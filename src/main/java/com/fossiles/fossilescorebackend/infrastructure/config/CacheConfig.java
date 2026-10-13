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

    /**
     * Listado completo de OPs para Centro de Producción y Organizador. Armarlo cuesta decenas de
     * segundos (una consulta por orden, ítem, cliente y envío), y esas pantallas solo lo usan
     * para clasificar cinchos y llenar el filtro por orden. Se invalida en cada escritura de
     * /api/production-orders y al planificar; el TTL cubre lo que escriben otros módulos.
     */
    public static final String PRODUCTION_ORDERS_LIST_CACHE = "productionOrdersList";
    private static final Duration PRODUCTION_ORDERS_LIST_TTL = Duration.ofMinutes(2);

    /**
     * Paneles del Centro (cola sin cuero, resumen de ventas del día, ventas a despachar). Se
     * recalculan en cada apertura y cada cambio de fecha; se invalidan con cualquier escritura
     * de tareas, órdenes o cuero.
     */
    public static final String PRODUCTION_CENTER_PANELS_CACHE = "productionCenterPanels";
    private static final Duration PRODUCTION_CENTER_PANELS_TTL = Duration.ofSeconds(60);
    private static final long PRODUCTION_CENTER_PANELS_MAX_ENTRIES = 100;

    @Bean
    public CacheManager cacheManager() {
        CaffeineCache salesDashboard = new CaffeineCache(
                SALES_DASHBOARD_CACHE,
                Caffeine.newBuilder()
                        .expireAfterWrite(SALES_DASHBOARD_TTL)
                        .maximumSize(SALES_DASHBOARD_MAX_ENTRIES)
                        .build());
        CaffeineCache productionOrdersList = new CaffeineCache(
                PRODUCTION_ORDERS_LIST_CACHE,
                Caffeine.newBuilder()
                        .expireAfterWrite(PRODUCTION_ORDERS_LIST_TTL)
                        .maximumSize(1)
                        .build());
        CaffeineCache productionCenterPanels = new CaffeineCache(
                PRODUCTION_CENTER_PANELS_CACHE,
                Caffeine.newBuilder()
                        .expireAfterWrite(PRODUCTION_CENTER_PANELS_TTL)
                        .maximumSize(PRODUCTION_CENTER_PANELS_MAX_ENTRIES)
                        .build());
        SimpleCacheManager manager = new SimpleCacheManager();
        manager.setCaches(List.of(salesDashboard, productionOrdersList, productionCenterPanels));
        return manager;
    }
}
