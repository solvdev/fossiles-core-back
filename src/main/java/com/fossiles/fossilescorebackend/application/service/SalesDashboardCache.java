package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.infrastructure.config.CacheConfig;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * Caché de 60 s de las respuestas del dashboard de ventas, con clave (fuente, desde, hasta, location, sitio).
 * Es programática (no @Cacheable) para que el cálculo corra dentro de una transacción de solo lectura
 * sin depender de proxies ni de auto-invocación. No se invalida por escrituras: la frescura máxima es el TTL.
 */
@Component
public class SalesDashboardCache {

    private final CacheManager cacheManager;
    private final TransactionTemplate readOnlyTx;

    public SalesDashboardCache(CacheManager cacheManager, PlatformTransactionManager transactionManager) {
        this.cacheManager = cacheManager;
        this.readOnlyTx = new TransactionTemplate(transactionManager);
        this.readOnlyTx.setReadOnly(true);
    }

    /**
     * Devuelve el valor cacheado de {@code key} o lo calcula dentro de una transacción de solo lectura.
     * Con {@code refresh} descarta la entrada existente y recalcula.
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, boolean refresh, Supplier<T> loader) {
        Cache cache = cacheManager.getCache(CacheConfig.SALES_DASHBOARD_CACHE);
        if (cache == null) {
            return readOnlyTx.execute(status -> loader.get());
        }
        if (refresh) {
            cache.evict(key);
        }
        try {
            return (T) cache.get(key, () -> readOnlyTx.execute(status -> loader.get()));
        } catch (Cache.ValueRetrievalException ex) {
            if (ex.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw ex;
        }
    }

    public static String key(String source, java.time.LocalDate from, java.time.LocalDate to, Long kioskLocationId) {
        return key(source, from, to, kioskLocationId, null);
    }

    /** Clave completa: el filtro de kiosko puede llegar como location POS (legacy) y/o como sitio de Finanzas. */
    public static String key(
            String source, java.time.LocalDate from, java.time.LocalDate to, Long kioskLocationId, Long siteId) {
        return source + "|" + from + "|" + to
                + "|" + (kioskLocationId != null ? kioskLocationId : "")
                + "|" + (siteId != null ? siteId : "");
    }
}
