package com.fossiles.fossilescorebackend.infrastructure.config;

import com.fossiles.fossilescorebackend.application.service.OnlineSalesReadCache;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;
import java.util.Set;

/**
 * Invalida las cachés de la vista de Ventas en línea después de cada escritura exitosa en los módulos que las
 * alimentan. Va como interceptor porque las ventas en línea no las escribe solo {@code OnlineSaleController}:
 * también facturación FEL, órdenes de producción, despacho (incluida la ruta pública por QR) y distribuciones.
 *
 * <p>Corre en {@code afterCompletion}: para entonces la transacción del endpoint ya confirmó.
 */
@Component
@RequiredArgsConstructor
public class OnlineSalesCacheInvalidator implements HandlerInterceptor {

    private static final String DAY_PLAN_PREVIEW = "/api/tasks/organizer/day-plan/preview";

    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    /** Rutas que pueden cambiar una venta en línea, sus productos, su factura o sus devoluciones. */
    static final List<String> SALES_PATHS = List.of(
            "/api/online-sales", "/api/tax-invoices", "/api/production-orders", "/api/public/online-warehouse",
            "/api/partial-releases", "/api/product-distributions", "/api/reconciliation/guides");

    /** Rutas que cambian lo que muestra la vista de bodega (órdenes, tareas, envíos, ventas, catálogos). */
    static final List<String> WAREHOUSE_PATHS = List.of(
            "/api/production-orders", "/api/tasks", "/api/online-sales", "/api/public/online-warehouse",
            "/api/partial-releases", "/api/product-distributions", "/api/distribuciones",
            "/api/internal-shipment-requests", "/api/products", "/api/colors", "/api/product-categories",
            "/api/reconciliation/guides");

    /** Rutas que cambian productos o colores. */
    static final List<String> CATALOG_PATHS = List.of(
            "/api/products", "/api/colors", "/api/product-distributions");

    private final OnlineSalesReadCache cache;

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        if (READ_METHODS.contains(request.getMethod()) || ex != null || response.getStatus() >= 400) {
            return;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (DAY_PLAN_PREVIEW.equals(path)) {
            // «Armar el día», vista previa: POST de solo lectura que el front repite en cada cambio.
            return;
        }
        if (matches(path, SALES_PATHS)) {
            cache.evictSales();
        }
        if (matches(path, WAREHOUSE_PATHS)) {
            cache.evictWarehouse();
        }
        if (matches(path, CATALOG_PATHS)) {
            cache.evictCatalog();
        }
    }

    static boolean matches(String path, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }
}
