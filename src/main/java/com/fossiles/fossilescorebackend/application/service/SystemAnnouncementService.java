package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.SystemAnnouncementRequest;
import com.fossiles.fossilescorebackend.application.dto.response.SystemAnnouncementResponse;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.SystemAnnouncementEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.SystemAnnouncementRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Service
@RequiredArgsConstructor
public class SystemAnnouncementService {

    private final SystemAnnouncementRepository announcementRepository;
    private final UserRepository userRepository;

    public static final java.time.ZoneId ZONE_GUATEMALA = java.time.ZoneId.of("America/Guatemala");
    private static final Long SSE_TIMEOUT_MS = 30 * 60 * 1000L; // 30 minutos
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    /**
     * Suscribe un cliente para recibir anuncios en tiempo real vía SSE
     */
    public SseEmitter subscribe(String username) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        emitter.onCompletion(() -> {
            emitters.remove(emitter);
            log.debug("SSE completado para usuario {}", username);
        });
        emitter.onTimeout(() -> {
            emitters.remove(emitter);
            emitter.complete();
            log.debug("SSE timeout para usuario {}", username);
        });
        emitter.onError((e) -> {
            emitters.remove(emitter);
            log.debug("SSE error para usuario {}: {}", username, e.getMessage());
        });

        emitters.add(emitter);

        try {
            // Enviar saludo inicial
            emitter.send(SseEmitter.event()
                    .name("INIT")
                    .data(Map.of("status", "CONNECTED", "timestamp", LocalDateTime.now(ZONE_GUATEMALA).toString())));

            // Si hay un anuncio activo actualmente, enviarlo de inmediato al cliente recién conectado
            getActiveAnnouncement().ifPresent(active -> {
                try {
                    emitter.send(SseEmitter.event()
                            .name("ANNOUNCEMENT")
                            .data(active));
                } catch (IOException e) {
                    emitters.remove(emitter);
                }
            });
        } catch (IOException e) {
            emitters.remove(emitter);
        }

        return emitter;
    }

    /**
     * Emite un nuevo anuncio / alerta a todos los clientes conectados
     */
    @Transactional
    public SystemAnnouncementResponse broadcastAnnouncement(SystemAnnouncementRequest request, String username) {
        LocalDateTime now = LocalDateTime.now(ZONE_GUATEMALA);
        UserEntity creator = (username != null) ? userRepository.findByUsername(username).orElse(null) : null;

        // Desactivar anuncios anteriores
        announcementRepository.deactivateAllActive(now, creator != null ? creator.getId() : null);

        int durationSeconds = 300;
        if (request.getDurationSeconds() != null && request.getDurationSeconds() > 0) {
            durationSeconds = request.getDurationSeconds();
        } else if (request.getDurationMinutes() != null && request.getDurationMinutes() > 0) {
            durationSeconds = request.getDurationMinutes() * 60;
        }

        LocalDateTime expiresAt = now.plusSeconds(durationSeconds);

        SystemAnnouncementEntity entity = SystemAnnouncementEntity.builder()
                .title(request.getTitle())
                .message(request.getMessage())
                .announcementType(request.getAnnouncementType() != null ? request.getAnnouncementType() : "RESTART_WARNING")
                .targetAction(request.getTargetAction() != null ? request.getTargetAction() : "RESTART")
                .durationSeconds(durationSeconds)
                .createdByUser(creator)
                .createdAt(now)
                .expiresAt(expiresAt)
                .isActive(true)
                .build();

        SystemAnnouncementEntity saved = announcementRepository.save(entity);
        SystemAnnouncementResponse response = toResponse(saved, now);

        // Enviar SSE a todos los conectados
        sendToAllEmitters("ANNOUNCEMENT", response);

        log.info("Alerta de sistema emitida por '{}': {} (expira en {} segs)", username, request.getTitle(), durationSeconds);
        return response;
    }

    /**
     * Cancela la alerta activa actual y avisa a los clientes
     */
    @Transactional
    public void dismissActiveAnnouncement(String username) {
        LocalDateTime now = LocalDateTime.now(ZONE_GUATEMALA);
        UserEntity user = (username != null) ? userRepository.findByUsername(username).orElse(null) : null;
        Long userId = user != null ? user.getId() : null;

        int updated = announcementRepository.deactivateAllActive(now, userId);
        if (updated > 0) {
            log.info("Alerta de sistema cancelada por '{}'", username);
        }

        // Notificar a todos los clientes para que cierren el banner/modal
        sendToAllEmitters("DISMISS", Map.of("isActive", false, "dismissedAt", now.toString()));
    }

    /**
     * Obtiene el anuncio activo actual (si aún no expira)
     */
    @Transactional(readOnly = true)
    public Optional<SystemAnnouncementResponse> getActiveAnnouncement() {
        LocalDateTime now = LocalDateTime.now(ZONE_GUATEMALA);
        return announcementRepository.findFirstByIsActiveTrueAndExpiresAtAfterOrderByCreatedAtDesc(now)
                .map(entity -> toResponse(entity, now));
    }

    /**
     * Heartbeat periódico cada 25 segundos para mantener vivas las conexiones SSE
     */
    @Scheduled(fixedRate = 25000)
    public void sendHeartbeat() {
        if (emitters.isEmpty()) return;
        sendToAllEmitters("PING", Map.of("ping", System.currentTimeMillis()));
    }

    private void sendToAllEmitters(String eventName, Object data) {
        List<SseEmitter> deadEmitters = new CopyOnWriteArrayList<>();

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name(eventName)
                        .data(data));
            } catch (Exception e) {
                deadEmitters.add(emitter);
            }
        }

        if (!deadEmitters.isEmpty()) {
            emitters.removeAll(deadEmitters);
        }
    }

    private SystemAnnouncementResponse toResponse(SystemAnnouncementEntity entity, LocalDateTime now) {
        if (entity == null) return null;

        Long remainingSeconds = null;
        if (entity.getExpiresAt() != null && entity.getExpiresAt().isAfter(now)) {
            remainingSeconds = Duration.between(now, entity.getExpiresAt()).getSeconds();
        } else {
            remainingSeconds = 0L;
        }

        String createdByUsername = null;
        String createdByName = null;
        if (entity.getCreatedByUser() != null) {
            createdByUsername = entity.getCreatedByUser().getUsername();
            String f = entity.getCreatedByUser().getFirstName();
            String l = entity.getCreatedByUser().getLastName();
            if (f != null || l != null) {
                createdByName = String.format("%s %s", f != null ? f : "", l != null ? l : "").trim();
            } else {
                createdByName = createdByUsername;
            }
        }

        return SystemAnnouncementResponse.builder()
                .id(entity.getId())
                .title(entity.getTitle())
                .message(entity.getMessage())
                .announcementType(entity.getAnnouncementType())
                .targetAction(entity.getTargetAction())
                .durationSeconds(entity.getDurationSeconds())
                .remainingSeconds(remainingSeconds)
                .createdAt(entity.getCreatedAt())
                .expiresAt(entity.getExpiresAt())
                .isActive(entity.getIsActive() && remainingSeconds > 0)
                .createdByUsername(createdByUsername)
                .createdByName(createdByName)
                .build();
    }
}
