package com.example.stock_news_back.crawler.core;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.IOException;

/**
 * HTML 기반 크롤러의 공통 유틸리티.
 * RSS/API 기반 크롤러는 이 클래스를 상속하지 않아도 된다.
 */
@Slf4j
public abstract class AbstractNewsCrawler implements NewsCrawler {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36";

    /**
     * Jsoup으로 HTML 문서 요청.
     * 사이트별로 헤더를 추가해야 한다면 오버라이드하거나 직접 Jsoup을 사용한다.
     */
    protected Document fetchDocument(String url) throws IOException {
        return Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .referrer("https://www.google.com")
                .timeout(15_000)
                .maxBodySize(0)
                .get();
    }

    /**
     * 요청 간 대기. rate_limit_per_min 준수에 사용.
     * ex) 분당 5회 → 12_000ms
     */
    protected void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
