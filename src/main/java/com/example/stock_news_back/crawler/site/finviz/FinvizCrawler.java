package com.example.stock_news_back.crawler.site.finviz;

import com.example.stock_news_back.crawler.config.CrawlerProperties;
import com.example.stock_news_back.crawler.core.AbstractNewsCrawler;
import com.example.stock_news_back.crawler.core.CrawledArticle;
import com.example.stock_news_back.crawler.core.NewsSourceInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.nodes.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Finviz 종목 페이지에서 뉴스 헤드라인을 수집한다.
 * URL: https://finviz.com/quote.ashx?t={TICKER}
 *
 * ─── 새 사이트 추가 방법 ───────────────────────────────────────────────
 * 1. crawler/site/{사이트명}/ 패키지 생성
 * 2. AbstractNewsCrawler 또는 NewsCrawler 직접 구현
 * 3. @Component 추가
 * → CrawlerManager가 자동으로 감지하여 등록·실행한다.
 * ────────────────────────────────────────────────────────────────────────
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "crawler.finviz.enabled", havingValue = "true")
public class FinvizCrawler extends AbstractNewsCrawler {

    private static final String SOURCE_ID = "FINVIZ";
    private static final String BASE_URL  = "https://finviz.com";
    private static final String QUOTE_URL = BASE_URL + "/quote.ashx?t=";

    /** 분당 5회 → 요청 간 12초 대기 */
    private static final long REQUEST_DELAY_MS = 12_000L;

    private static final DateTimeFormatter DATE_TIME_FMT =
            DateTimeFormatter.ofPattern("MMM-dd-yy hh:mma", Locale.ENGLISH);
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("hh:mma", Locale.ENGLISH);

    private final CrawlerProperties properties;

    @Override
    public String getSourceId() {
        return SOURCE_ID;
    }

    @Override
    public NewsSourceInfo getSourceInfo() {
        return new NewsSourceInfo(SOURCE_ID, "Finviz", BASE_URL, 5);
    }

    @Override
    public List<CrawledArticle> crawl() {
        List<String> tickers = properties.getTargetTickers();
        List<CrawledArticle> result = new ArrayList<>();

        for (int i = 0; i < tickers.size(); i++) {
            String ticker = tickers.get(i);
            try {
                List<CrawledArticle> articles = crawlTicker(ticker);
                result.addAll(articles);
                log.debug("[Finviz] ticker={}, 수집={}", ticker, articles.size());
            } catch (Exception e) {
                log.warn("[Finviz] ticker={} 수집 실패: {}", ticker, e.getMessage());
            }

            if (i < tickers.size() - 1) {
                sleep(REQUEST_DELAY_MS);
            }
        }

        return result;
    }

    private List<CrawledArticle> crawlTicker(String ticker) throws Exception {
        Document doc = fetchDocument(QUOTE_URL + ticker);

        Element newsTable = doc.getElementById("news-table");
        if (newsTable == null) {
            log.debug("[Finviz] ticker={} news-table 없음 (로그인 필요 or 구조 변경)", ticker);
            return Collections.emptyList();
        }

        List<CrawledArticle> articles = new ArrayList<>();
        LocalDate currentDate = LocalDate.now();

        for (Element row : newsTable.select("tr")) {
            Elements cells = row.select("td");
            if (cells.size() < 2) continue;

            String dateText = cells.get(0).text().trim();
            Element newsLink = cells.get(1).selectFirst("a.tab-link-news");
            if (newsLink == null) continue;

            String headline  = newsLink.text().trim();
            String sourceUrl = newsLink.attr("href");
            if (headline.isEmpty() || sourceUrl.isEmpty()) continue;

            LocalDateTime publishedAt = parseDateTime(dateText, currentDate);
            // 날짜가 포함된 셀(하이픈 존재)이면 currentDate 갱신
            if (dateText.contains("-")) {
                currentDate = publishedAt.toLocalDate();
            }

            articles.add(new CrawledArticle(ticker, headline, null, sourceUrl, publishedAt));
        }

        return articles;
    }

    /**
     * Finviz 날짜 형식 파싱
     * - 날짜+시간: "Apr-30-26 05:30PM"
     * - 시간만:    "07:15PM" (같은 날 이후 행)
     */
    private LocalDateTime parseDateTime(String text, LocalDate fallbackDate) {
        if (text.isBlank()) return LocalDateTime.now();

        try {
            if (text.contains("-")) {
                return LocalDateTime.parse(text, DATE_TIME_FMT);
            }
            LocalTime time = LocalTime.parse(text, TIME_FMT);
            return LocalDateTime.of(fallbackDate, time);
        } catch (Exception e) {
            log.trace("[Finviz] 날짜 파싱 실패: '{}', fallback=now", text);
            return LocalDateTime.now();
        }
    }
}
