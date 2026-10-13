package com.fossiles.fossilescorebackend.infrastructure.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final ProductionCenterCacheInvalidator productionCenterCacheInvalidator;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(productionCenterCacheInvalidator)
                .addPathPatterns("/api/production-orders/**", "/api/tasks/**",
                        "/api/leather/**", "/api/product-variant-leathers/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(
                    "http://localhost",
                    "https://localhost",
                    "http://localhost:3000", 
                    "http://localhost:3001",
                    "http://core.fossilescorp.com",
                    "https://core.fossilescorp.com",
                    "https://coretest.fossilescorp.com",
                    "http://coretest.fossilescorp.com"
                )
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}

