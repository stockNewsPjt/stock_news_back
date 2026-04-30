package com.example.stock_news_back.domain.news.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "news_raw")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NewsRaw {

    @Id
    @Column(name = "news_id")
    private String newsId;

    @Column(nullable = false)
    private String ticker;

    @Column(name = "source_id", nullable = false)
    private String sourceId;

    @Column(nullable = false)
    private String headline;

    @Column(columnDefinition = "text")
    private String body;

    @Column(name = "source_url", length = 1024)
    private String sourceUrl;

    @Column(name = "news_sentiment")
    private String newsSentiment;

    @Column(name = "news_category")
    private String newsCategory;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "ingested_at", nullable = false)
    private LocalDateTime ingestedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NewsStatus status;
}
