package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.infrastructure.config.CacheConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class SalesDashboardCacheTest {

    private SalesDashboardCache cache;

    @BeforeEach
    void setUp() {
        SimpleCacheManager manager = (SimpleCacheManager) new CacheConfig().cacheManager();
        manager.afterPropertiesSet();
        cache = new SalesDashboardCache(manager, mock(PlatformTransactionManager.class));
    }

    @Test
    void secondCallWithSameKeyIsServedFromCache() {
        AtomicInteger loads = new AtomicInteger();

        String first = cache.get("k", false, () -> "v" + loads.incrementAndGet());
        String second = cache.get("k", false, () -> "v" + loads.incrementAndGet());

        assertThat(first).isEqualTo("v1");
        assertThat(second).isEqualTo("v1");
        assertThat(loads.get()).isEqualTo(1);
    }

    @Test
    void refreshEvictsOnlyThatKeyAndRecomputes() {
        AtomicInteger loads = new AtomicInteger();
        cache.get("a", false, () -> "a" + loads.incrementAndGet());
        cache.get("b", false, () -> "b" + loads.incrementAndGet());

        String refreshed = cache.get("a", true, () -> "a" + loads.incrementAndGet());
        String untouched = cache.get("b", false, () -> "b" + loads.incrementAndGet());

        assertThat(refreshed).isEqualTo("a3");
        assertThat(untouched).isEqualTo("b2");
        assertThat(cache.get("a", false, () -> "again")).isEqualTo("a3");
    }

    @Test
    void loaderRuntimeExceptionIsPropagatedAndNotCached() {
        assertThatThrownBy(() -> cache.get("x", false, () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class).hasMessage("boom");

        assertThat(cache.get("x", false, () -> "ok")).isEqualTo("ok");
    }

    @Test
    void keyIncludesSourceDatesAndKiosk() {
        LocalDate from = LocalDate.of(2026, 9, 1);
        LocalDate to = LocalDate.of(2026, 9, 30);

        assertThat(SalesDashboardCache.key("KIOSKO", from, to, 4L)).isEqualTo("KIOSKO|2026-09-01|2026-09-30|4|");
        assertThat(SalesDashboardCache.key("ONLINE", from, to, null)).isEqualTo("ONLINE|2026-09-01|2026-09-30||");
    }

    @Test
    void keyIncludesSiteIdAndKioskLocationIdInSeparateSlots() {
        LocalDate from = LocalDate.of(2026, 9, 1);
        LocalDate to = LocalDate.of(2026, 9, 30);

        assertThat(SalesDashboardCache.key("KIOSKO", from, to, null, 7L)).isEqualTo("KIOSKO|2026-09-01|2026-09-30||7");
        assertThat(SalesDashboardCache.key("KIOSKO", from, to, 4L, 7L)).isEqualTo("KIOSKO|2026-09-01|2026-09-30|4|7");
        assertThat(SalesDashboardCache.key("KIOSKO", from, to, null, null)).isEqualTo("KIOSKO|2026-09-01|2026-09-30||");
        // Mismo número como location POS y como sitio son consultas distintas: no deben compartir entrada.
        assertThat(SalesDashboardCache.key("KIOSKO", from, to, 4L, null))
                .isNotEqualTo(SalesDashboardCache.key("KIOSKO", from, to, null, 4L));
        assertThat(SalesDashboardCache.key("KIOSKO", from, to, 4L))
                .isEqualTo(SalesDashboardCache.key("KIOSKO", from, to, 4L, null));
    }
}
