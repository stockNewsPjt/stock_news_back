package com.example.stock_news_back.crawler.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "crawler")
public class CrawlerProperties {

    /** 수집 대상 티커 목록 */
    private List<String> targetTickers = new ArrayList<>(
            List.of("NVDA", "AAPL", "TSLA", "MSFT", "AMZN", "GOOGL", "META")
    );

    private Schedule schedule = new Schedule();

    @Getter
    @Setter
    public static class Schedule {
        private boolean enabled = true;
        /** 앱 시작 후 첫 실행까지 대기 시간 (ms) */
        private long initialDelayMs = 0L;
        /** 실행 완료 후 다음 실행까지 대기 시간 (ms), 기본 1시간 */
        private long fixedDelayMs = 3_600_000L;
    }
}
