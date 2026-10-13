package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.config.CacheConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OnlineSalesReadCacheTest {

    private OnlineSalesReadCache cache;

    @BeforeEach
    void setUp() {
        org.springframework.cache.support.SimpleCacheManager manager =
                (org.springframework.cache.support.SimpleCacheManager) new CacheConfig().cacheManager();
        manager.afterPropertiesSet();
        cache = new OnlineSalesReadCache(manager);
    }

    @Test
    void segundaLecturaConLosMismosParametrosSalDeLaCache() {
        AtomicInteger loads = new AtomicInteger();
        String key = OnlineSalesReadCache.key("by-date", "2026-10-13");

        List<String> first = cache.sales(key, () -> List.of("venta-" + loads.incrementAndGet()));
        List<String> second = cache.sales(key, () -> List.of("venta-" + loads.incrementAndGet()));

        assertThat(second).isEqualTo(first);
        assertThat(loads).hasValue(1);
    }

    @Test
    void parametrosDistintosNoComparteneEntrada() {
        AtomicInteger loads = new AtomicInteger();

        cache.sales(OnlineSalesReadCache.key("by-date", "2026-10-13"), loads::incrementAndGet);
        cache.sales(OnlineSalesReadCache.key("by-date", "2026-10-14"), loads::incrementAndGet);

        assertThat(loads).hasValue(2);
    }

    @Test
    void invalidarObligaARecalcular() {
        AtomicInteger loads = new AtomicInteger();
        String key = OnlineSalesReadCache.key("by-date", "2026-10-13");
        cache.sales(key, loads::incrementAndGet);

        cache.evictSales();
        Integer afterEvict = cache.sales(key, loads::incrementAndGet);

        assertThat(afterEvict).isEqualTo(2);
    }

    @Test
    void unaLecturaEnCursoQueTerminaDespuesDeUnaEscrituraNoSeGuarda() {
        AtomicInteger loads = new AtomicInteger();
        String key = OnlineSalesReadCache.key("by-date", "2026-10-13");

        // La carga empieza, llega una escritura (invalida) y recién entonces termina de leer.
        Integer stale = cache.sales(key, () -> {
            cache.evictSales();
            return loads.incrementAndGet();
        });
        Integer fresh = cache.sales(key, loads::incrementAndGet);

        assertThat(stale).isEqualTo(1);
        assertThat(fresh).isEqualTo(2);
    }

    @Test
    void noGuardaResultadosNulos() {
        AtomicInteger loads = new AtomicInteger();
        String key = OnlineSalesReadCache.key("x");

        cache.sales(key, () -> {
            loads.incrementAndGet();
            return null;
        });
        cache.sales(key, () -> {
            loads.incrementAndGet();
            return null;
        });

        assertThat(loads).hasValue(2);
    }

    @Test
    void laExcepcionDeLaCargaSePropagaYNoSeGuarda() {
        String key = OnlineSalesReadCache.key("summary", "2026-10-13");

        assertThatThrownBy(() -> cache.salesChecked(key, () -> {
            throw new BusinessException("rango invalido");
        })).isInstanceOf(BusinessException.class).hasMessageContaining("rango invalido");

        assertThat(cache.<String, RuntimeException>salesChecked(key, () -> "ok")).isEqualTo("ok");
    }
}
