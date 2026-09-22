package com.ashish.stockresearch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class StockResearchApplication {

	public static void main(String[] args) {
		SpringApplication.run(StockResearchApplication.class, args);
	}

}
