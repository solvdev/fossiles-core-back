package com.fossiles.fossilescorebackend.infrastructure.config;

import com.fossiles.fossilescorebackend.application.service.OnlineSalesReadCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class OnlineSalesCacheInvalidatorTest {

    private OnlineSalesReadCache cache;
    private OnlineSalesCacheInvalidator invalidator;

    @BeforeEach
    void setUp() {
        org.springframework.cache.support.SimpleCacheManager manager =
                (org.springframework.cache.support.SimpleCacheManager) new CacheConfig().cacheManager();
        manager.afterPropertiesSet();
        cache = new OnlineSalesReadCache(manager);
        invalidator = new OnlineSalesCacheInvalidator(cache);
    }

    @Test
    void lasLecturasNoInvalidan() throws Exception {
        String key = OnlineSalesReadCache.key("by-date", "2026-10-13");
        cache.sales(key, () -> "v1");

        invalidator.afterCompletion(request("GET", "/api/online-sales/by-date"), response(200), null, null);

        assertThat(cache.sales(key, () -> "v2")).isEqualTo("v1");
    }

    @Test
    void unaEscrituraEnVentasEnLineaInvalidaVentasYBodega() throws Exception {
        String sales = OnlineSalesReadCache.key("by-date", "2026-10-13");
        String warehouse = OnlineSalesReadCache.key("warehouse-view", "PENDING");
        String catalog = OnlineSalesReadCache.key("products");
        cache.sales(sales, () -> "v1");
        cache.warehouse(warehouse, () -> "v1");
        cache.catalog(catalog, () -> "v1");

        invalidator.afterCompletion(request("PUT", "/api/online-sales/12/void"), response(200), null, null);

        assertThat(cache.sales(sales, () -> "v2")).isEqualTo("v2");
        assertThat(cache.warehouse(warehouse, () -> "v2")).isEqualTo("v2");
        assertThat(cache.catalog(catalog, () -> "v2")).isEqualTo("v1");
    }

    @Test
    void unaEscrituraDeFacturaFelInvalidaLasVentas() throws Exception {
        String sales = OnlineSalesReadCache.key("by-date", "2026-10-13");
        cache.sales(sales, () -> "v1");

        invalidator.afterCompletion(request("POST", "/api/tax-invoices/online-sale/9/issue"), response(200), null, null);

        assertThat(cache.sales(sales, () -> "v2")).isEqualTo("v2");
    }

    @Test
    void despachoPorQrPublicoInvalidaVentasYBodega() throws Exception {
        String sales = OnlineSalesReadCache.key("by-date", "2026-10-13");
        String warehouse = OnlineSalesReadCache.key("warehouse-view", "PENDING");
        cache.sales(sales, () -> "v1");
        cache.warehouse(warehouse, () -> "v1");

        invalidator.afterCompletion(request("POST", "/api/public/online-warehouse/dispatch"), response(200), null, null);

        assertThat(cache.sales(sales, () -> "v2")).isEqualTo("v2");
        assertThat(cache.warehouse(warehouse, () -> "v2")).isEqualTo("v2");
    }

    @Test
    void unaEscrituraDeProductosInvalidaCatalogoYBodegaPeroNoVentas() throws Exception {
        String sales = OnlineSalesReadCache.key("by-date", "2026-10-13");
        String catalog = OnlineSalesReadCache.key("products");
        String warehouse = OnlineSalesReadCache.key("warehouse-view", "PENDING");
        cache.sales(sales, () -> "v1");
        cache.catalog(catalog, () -> "v1");
        cache.warehouse(warehouse, () -> "v1");

        invalidator.afterCompletion(request("PUT", "/api/products/5"), response(200), null, null);

        assertThat(cache.catalog(catalog, () -> "v2")).isEqualTo("v2");
        assertThat(cache.warehouse(warehouse, () -> "v2")).isEqualTo("v2");
        assertThat(cache.sales(sales, () -> "v2")).isEqualTo("v1");
    }

    @Test
    void unaEscrituraFallidaNoInvalida() throws Exception {
        String sales = OnlineSalesReadCache.key("by-date", "2026-10-13");
        cache.sales(sales, () -> "v1");

        invalidator.afterCompletion(request("PUT", "/api/online-sales/12/void"), response(400), null, null);
        invalidator.afterCompletion(request("PUT", "/api/online-sales/12/void"), response(200), null,
                new RuntimeException("falló"));

        assertThat(cache.sales(sales, () -> "v2")).isEqualTo("v1");
    }

    @Test
    void escriturasDeOtrosModulosNoInvalidan() throws Exception {
        String sales = OnlineSalesReadCache.key("by-date", "2026-10-13");
        cache.sales(sales, () -> "v1");

        invalidator.afterCompletion(request("POST", "/api/kiosk-pos/sales"), response(200), null, null);

        assertThat(cache.sales(sales, () -> "v2")).isEqualTo("v1");
    }

    @Test
    void elPrefijoDeRutaNoConfundeModulosConNombreParecido() {
        assertThat(OnlineSalesCacheInvalidator.matches("/api/products/5", OnlineSalesCacheInvalidator.CATALOG_PATHS))
                .isTrue();
        assertThat(OnlineSalesCacheInvalidator.matches("/api/product-inventory/5",
                OnlineSalesCacheInvalidator.CATALOG_PATHS)).isFalse();
    }

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setContextPath("");
        return request;
    }

    @Test
    void laVistaPreviaDeArmarElDiaNoInvalida() throws Exception {
        String warehouse = OnlineSalesReadCache.key("warehouse-view", "PENDING");
        cache.warehouse(warehouse, () -> "v1");

        invalidator.afterCompletion(request("POST", "/api/tasks/organizer/day-plan/preview"), response(200), null, null);

        assertThat(cache.warehouse(warehouse, () -> "v2")).isEqualTo("v1");
    }

    @Test
    void confirmarArmarElDiaInvalidaBodega() throws Exception {
        String warehouse = OnlineSalesReadCache.key("warehouse-view", "PENDING");
        cache.warehouse(warehouse, () -> "v1");

        invalidator.afterCompletion(request("POST", "/api/tasks/organizer/day-plan/confirm"), response(200), null, null);

        assertThat(cache.warehouse(warehouse, () -> "v2")).isEqualTo("v2");
    }

    private static MockHttpServletResponse response(int status) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(status);
        return response;
    }
}
