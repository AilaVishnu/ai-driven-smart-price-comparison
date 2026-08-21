package com.spc.pricecompare;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AI-Driven Smart Price Comparison System.
 *
 * <p>Caching is enabled because provider quota is scarce: a cached search is a
 * call not spent. Scheduling drives the periodic price refresh that builds the
 * history the forecaster reads.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableCaching
@EnableScheduling
public class PricecompareApplication {

	public static void main(String[] args) {
		SpringApplication.run(PricecompareApplication.class, args);
	}

}
