package com.example.stock_news_back.crawler.scheduler;

import com.example.stock_news_back.crawler.config.CrawlerProperties;
import com.example.stock_news_back.crawler.manager.CrawlerManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CrawlerScheduler {

    private final CrawlerManager crawlerManager;
    private final CrawlerProperties properties;

    /**
     * 앱 시작 즉시 1회 실행 후, 매 실행 완료 시점 기준 1시간마다 반복.
     * initial-delay-ms / fixed-delay-ms 로 조정 가능.
     */
//    @Scheduled(
//            initialDelayString = "${crawler.schedule.initial-delay-ms:0}",
//            fixedDelayString   = "${crawler.schedule.fixed-delay-ms:3600000}"
//    )
    @Scheduled(fixedDelay = 10_000)
    public void scheduledCrawl() {
        if (!properties.getSchedule().isEnabled()) {
            return;
        }
        log.info("[스케줄 크롤링 시작]");
        crawlerManager.runAll();
        log.info("[스케줄 크롤링 종료]");
    }
}
