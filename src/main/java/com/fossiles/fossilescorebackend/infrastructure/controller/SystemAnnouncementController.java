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

    private String getAuthenticatedUsernameAndAuthorize() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = (auth != null && auth.isAuthenticated()) ? auth.getName() : null;
        if (!userService.canManageUsers(username)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Solo administradores pueden emitir o cancelar alertas globales");
        }
        return username;
    }
}
