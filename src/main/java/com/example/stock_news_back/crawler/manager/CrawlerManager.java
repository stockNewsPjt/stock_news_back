package com.example.stock_news_back.crawler.manager;

import com.example.stock_news_back.crawler.core.CrawledArticle;
import com.example.stock_news_back.crawler.core.NewsCrawler;
import com.example.stock_news_back.crawler.core.NewsSourceInfo;
import com.example.stock_news_back.domain.news.entity.NewsRaw;
import com.example.stock_news_back.domain.news.entity.NewsSourceRegistry;
import com.example.stock_news_back.domain.news.entity.NewsStatus;
import com.example.stock_news_back.domain.news.repository.NewsRawRepository;
import com.example.stock_news_back.domain.news.repository.NewsSourceRegistryRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class CrawlerManager {

    private final List<NewsCrawler> crawlers;
    private final NewsRawRepository newsRawRepository;
    private final NewsSourceRegistryRepository sourceRegistryRepository;

    /** 애플리케이션 기동 시 각 크롤러의 출처 정보를 DB에 자동 등록 */
    @PostConstruct
    @Transactional
    public void initSources() {
        for (NewsCrawler crawler : crawlers) {
            NewsSourceInfo info = crawler.getSourceInfo();
            if (!sourceRegistryRepository.existsById(info.sourceId())) {
                sourceRegistryRepository.save(toSourceEntity(info));
                log.info("[출처 등록] sourceId={}, name={}", info.sourceId(), info.name());
            }
        }
    }

    /** 활성화된 모든 크롤러를 순서대로 실행 */
    public void runAll() {
        for (NewsCrawler crawler : crawlers) {
            if (!isEnabled(crawler.getSourceId())) {
                log.debug("[크롤러 스킵] sourceId={} (비활성)", crawler.getSourceId());
                continue;
            }
            run(crawler);
        }
    }

    private void run(NewsCrawler crawler) {
        String sourceId = crawler.getSourceId();
        log.info("[크롤러 시작] sourceId={}", sourceId);
        try {
            List<CrawledArticle> articles = crawler.crawl();
            int saved = save(articles, sourceId);
            log.info("[크롤러 완료] sourceId={}, 수집={}, 신규저장={}", sourceId, articles.size(), saved);
        } catch (Exception e) {
            log.error("[크롤러 오류] sourceId={}, message={}", sourceId, e.getMessage(), e);
        }
    }

    @Transactional
    protected int save(List<CrawledArticle> articles, String sourceId) {
        int saved = 0;
        for (CrawledArticle article : articles) {
            String newsId = generateNewsId(sourceId, article.ticker(), article.sourceUrl());
            if (!newsRawRepository.existsById(newsId)) {
                newsRawRepository.save(toNewsRawEntity(newsId, sourceId, article));
                saved++;
            }
        }
        return saved;
    }

    private boolean isEnabled(String sourceId) {
        return sourceRegistryRepository.findById(sourceId)
                .map(NewsSourceRegistry::isEnabled)
                .orElse(false);
    }

    /**
     * 동일 ticker + URL 조합에 대해 항상 같은 ID가 생성되어 중복 저장을 방지한다.
     */
    private String generateNewsId(String sourceId, String ticker, String url) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((ticker + url).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return sourceId + "_" + hex;
        } catch (NoSuchAlgorithmException e) {
            return sourceId + "_" + Integer.toHexString((ticker + url).hashCode());
        }
    }

    private NewsRaw toNewsRawEntity(String newsId, String sourceId, CrawledArticle article) {
        return NewsRaw.builder()
                .newsId(newsId)
                .ticker(article.ticker())
                .sourceId(sourceId)
                .headline(article.headline())
                .body(article.body())
                .sourceUrl(article.sourceUrl())
                .newsSentiment(article.sentiment())
                .newsCategory(article.category())
                .publishedAt(article.publishedAt())
                .ingestedAt(LocalDateTime.now())
                .status(NewsStatus.INGESTED)
                .build();
    }

    private NewsSourceRegistry toSourceEntity(NewsSourceInfo info) {
        return NewsSourceRegistry.builder()
                .sourceId(info.sourceId())
                .name(info.name())
                .baseUrl(info.baseUrl())
                .enabled(true)
                .rateLimitPerMin(info.rateLimitPerMin())
                .build();
    }
}
