package com.example.stock_news_back.domain.news.repository;

import com.example.stock_news_back.domain.news.entity.NewsSourceRegistry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NewsSourceRegistryRepository extends JpaRepository<NewsSourceRegistry, String> {
}
