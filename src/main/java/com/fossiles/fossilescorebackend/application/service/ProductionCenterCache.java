package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.infrastructure.config.CacheConfig;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Caché de las lecturas pesadas del Centro de Producción: el listado completo de OPs y los
 * paneles del día. Programática, igual que {@link SalesDashboardCache}, para que quien escribe
 * pueda invalidar sin depender de proxies.
 *
 * <p>Las tareas no se cachean: cambian con cada clic en el tablero (iniciar, completar,
 * troquelar) y una lista vieja pisaría el cambio que el usuario acaba de ver.
 */
@Component
public class ProductionCenterCache {

    private static final String ORDERS_KEY = "all";

    private final CacheManager cacheManager;

    public ProductionCenterCache(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    public <T> T orders(Supplier<T> loader) {
        return get(CacheConfig.PRODUCTION_ORDERS_LIST_CACHE, ORDERS_KEY, loader);
    }

    public <T> T panel(String key, Supplier<T> loader) {
        return get(CacheConfig.PRODUCTION_CENTER_PANELS_CACHE, key, loader);
    }

    public void evictOrders() {
        clear(CacheConfig.PRODUCTION_ORDERS_LIST_CACHE);
    }

    public void evictPanels() {
        clear(CacheConfig.PRODUCTION_CENTER_PANELS_CACHE);
    }

    @SuppressWarnings("unchecked")
    private <T> T get(String cacheName, String key, Supplier<T> loader) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache == null) {
            return loader.get();
        }
        try {
            return (T) cache.get(key, loader::get);
        } catch (Cache.ValueRetrievalException ex) {
            if (ex.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw ex;
        }
    }

    private void clear(String cacheName) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.clear();
        }
    }
}
