package com.fossiles.fossilescorebackend.infrastructure.config;

import com.fossiles.fossilescorebackend.application.service.LeatherRecipeCatalog;
import com.fossiles.fossilescorebackend.application.service.ProductionCenterCache;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.MaterialRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductVariantLeatherRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * La caché del Centro se sirve mientras nadie escriba, y se suelta con la primera escritura
 * exitosa en órdenes, tareas o cuero.
 */
class ProductionCenterCacheInvalidatorTest {

    private ProductionCenterCache cache;
    private ProductionCenterCacheInvalidator invalidator;
    private final AtomicInteger ordersLoads = new AtomicInteger();
    private final AtomicInteger panelLoads = new AtomicInteger();
    private final AtomicInteger dashboardLoads = new AtomicInteger();
    private LeatherRecipeCatalog recipes;
    private ProductVariantLeatherRepository recipeRepository;

    @BeforeEach
    void setUp() {
        // Fuera de Spring el SimpleCacheManager no se inicializa solo.
        SimpleCacheManager manager = (SimpleCacheManager) new CacheConfig().cacheManager();
        manager.afterPropertiesSet();
        cache = new ProductionCenterCache(manager);
        recipeRepository = mock(ProductVariantLeatherRepository.class);
        recipes = new LeatherRecipeCatalog(recipeRepository, mock(ColorRepository.class),
                mock(MaterialRepository.class), manager);
        invalidator = new ProductionCenterCacheInvalidator(cache, recipes);
    }

    private void readDashboard() {
        cache.dashboard("dashboard-v2|null|hoy", dashboardLoads::incrementAndGet);
        cache.dashboard("leather-demand", dashboardLoads::incrementAndGet);
    }

    /** Una lectura de recetas: carga la foto solo si no está en caché. */
    private void readRecipes() {
        recipes.resolve(9L, 3L);
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

    @Test
    void laVistaPreviaDeArmarElDiaNoSueltaNada() {
        readBoth();
        call("POST", "/api/tasks/organizer/day-plan/preview", 200);
        readBoth();

        assertThat(ordersLoads).hasValue(1);
        assertThat(panelLoads).hasValue(1);
    }

    @Test
    void confirmarArmarElDiaSueltaPanelesYOrdenes() {
        readBoth();
        call("POST", "/api/tasks/organizer/day-plan/confirm", 200);
        readBoth();

        assertThat(ordersLoads).hasValue(2);
        assertThat(panelLoads).hasValue(2);
    }

    @Test
    void confirmarRechazadoConConflictoNoSueltaNada() {
        readBoth();
        call("POST", "/api/tasks/organizer/day-plan/confirm", 409);
        readBoth();

        assertThat(ordersLoads).hasValue(1);
        assertThat(panelLoads).hasValue(1);
    }

    @Test
    void elDashboardSeSirveDeCacheMientrasNadieEscriba() {
        readDashboard();
        readDashboard();
        call("GET", "/api/production-orders/leather-demand", 200);
        call("POST", "/api/tasks/organizer/day-plan/preview", 200);
        readDashboard();

        assertThat(dashboardLoads).hasValue(2);
    }

    @Test
    void escriturasDeOrdenesTareasYCueroSueltanElDashboard() {
        List<String> writes = List.of(
                "/api/production-orders",                 // crear OP
                "/api/production-orders/7",               // editar OP
                "/api/production-orders/7/status",        // cancelar OP
                "/api/tasks/5/status",                    // estado de tarea
                "/api/tasks/5/leather-delivered",         // entregar cuero
                "/api/leather/receive",                   // recibir cuero
                "/api/leather/deliver",                   // entregar cuero de inventario
                "/api/product-variant-leathers/3",        // receta
                "/api/boms/3",                            // receta desde BOM
                "/api/materials/80",                      // material
                "/api/production/cinchos/tasks",          // cinchos
                "/api/online-sales/12");                  // OPV desde venta en línea
        int expected = 2;
        readDashboard();
        for (String path : writes) {
            call("PUT", path, 200);
            readDashboard();
            expected += 2;
            assertThat(dashboardLoads).as(path).hasValue(expected);
        }
    }

    @Test
    void lasRecetasDuranHastaQueSeEditanRecetasMaterialesOColores() {
        readRecipes();
        call("PUT", "/api/tasks/5/status", 200);
        call("POST", "/api/production-orders", 200);
        readRecipes();
        verify(recipeRepository, times(1)).findAll();

        call("PUT", "/api/product-variant-leathers/3", 200);
        readRecipes();
        call("PUT", "/api/materials/80", 200);
        readRecipes();
        call("PUT", "/api/colors/3", 200);
        readRecipes();
        call("POST", "/api/boms", 200);
        readRecipes();
        verify(recipeRepository, times(5)).findAll();
    }

    @Test
    void unaEscrituraFallidaNoSueltaElDashboard() {
        readDashboard();
        call("PUT", "/api/production-orders/7", 500);
        readDashboard();

        assertThat(dashboardLoads).hasValue(2);
    }
}
