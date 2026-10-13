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
    private final OnlineSalesCacheInvalidator onlineSalesCacheInvalidator;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(productionCenterCacheInvalidator)
                .addPathPatterns("/api/production-orders/**", "/api/tasks/**",
                        "/api/leather/**", "/api/product-variant-leathers/**");
        registry.addInterceptor(onlineSalesCacheInvalidator)
                .addPathPatterns("/api/online-sales/**", "/api/tax-invoices/**", "/api/production-orders/**",
                        "/api/public/online-warehouse/**", "/api/partial-releases/**",
                        "/api/product-distributions/**", "/api/distribuciones/**",
                        "/api/internal-shipment-requests/**", "/api/tasks/**", "/api/products/**",
                        "/api/colors/**", "/api/product-categories/**");
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

