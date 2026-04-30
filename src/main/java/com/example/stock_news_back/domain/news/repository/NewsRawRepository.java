package com.example.stock_news_back.domain.news.repository;

import com.example.stock_news_back.domain.news.entity.NewsRaw;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NewsRawRepository extends JpaRepository<NewsRaw, String> {
}
