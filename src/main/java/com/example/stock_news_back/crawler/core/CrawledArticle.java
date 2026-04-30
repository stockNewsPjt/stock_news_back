package com.example.stock_news_back.crawler.core;

import java.time.LocalDateTime;

/**
 * 크롤러가 반환하는 원시 기사 데이터.
 * sentiment/category는 API가 제공하는 경우에만 값이 채워진다 (HTML 크롤러는 null).
 */
public record CrawledArticle(
        String ticker,
        String headline,
        String body,
        String sourceUrl,
        LocalDateTime publishedAt,
        String sentiment,
        String category
) {
    /** HTML 크롤러처럼 sentiment/category 없이 생성할 때 사용 */
    public CrawledArticle(String ticker, String headline, String body,
                          String sourceUrl, LocalDateTime publishedAt) {
        this(ticker, headline, body, sourceUrl, publishedAt, null, null);
    }
}
