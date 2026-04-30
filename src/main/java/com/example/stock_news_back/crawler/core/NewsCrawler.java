package com.example.stock_news_back.crawler.core;

import java.util.List;

/**
 * 새 사이트 크롤러를 추가하려면 이 인터페이스를 구현하고 @Component를 달면 된다.
 * CrawlerManager가 모든 구현체를 자동으로 감지하여 등록·실행한다.
 */
public interface NewsCrawler {

    /** news_source_registry.source_id와 매핑되는 고유 식별자 */
    String getSourceId();

    /** DB 자동 등록에 사용할 출처 메타정보 */
    NewsSourceInfo getSourceInfo();

    /** 뉴스 수집 실행. 실패 시 예외를 던지지 않고 빈 리스트 반환 권장 */
    List<CrawledArticle> crawl();
}
