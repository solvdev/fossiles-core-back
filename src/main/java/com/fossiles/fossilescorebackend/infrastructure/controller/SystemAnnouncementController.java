package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.SystemAnnouncementRequest;
import com.fossiles.fossilescorebackend.application.dto.response.SystemAnnouncementResponse;
import com.fossiles.fossilescorebackend.application.service.SystemAnnouncementService;
import com.fossiles.fossilescorebackend.application.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/system-announcements")
@RequiredArgsConstructor
public class SystemAnnouncementController {

    private final SystemAnnouncementService announcementService;
    private final UserService userService;

    @org.springframework.beans.factory.annotation.Value("${DEPLOY_HOOK_TOKEN:}")
    private String deployHookToken;

    /**
     * Canal SSE para suscripción de notificaciones y alertas en tiempo real
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamAnnouncements(jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("Connection", "keep-alive");

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = (auth != null && auth.isAuthenticated()) ? auth.getName() : "anonymous";
        return announcementService.subscribe(username);
    }

    /**
     * Consulta REST del anuncio activo actual (útil en recargas o consultas iniciales)
     */
    @GetMapping("/active")
    public ResponseEntity<SystemAnnouncementResponse> getActiveAnnouncement() {
        return announcementService.getActiveAnnouncement()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * Emisión de alerta global / reinicio (Solo ADMIN / RRHH)
     */
    @PostMapping("/broadcast")
    public ResponseEntity<SystemAnnouncementResponse> broadcast(
            @Valid @RequestBody SystemAnnouncementRequest request) {
        String username = getAuthenticatedUsernameAndAuthorize();
        SystemAnnouncementResponse response = announcementService.broadcastAnnouncement(request, username);
        return ResponseEntity.ok(response);
    }

    /**
     * Cancelación de la alerta activa actual (Solo ADMIN / RRHH)
     */
    @PostMapping("/dismiss")
    public ResponseEntity<Map<String, Object>> dismiss() {
        String username = getAuthenticatedUsernameAndAuthorize();
        announcementService.dismissActiveAnnouncement(username);
        return ResponseEntity.ok(Map.of("success", true, "message", "Alerta cancelada"));
    }

    /**
     * Confirma que el back terminó de arrancar. El front lo consulta mientras muestra "Reiniciando…"
     * y el deploy lo usa para verificar el servicio (bootId cambia en cada arranque).
     */
    @GetMapping("/ping")
    public ResponseEntity<Map<String, Object>> ping() {
        return ResponseEntity.ok(Map.of(
                "bootId", announcementService.getBootId(),
                "serverNowEpochMs", System.currentTimeMillis()));
    }

    /**
     * Gancho del deploy (GitHub Actions, vía SSH a localhost). Protegido con el header X-Deploy-Token,
     * que debe ser igual a la variable de entorno DEPLOY_HOOK_TOKEN del servicio.
     * phase: ANNOUNCE (crea o reutiliza el aviso), STATUS (aviso activo o 204), RESTARTING (avisa que se detiene).
     */
    @PostMapping("/deploy-hook")
    public ResponseEntity<?> deployHook(
            @RequestHeader(value = "X-Deploy-Token", required = false) String token,
            @RequestBody(required = false) Map<String, Object> body) {
        if (deployHookToken == null || deployHookToken.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        if (token == null || !java.security.MessageDigest.isEqual(
                deployHookToken.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                token.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        String phase = body != null && body.get("phase") != null ? body.get("phase").toString().toUpperCase() : "STATUS";
        switch (phase) {
            case "ANNOUNCE": {
                Integer minutes = null;
                if (body.get("minutes") != null) {
                    try {
                        minutes = Integer.valueOf(body.get("minutes").toString().trim());
                    } catch (NumberFormatException ignored) {
                        minutes = null;
                    }
                }
                return announcementService.announceDeployRestart(minutes)
                        .<ResponseEntity<?>>map(ResponseEntity::ok)
                        .orElseGet(() -> ResponseEntity.noContent().build());
            }
            case "RESTARTING":
                announcementService.notifyRestarting();
                return ResponseEntity.ok(Map.of("success", true, "bootId", announcementService.getBootId()));
            case "STATUS":
                return announcementService.getActiveRestartAnnouncement()
                        .<ResponseEntity<?>>map(ResponseEntity::ok)
                        .orElseGet(() -> ResponseEntity.noContent().build());
            default:
                return ResponseEntity.badRequest().body(Map.of("message", "phase no válida: " + phase));
        }
    }

    private String getAuthenticatedUsernameAndAuthorize() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = (auth != null && auth.isAuthenticated()) ? auth.getName() : null;
        if (!userService.canManageUsers(username)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Solo administradores pueden emitir o cancelar alertas globales");
        }
        return username;
    }
}
