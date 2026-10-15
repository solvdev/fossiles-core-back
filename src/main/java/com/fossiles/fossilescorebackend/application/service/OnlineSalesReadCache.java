package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.infrastructure.config.CacheConfig;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Caché de las lecturas de la vista de Ventas en línea: ventas por fecha/rango, resumen, elegibles para
 * producción, devoluciones, catálogos de productos/colores y vista de bodega. Programática, igual que
 * {@link ProductionCenterCache}, para que quien escribe invalide sin depender de proxies.
 *
 * <p>Cada dominio lleva un contador de versión: si hay una invalidación mientras una lectura se está calculando,
 * ese resultado (leído antes de la escritura) no se guarda, para que una carrera no deje datos viejos hasta el TTL.
 */
@Component
public class OnlineSalesReadCache {

    private final CacheManager cacheManager;
    private final Map<String, AtomicLong> versions = new ConcurrentHashMap<>();

    public OnlineSalesReadCache(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    public <T> T sales(String key, Supplier<T> loader) {
        return get(CacheConfig.ONLINE_SALES_READS_CACHE, key, loader::get);
    }

    /** Igual que {@link #sales} para cargas que lanzan excepciones comprobadas (p. ej. BusinessException). */
    public <T, E extends Exception> T salesChecked(String key, CheckedSupplier<T, E> loader) throws E {
        return get(CacheConfig.ONLINE_SALES_READS_CACHE, key, loader);
    }

    @FunctionalInterface
    public interface CheckedSupplier<T, E extends Exception> {
        T get() throws E;
    }

    public <T> T catalog(String key, Supplier<T> loader) {
        return get(CacheConfig.CATALOG_READS_CACHE, key, loader::get);
    }

    public <T> T warehouse(String key, Supplier<T> loader) {
        return get(CacheConfig.WAREHOUSE_VIEW_CACHE, key, loader::get);
    }

    public void evictSales() {
        clear(CacheConfig.ONLINE_SALES_READS_CACHE);
    }

    public void evictCatalog() {
        clear(CacheConfig.CATALOG_READS_CACHE);
    }

    public void evictWarehouse() {
        clear(CacheConfig.WAREHOUSE_VIEW_CACHE);
    }

    /** Clave estable a partir de los parámetros de la consulta (los nulos cuentan como vacíos). */
    public static String key(String name, Object... params) {
        StringBuilder sb = new StringBuilder(name);
        for (Object param : params) {
            sb.append('|').append(param == null ? "" : param);
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private <T, E extends Exception> T get(String cacheName, String key, CheckedSupplier<T, E> loader)
            throws E {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache == null) {
            return loader.get();
        }
        Cache.ValueWrapper hit = cache.get(key);
        if (hit != null && hit.get() != null) {
            return (T) hit.get();
        }
        AtomicLong version = version(cacheName);
        long before = version.get();
        T value = loader.get();
        if (value != null && version.get() == before) {
            cache.put(key, value);
        }
        return value;
    }

    private void clear(String cacheName) {
        // Primero la versión: una lectura en curso ya no podrá guardar su resultado.
        version(cacheName).incrementAndGet();
        Cache cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.clear();
        }
    }

    private AtomicLong version(String cacheName) {
        return versions.computeIfAbsent(cacheName, k -> new AtomicLong());
    }
}
