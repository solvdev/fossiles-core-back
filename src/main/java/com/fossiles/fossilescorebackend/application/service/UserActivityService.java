package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.ConnectedUserResponse;
import com.fossiles.fossilescorebackend.application.dto.response.UserActivityLogResponse;
import com.fossiles.fossilescorebackend.domain.model.Role;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserActivityLogEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserActivityLogRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserActivityService {

    private final UserRepository userRepository;
    private final UserActivityLogRepository userActivityLogRepository;

    public static final java.time.ZoneId ZONE_GUATEMALA = java.time.ZoneId.of("America/Guatemala");
    private static final int DEFAULT_ONLINE_WINDOW_MINUTES = 5;

    /**
     * Registra de forma asíncrona la actividad del usuario y actualiza su last_activity_at
     */
    @Async
    @Transactional
    public void recordActivity(String username, String httpMethod, String requestPath, String ipAddress, String userAgent) {
        if (username == null || username.isBlank() || "anonymousUser".equalsIgnoreCase(username)) {
            return;
        }

        try {
            Optional<UserEntity> userOpt = userRepository.findByUsername(username);
            if (userOpt.isEmpty()) {
                return;
            }

            UserEntity user = userOpt.get();
            LocalDateTime now = LocalDateTime.now(ZONE_GUATEMALA);
            user.setLastActivityAt(now);
            userRepository.save(user);

            // Obtener tipo y descripción amigable
            ActionInfo actionInfo = resolveActionInfo(httpMethod, requestPath);

            // Truncar userAgent e ipAddress si exceden longitud
            String safeIp = ipAddress != null && ipAddress.length() > 50 ? ipAddress.substring(0, 50) : ipAddress;
            String safeUserAgent = userAgent != null && userAgent.length() > 255 ? userAgent.substring(0, 255) : userAgent;
            String safePath = requestPath != null && requestPath.length() > 255 ? requestPath.substring(0, 255) : requestPath;

            UserActivityLogEntity logEntity = UserActivityLogEntity.builder()
                    .user(user)
                    .actionType(actionInfo.actionType)
                    .description(actionInfo.description)
                    .httpMethod(httpMethod)
                    .requestPath(safePath)
                    .ipAddress(safeIp)
                    .userAgent(safeUserAgent)
                    .createdAt(now)
                    .build();

            userActivityLogRepository.save(logEntity);
        } catch (Exception e) {
            log.warn("Error al registrar actividad de usuario {}: {}", username, e.getMessage());
        }
    }

    /**
     * Obtiene la lista de usuarios con su estado de conexión y última actividad
     */
    @Transactional(readOnly = true)
    public List<ConnectedUserResponse> getConnectedUsers(Integer windowMinutes) {
        int window = (windowMinutes != null && windowMinutes > 0) ? windowMinutes : DEFAULT_ONLINE_WINDOW_MINUTES;
        LocalDateTime now = LocalDateTime.now(ZONE_GUATEMALA);
        LocalDateTime threshold = now.minusMinutes(window);

        List<UserEntity> allUsers = userRepository.findAll();

        return allUsers.stream()
                .map(user -> {
                    LocalDateTime lastAct = user.getLastActivityAt();
                    boolean isOnline = lastAct != null && (lastAct.isAfter(threshold) || lastAct.isEqual(threshold));
                    Long minutesSince = null;
                    if (lastAct != null) {
                        long diff = Duration.between(lastAct, now).toMinutes();
                        minutesSince = Math.max(0L, diff);
                    }

                    // Obtener la última acción registrada
                    UserActivityLogResponse lastAction = userActivityLogRepository.findLatestByUserId(user.getId())
                            .map(this::toActivityLogResponse)
                            .orElse(null);

                    Set<String> roleNames = user.getRoles() != null
                            ? user.getRoles().stream().map(r -> r.getName()).collect(Collectors.toSet())
                            : Collections.emptySet();

                    String departmentName = user.getDepartment() != null ? user.getDepartment().getName() : null;

                    return ConnectedUserResponse.builder()
                            .id(user.getId())
                            .username(user.getUsername())
                            .firstName(user.getFirstName())
                            .lastName(user.getLastName())
                            .email(user.getEmail())
                            .profileImageUrl(user.getProfileImageUrl())
                            .status(user.getStatus())
                            .roles(roleNames)
                            .departmentName(departmentName)
                            .lastActivityAt(lastAct)
                            .isOnline(isOnline)
                            .minutesSinceLastActivity(minutesSince)
                            .lastAction(lastAction)
                            .build();
                })
                .sorted((a, b) -> {
                    // Ordenar: primero conectados, luego por última actividad más reciente
                    if (a.isOnline() != b.isOnline()) {
                        return a.isOnline() ? -1 : 1;
                    }
                    if (a.getLastActivityAt() == null && b.getLastActivityAt() == null) return 0;
                    if (a.getLastActivityAt() == null) return 1;
                    if (b.getLastActivityAt() == null) return -1;
                    return b.getLastActivityAt().compareTo(a.getLastActivityAt());
                })
                .collect(Collectors.toList());
    }

    /**
     * Obtiene las 10 últimas acciones de un usuario específico
     */
    @Transactional(readOnly = true)
    public List<UserActivityLogResponse> getRecentActions(Long userId) {
        List<UserActivityLogEntity> logs = userActivityLogRepository.findTop10ByUserIdOrderByCreatedAtDesc(userId);
        return logs.stream().map(this::toActivityLogResponse).collect(Collectors.toList());
    }

    private UserActivityLogResponse toActivityLogResponse(UserActivityLogEntity entity) {
        if (entity == null) return null;
        return UserActivityLogResponse.builder()
                .id(entity.getId())
                .userId(entity.getUser() != null ? entity.getUser().getId() : null)
                .username(entity.getUser() != null ? entity.getUser().getUsername() : null)
                .actionType(entity.getActionType())
                .description(entity.getDescription())
                .httpMethod(entity.getHttpMethod())
                .requestPath(entity.getRequestPath())
                .ipAddress(entity.getIpAddress())
                .userAgent(entity.getUserAgent())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    private static class ActionInfo {
        final String actionType;
        final String description;

        ActionInfo(String actionType, String description) {
            this.actionType = actionType;
            this.description = description;
        }
    }

    private ActionInfo resolveActionInfo(String method, String path) {
        if (path == null) path = "";
        String m = method != null ? method.toUpperCase(Locale.ROOT) : "GET";

        if (path.contains("/api/kiosk-pos/sales")) {
            if ("POST".equals(m)) return new ActionInfo("VENTA_POS", "Registro de venta en POS");
            return new ActionInfo("VENTA_POS", "Consulta de ventas POS");
        }
        if (path.contains("/api/kiosk-pos/void-sale")) {
            return new ActionInfo("VENTA_POS", "Anulación de venta POS");
        }
        if (path.contains("/api/kiosco-cambios/autorizaciones")) {
            if ("POST".equals(m) || "PUT".equals(m)) return new ActionInfo("CAMBIOS", "Autorización / Rechazo de cambio");
            return new ActionInfo("CAMBIOS", "Consulta de cambios pendientes");
        }
        if (path.contains("/api/kiosco-cambios")) {
            if ("POST".equals(m)) return new ActionInfo("CAMBIOS", "Creación de boleta de cambio");
            return new ActionInfo("CAMBIOS", "Gestión de boletas de cambio");
        }
        if (path.contains("/api/tax-invoices/certify")) {
            return new ActionInfo("FACTURACION_FEL", "Certificación de Factura FEL");
        }
        if (path.contains("/api/tax-invoices")) {
            if ("POST".equals(m)) return new ActionInfo("FACTURACION_FEL", "Emisión de Factura FEL");
            return new ActionInfo("FACTURACION_FEL", "Gestión de Facturación FEL");
        }
        if (path.contains("/api/kiosco-inventario/movimiento")) {
            return new ActionInfo("INVENTARIO", "Registro de movimiento de inventario");
        }
        if (path.contains("/api/kiosco-inventario/toma-fisica") || path.contains("/api/kiosco-inventario/conteos")) {
            return new ActionInfo("INVENTARIO", "Toma física de inventario en kiosco");
        }
        if (path.contains("/api/kiosco-inventario")) {
            return new ActionInfo("INVENTARIO", "Gestión de inventario de kiosco");
        }
        if (path.contains("/api/production-orders")) {
            if ("POST".equals(m)) return new ActionInfo("PRODUCCION", "Creación de orden de producción");
            return new ActionInfo("PRODUCCION", "Gestión de órdenes de producción");
        }
        if (path.contains("/api/internal-shipment-requests")) {
            if ("POST".equals(m)) return new ActionInfo("DISTRIBUCION", "Creación / Registro de envío interno");
            return new ActionInfo("DISTRIBUCION", "Gestión de envíos internos");
        }
        if (path.contains("/api/product-shipments")) {
            return new ActionInfo("DISTRIBUCION", "Gestión de traslados y recepciones");
        }
        if (path.contains("/api/customers")) {
            if ("POST".equals(m) || "PUT".equals(m)) return new ActionInfo("CLIENTES", "Creación / Edición de cliente");
            return new ActionInfo("CLIENTES", "Gestión de clientes y cuentas");
        }
        if (path.contains("/api/users")) {
            if ("POST".equals(m) || "PUT".equals(m) || "PATCH".equals(m)) return new ActionInfo("SEGURIDAD", "Modificación de usuario");
            return new ActionInfo("SEGURIDAD", "Consulta de usuarios");
        }
        if (path.contains("/api/roles")) {
            return new ActionInfo("SEGURIDAD", "Gestión de roles y permisos");
        }
        if (path.contains("/api/products")) {
            if ("POST".equals(m) || "PUT".equals(m)) return new ActionInfo("CATALOGO", "Gestión de productos / catálogo");
            return new ActionInfo("CATALOGO", "Consulta de productos");
        }
        if (path.contains("/api/materials")) {
            return new ActionInfo("MATERIALES", "Gestión de materiales y entregas");
        }
        if (path.contains("/api/auth/login")) {
            return new ActionInfo("SESION", "Inicio de sesión en el sistema");
        }
        if (path.contains("/api/auth/logout")) {
            return new ActionInfo("SESION", "Cierre de sesión");
        }

        // Genérico según método
        switch (m) {
            case "POST":
                return new ActionInfo("CREACION", "Creación en " + path);
            case "PUT":
            case "PATCH":
                return new ActionInfo("ACTUALIZACION", "Actualización en " + path);
            case "DELETE":
                return new ActionInfo("ELIMINACION", "Eliminación en " + path);
            case "GET":
            default:
                return new ActionInfo("CONSULTA", "Consulta de " + path);
        }
    }
}
