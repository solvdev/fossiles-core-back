package com.fossiles.fossilescorebackend.infrastructure.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

@Configuration
@EnableCaching
public class CacheConfig {

    public static final String SALES_DASHBOARD_CACHE = "salesDashboard";

    private static final Duration SALES_DASHBOARD_TTL = Duration.ofSeconds(60);
    private static final long SALES_DASHBOARD_MAX_ENTRIES = 300;

    @Bean
    public CacheManager cacheManager() {
        CaffeineCache salesDashboard = new CaffeineCache(
                SALES_DASHBOARD_CACHE,
                Caffeine.newBuilder()
                        .expireAfterWrite(SALES_DASHBOARD_TTL)
                        .maximumSize(SALES_DASHBOARD_MAX_ENTRIES)
                        .build());
        SimpleCacheManager manager = new SimpleCacheManager();
        manager.setCaches(List.of(salesDashboard));
        return manager;
    }
}
