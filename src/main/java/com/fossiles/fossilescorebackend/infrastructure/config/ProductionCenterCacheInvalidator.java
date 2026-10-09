package com.fossiles.fossilescorebackend.infrastructure.config;

import com.fossiles.fossilescorebackend.application.service.ProductionCenterCache;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/**
 * Invalida la caché del Centro de Producción después de cada escritura exitosa en los módulos
 * que la alimentan. Va como interceptor y no en cada endpoint porque TaskController y
 * ProductionOrderController tienen decenas de rutas que escriben, y olvidar una sola dejaría
 * datos viejos en pantalla hasta que venza el TTL.
 *
 * <p>Corre en {@code afterCompletion}: para entonces la transacción del endpoint ya confirmó.
 */
@Component
@RequiredArgsConstructor
public class ProductionCenterCacheInvalidator implements HandlerInterceptor {

    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final ProductionCenterCache productionCenterCache;

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        if (READ_METHODS.contains(request.getMethod()) || ex != null || response.getStatus() >= 400) {
            return;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith("/api/production-orders")) {
            productionCenterCache.evictOrders();
            productionCenterCache.evictPanels();
        } else if (path.startsWith("/api/tasks")) {
            productionCenterCache.evictPanels();
            // Planificar y generar pasan órdenes de PENDING a IN_PROGRESS.
            if (path.startsWith("/api/tasks/auto-plan") || path.startsWith("/api/tasks/generate")) {
                productionCenterCache.evictOrders();
            }
        } else if (path.startsWith("/api/leather") || path.startsWith("/api/product-variant-leathers")) {
            // El cuero disponible decide la cola sin cuero y qué va a producción.
            productionCenterCache.evictPanels();
        }
    }
}
