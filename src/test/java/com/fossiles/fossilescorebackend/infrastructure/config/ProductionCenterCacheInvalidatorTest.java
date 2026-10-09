package com.fossiles.fossilescorebackend.infrastructure.config;

import com.fossiles.fossilescorebackend.application.service.ProductionCenterCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La caché del Centro se sirve mientras nadie escriba, y se suelta con la primera escritura
 * exitosa en órdenes, tareas o cuero.
 */
class ProductionCenterCacheInvalidatorTest {

    private ProductionCenterCache cache;
    private ProductionCenterCacheInvalidator invalidator;
    private final AtomicInteger ordersLoads = new AtomicInteger();
    private final AtomicInteger panelLoads = new AtomicInteger();

    @BeforeEach
    void setUp() {
        // Fuera de Spring el SimpleCacheManager no se inicializa solo.
        SimpleCacheManager manager = (SimpleCacheManager) new CacheConfig().cacheManager();
        manager.afterPropertiesSet();
        cache = new ProductionCenterCache(manager);
        invalidator = new ProductionCenterCacheInvalidator(cache);
    }

    private void readBoth() {
        cache.orders(ordersLoads::incrementAndGet);
        cache.panel("blocked-leather", panelLoads::incrementAndGet);
    }

    private void call(String method, String uri, int status) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(status);
        invalidator.afterCompletion(request, response, null, null);
    }

    @Test
    void lecturasRepetidasNoRecalculan() {
        readBoth();
        readBoth();
        call("GET", "/api/tasks", 200);
        readBoth();

        assertThat(ordersLoads).hasValue(1);
        assertThat(panelLoads).hasValue(1);
    }

    @Test
    void escribirTareasSueltaLosPanelesPeroNoLasOrdenes() {
        readBoth();
        call("PUT", "/api/tasks/5/die-cut", 200);
        readBoth();

        assertThat(ordersLoads).hasValue(1);
        assertThat(panelLoads).hasValue(2);
    }

    @Test
    void planificarYEscribirOrdenesSueltanTodo() {
        readBoth();
        call("POST", "/api/tasks/auto-plan", 200);
        readBoth();
        call("PUT", "/api/production-orders/7", 200);
        readBoth();

        assertThat(ordersLoads).hasValue(3);
        assertThat(panelLoads).hasValue(3);
    }

    @Test
    void unaEscrituraFallidaNoSueltaNada() {
        readBoth();
        call("PUT", "/api/production-orders/7", 400);
        readBoth();

        assertThat(ordersLoads).hasValue(1);
        assertThat(panelLoads).hasValue(1);
    }

    @Test
    void elCueroSueltaLosPaneles() {
        readBoth();
        call("POST", "/api/leather/movements", 200);
        readBoth();

        assertThat(panelLoads).hasValue(2);
    }
}
