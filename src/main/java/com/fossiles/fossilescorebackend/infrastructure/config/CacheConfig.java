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

    /**
     * Lecturas de la vista de Ventas en línea (por fecha, rango, resumen, elegibles, devoluciones). Se invalidan
     * con cualquier escritura de los módulos que las alimentan (ver {@code OnlineSalesCacheInvalidator}); el TTL
     * cubre lo que se escriba fuera de HTTP (jobs).
     */
    public static final String ONLINE_SALES_READS_CACHE = "onlineSalesReads";
    private static final Duration ONLINE_SALES_READS_TTL = Duration.ofSeconds(30);
    private static final long ONLINE_SALES_READS_MAX_ENTRIES = 100;

    /** Catálogos de productos y colores que las pantallas cargan al abrir. Cambian muy poco. */
    public static final String CATALOG_READS_CACHE = "catalogReads";
    private static final Duration CATALOG_READS_TTL = Duration.ofSeconds(60);
    private static final long CATALOG_READS_MAX_ENTRIES = 10;

    /** Vista de bodega (órdenes con su destino de despacho). Depende de varios módulos; TTL corto. */
    public static final String WAREHOUSE_VIEW_CACHE = "warehouseView";
    private static final Duration WAREHOUSE_VIEW_TTL = Duration.ofSeconds(30);
    private static final long WAREHOUSE_VIEW_MAX_ENTRIES = 20;

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
        CaffeineCache onlineSalesReads = new CaffeineCache(
                ONLINE_SALES_READS_CACHE,
                Caffeine.newBuilder()
                        .expireAfterWrite(ONLINE_SALES_READS_TTL)
                        .maximumSize(ONLINE_SALES_READS_MAX_ENTRIES)
                        .build());
        CaffeineCache catalogReads = new CaffeineCache(
                CATALOG_READS_CACHE,
                Caffeine.newBuilder()
                        .expireAfterWrite(CATALOG_READS_TTL)
                        .maximumSize(CATALOG_READS_MAX_ENTRIES)
                        .build());
        CaffeineCache warehouseView = new CaffeineCache(
                WAREHOUSE_VIEW_CACHE,
                Caffeine.newBuilder()
                        .expireAfterWrite(WAREHOUSE_VIEW_TTL)
                        .maximumSize(WAREHOUSE_VIEW_MAX_ENTRIES)
                        .build());
        SimpleCacheManager manager = new SimpleCacheManager();
        manager.setCaches(List.of(
                salesDashboard, productionOrdersList, productionCenterPanels,
                onlineSalesReads, catalogReads, warehouseView));
        return manager;
    }
}
