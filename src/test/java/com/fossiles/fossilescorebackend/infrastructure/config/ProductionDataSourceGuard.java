package com.fossiles.fossilescorebackend.infrastructure.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * Tests must not open the production RDS. application.properties defaults to that host
 * when SPRING_DATASOURCE_URL is unset, and an environment variable would override the
 * test properties. This forces H2 whenever that host is still selected.
 * A later {@code @DynamicPropertySource} (Testcontainers) replaces it.
 */
public class ProductionDataSourceGuard implements EnvironmentPostProcessor {

    static final String PRODUCTION_HOST = "fossiles-gt.czaiugi22fpp.us-east-2.rds.amazonaws.com";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String url = environment.getProperty("spring.datasource.url");
        if (url == null || !url.contains(PRODUCTION_HOST)) {
            return;
        }
        Map<String, Object> forced = new HashMap<>();
        forced.put("spring.datasource.url",
                "jdbc:h2:mem:fossiles_test;MODE=PostgreSQL;NON_KEYWORDS=YEAR,MONTH;DB_CLOSE_DELAY=-1");
        forced.put("spring.datasource.username", "sa");
        forced.put("spring.datasource.password", "");
        forced.put("spring.datasource.driver-class-name", "org.h2.Driver");
        forced.put("spring.jpa.hibernate.ddl-auto", "create-drop");
        environment.getPropertySources().addFirst(new MapPropertySource("block-production-rds", forced));
    }
}
