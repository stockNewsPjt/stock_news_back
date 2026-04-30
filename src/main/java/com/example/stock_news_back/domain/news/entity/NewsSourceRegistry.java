package com.example.stock_news_back.domain.news.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "news_source_registry")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NewsSourceRegistry {

    @Id
    @Column(name = "source_id")
    private String sourceId;

    @Column(nullable = false)
    private String name;

    @Column(name = "base_url")
    private String baseUrl;

    @Column(name = "robots_checked_at")
    private LocalDateTime robotsCheckedAt;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "rate_limit_per_min")
    private int rateLimitPerMin;
}
