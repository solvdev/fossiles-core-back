package com.fossiles.fossilescorebackend.infrastructure.config;

import com.fossiles.fossilescorebackend.application.service.LeatherRecipeCatalog;
import com.fossiles.fossilescorebackend.application.service.ProductionCenterCache;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;
import java.util.Set;

/**
 * Invalida la caché del Centro de Producción después de cada escritura exitosa en los módulos
 * que la alimentan. Va como interceptor y no en cada endpoint porque TaskController y
 * ProductionOrderController tienen decenas de rutas que escriben, y olvidar una sola dejaría
 * datos viejos en pantalla hasta que venza el TTL.
 *
 * <p>También suelta la caché del Dashboard de Producción ({@code /dashboard-v2}, {@code /leather-demand}) y
 * la de recetas de cuero ({@link LeatherRecipeCatalog}).
 *
 * <p>Corre en {@code afterCompletion}: para entonces la transacción del endpoint ya confirmó.
 */
@Component
@RequiredArgsConstructor
public class ProductionCenterCacheInvalidator implements HandlerInterceptor {

    private static final String DAY_PLAN_PREVIEW = "/api/tasks/organizer/day-plan/preview";
    private static final String DAY_PLAN_CONFIRM = "/api/tasks/organizer/day-plan/confirm";

    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    /**
     * Rutas que cambian lo que muestra el Dashboard de Producción: OP y sus líneas (incluidas las que crean
     * ventas en línea, distribuciones, solicitudes internas y cinchos), tareas, cuero entregado o en inventario,
     * recetas, materiales, productos, colores y mesas activas.
     */
    static final List<String> DASHBOARD_PATHS = List.of(
            "/api/production-orders", "/api/tasks", "/api/production/cinchos", "/api/production-order-groups",
            "/api/production/desks-count", "/api/leather", "/api/product-variant-leathers", "/api/boms",
            "/api/materials", "/api/products", "/api/colors", "/api/online-sales", "/api/product-distributions",
            "/api/distribuciones", "/api/internal-shipment-requests", "/api/partial-releases",
            "/api/public/online-warehouse");

    /** Rutas que cambian recetas de cuero o los colores y materiales con que se derivan. */
    static final List<String> LEATHER_RECIPE_PATHS = List.of(
            "/api/product-variant-leathers", "/api/boms", "/api/materials", "/api/colors");

    private final ProductionCenterCache productionCenterCache;
    private final LeatherRecipeCatalog leatherRecipeCatalog;

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        if (READ_METHODS.contains(request.getMethod()) || ex != null || response.getStatus() >= 400) {
            return;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith(DAY_PLAN_PREVIEW)) {
            // La vista previa de «Armar el día» es un POST de solo lectura que el front repite
            // en cada cambio: vaciar cachés ahí las dejaría siempre frías.
            return;
        }
        if (OnlineSalesCacheInvalidator.matches(path, DASHBOARD_PATHS)) {
            productionCenterCache.evictDashboard();
        }
        if (OnlineSalesCacheInvalidator.matches(path, LEATHER_RECIPE_PATHS)) {
            leatherRecipeCatalog.evict();
        }
        if (path.startsWith("/api/production-orders")) {
            productionCenterCache.evictOrders();
            productionCenterCache.evictPanels();
        } else if (path.startsWith("/api/tasks")) {
            productionCenterCache.evictPanels();
            // Planificar y generar pasan órdenes de PENDING a IN_PROGRESS.
            // «Armar el día» (confirmar) también: las OP pasan a IN_PROGRESS.
            if (path.startsWith("/api/tasks/auto-plan") || path.startsWith("/api/tasks/generate")
                    || path.startsWith(DAY_PLAN_CONFIRM)) {
                productionCenterCache.evictOrders();
            }
        } else if (path.startsWith("/api/leather") || path.startsWith("/api/product-variant-leathers")) {
            // El cuero disponible decide la cola sin cuero y qué va a producción.
            productionCenterCache.evictPanels();
        }
    }
}
