package com.example.stock_news_back;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class StockNewsBackApplication {

	public static void main(String[] args) {
		SpringApplication.run(StockNewsBackApplication.class, args);
	}

}
