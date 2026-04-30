package com.example.stock_news_back.crawler.core;

/**
 * 크롤러가 자신의 출처 정보를 선언할 때 사용하는 DTO.
 * news_source_registry 테이블에 자동으로 등록된다.
 */
public record NewsSourceInfo(
        String sourceId,
        String name,
        String baseUrl,
        int rateLimitPerMin
) {
}
