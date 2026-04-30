package com.example.stock_news_back.crawler.site.alphavantage;

import com.example.stock_news_back.crawler.config.CrawlerProperties;
import com.example.stock_news_back.crawler.core.CrawledArticle;
import com.example.stock_news_back.crawler.core.NewsCrawler;
import com.example.stock_news_back.crawler.core.NewsSourceInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Alpha Vantage News & Sentiment API 크롤러.
 * ticker + topic 필터링으로 미국 AI/IT 관련 기사만 수집한다.
 *
 * API 문서: https://www.alphavantage.co/documentation/#news-sentiment
 * 무료 한도: 25회/일 → 1시간 주기(24회/일)로 여유 있게 사용 가능
 */
@Slf4j
@Component
public class AlphaVantageCrawler implements NewsCrawler {

    private static final String SOURCE_ID = "ALPHAVANTAGE";
    private static final DateTimeFormatter AV_PUBLISHED_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
    private static final DateTimeFormatter AV_TIME_FROM_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm");

    @Value("${alphavantage.api-key}")
    private String apiKey;

    @Value("${alphavantage.topics:technology,artificial_intelligence}")
    private String topics;

    @Value("${alphavantage.lookback-hours:2}")
    private int lookbackHours;

    private final CrawlerProperties crawlerProperties;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public AlphaVantageCrawler(
            @Value("${alphavantage.base-url:https://www.alphavantage.co}") String baseUrl,
            CrawlerProperties crawlerProperties,
            ObjectMapper objectMapper) {
        this.crawlerProperties = crawlerProperties;
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(5 * 1024 * 1024))
                .build();
    }

    @Override
    public String getSourceId() {
        return SOURCE_ID;
    }

    @Override
    public NewsSourceInfo getSourceInfo() {
        return new NewsSourceInfo(SOURCE_ID, "Alpha Vantage", "https://www.alphavantage.co", 25);
    }

    @Override
    public List<CrawledArticle> crawl() {
        String tickerParam = String.join(",", crawlerProperties.getTargetTickers());
        String timeFrom = LocalDateTime.now(java.time.ZoneOffset.UTC)
                .minusHours(lookbackHours)
                .format(AV_TIME_FROM_FMT);

        log.info("[AlphaVantage] 요청 tickers={}, topics={}, time_from={}", tickerParam, topics, timeFrom);

        try {
            String json = webClient.get()
                    .uri(builder -> builder
                            .path("/query")
                            .queryParam("function", "NEWS_SENTIMENT")
                            .queryParam("tickers", tickerParam)
                            .queryParam("topics", topics)
                            .queryParam("time_from", timeFrom)
                            .queryParam("sort", "LATEST")
                            .queryParam("limit", "200")
                            .queryParam("apikey", apiKey)
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(30))
                    .block();

            return parse(json);

        } catch (Exception e) {
            log.error("[AlphaVantage] API 호출 실패: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    private List<CrawledArticle> parse(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);

        // 무료 한도 초과 또는 잘못된 API 키 감지
        if (root.has("Information")) {
            log.warn("[AlphaVantage] API 제한 메시지: {}", root.get("Information").asText());
            return Collections.emptyList();
        }
        if (root.has("Note")) {
            log.warn("[AlphaVantage] API Note: {}", root.get("Note").asText());
            return Collections.emptyList();
        }
        if (root.has("Error Message")) {
            log.error("[AlphaVantage] API 오류: {}", root.get("Error Message").asText());
            return Collections.emptyList();
        }

        String itemsCount = root.path("items").asText("?");
        log.info("[AlphaVantage] API 응답 items={}", itemsCount);

        JsonNode feed = root.path("feed");
        if (!feed.isArray() || feed.isEmpty()) {
            log.info("[AlphaVantage] feed 없음 — 응답 키 목록: {}", root.fieldNames());
            return Collections.emptyList();
        }

        Set<String> targetTickers = Set.copyOf(crawlerProperties.getTargetTickers());
        List<CrawledArticle> articles = new ArrayList<>();

        for (JsonNode item : feed) {
            String headline     = item.path("title").asText("");
            String sourceUrl    = item.path("url").asText("");
            String summary      = item.path("summary").asText(null);
            String timeStr      = item.path("time_published").asText("");
            String overallLabel = item.path("overall_sentiment_label").asText(null);
            String category     = extractPrimaryTopic(item.path("topics"));

            if (headline.isEmpty() || sourceUrl.isEmpty()) continue;

            LocalDateTime publishedAt = parseTime(timeStr);

            /*
             * ticker_sentiment 배열에서 target ticker별로 개별 row 생성.
             * 동일 기사가 NVDA, MSFT 모두 관련이면 → 각각 별도 저장.
             * (CrawlerManager의 SHA-256 기반 ID로 중복 방지)
             */
            JsonNode tickerSentiments = item.path("ticker_sentiment");
            if (tickerSentiments.isArray() && !tickerSentiments.isEmpty()) {
                for (JsonNode ts : tickerSentiments) {
                    String ticker = ts.path("ticker").asText("");
                    if (!targetTickers.contains(ticker)) continue;

                    // ticker 개별 감성이 있으면 우선 사용, 없으면 overall 사용
                    String sentiment = ts.has("ticker_sentiment_label")
                            ? ts.path("ticker_sentiment_label").asText(overallLabel)
                            : overallLabel;

                    articles.add(new CrawledArticle(
                            ticker, headline, summary, sourceUrl, publishedAt, sentiment, category
                    ));
                }
            }
        }

        log.info("[AlphaVantage] 파싱 완료: feed={}, target ticker 매칭={}", feed.size(), articles.size());
        return articles;
    }

    /** topics 배열에서 relevance_score 가장 높은 항목의 topic 문자열 반환 */
    private String extractPrimaryTopic(JsonNode topics) {
        if (!topics.isArray() || topics.isEmpty()) return null;
        String primary = null;
        double maxScore = -1;
        for (JsonNode t : topics) {
            double score = t.path("relevance_score").asDouble(0);
            if (score > maxScore) {
                maxScore = score;
                primary = t.path("topic").asText(null);
            }
        }
        return primary;
    }

    /** Alpha Vantage time_published 포맷(yyyyMMddTHHmmss) → LocalDateTime */
    private LocalDateTime parseTime(String timeStr) {
        if (timeStr == null || timeStr.isBlank()) return LocalDateTime.now();
        try {
            return LocalDateTime.parse(timeStr, AV_PUBLISHED_FMT);
        } catch (Exception e) {
            log.trace("[AlphaVantage] 날짜 파싱 실패: '{}'", timeStr);
            return LocalDateTime.now();
        }
    }
}
