package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoNotificationRecipientEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoPhysicalCountEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoPhysicalCountStatus;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoNotificationRecipientRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoPhysicalCountRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Alerta por email a contabilidad/logistica cuando un conteo fisico de kiosco lleva mas de 2 dias
 * revisado sin cerrarse y aun tiene diferencias >= {@link KioscoInventoryCountService#DIFF_ALERT_THRESHOLD}
 * unidades. Cada sesion se notifica una unica vez (diff_notified_at) para no saturar de correos.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class KioscoInventoryDiffNotificationJob {

    private static final int GRACE_PERIOD_DAYS = 2;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final KioscoPhysicalCountRepository countRepository;
    private final KioscoNotificationRecipientRepository notificationRecipientRepository;
    private final LocationRepository locationRepository;
    private final EmailService emailService;

    @Scheduled(cron = "0 0 8 * * *")
    @Transactional
    public void notifyUnresolvedDifferences() {
        LocalDateTime reviewedBefore = LocalDateTime.now().minusDays(GRACE_PERIOD_DAYS);
        List<KioscoPhysicalCountEntity> pending = countRepository
                .findByStatusAndMaxAbsDiffGreaterThanEqualAndDiffNotifiedAtIsNullAndReviewedAtLessThanEqual(
                        KioscoPhysicalCountStatus.REVISADO,
                        KioscoInventoryCountService.DIFF_ALERT_THRESHOLD,
                        reviewedBefore);
        if (pending.isEmpty()) {
            return;
        }

        List<KioscoNotificationRecipientEntity> recipients = notificationRecipientRepository.findByActiveTrue();
        if (recipients.isEmpty()) {
            log.warn("Hay {} conteo(s) con diferencias sin resolver pero no hay destinatarios de notificacion activos.",
                    pending.size());
            return;
        }
        String toAddresses = recipients.stream()
                .map(KioscoNotificationRecipientEntity::getEmail)
                .collect(Collectors.joining(","));

        for (KioscoPhysicalCountEntity count : pending) {
            try {
                sendAlert(count, toAddresses);
                count.setDiffNotifiedAt(LocalDateTime.now());
                countRepository.save(count);
            } catch (Exception e) {
                log.error("No se pudo enviar la alerta de diferencias del conteo #{}: {}", count.getId(), e.getMessage(), e);
            }
        }
    }

    private void sendAlert(KioscoPhysicalCountEntity count, String toAddresses) {
        LocationEntity location = locationRepository.findById(count.getLocationId()).orElse(null);
        String kioskLabel = location != null
                ? String.format("%s (%s)", location.getName(), location.getCode())
                : "Kiosko #" + count.getLocationId();

        String subject = String.format("Diferencia de inventario sin resolver — %s", kioskLabel);
        String body = String.format(
                "<p>El conteo físico del kiosko <strong>%s</strong> para el período "
                        + "<strong>%s — %s</strong> fue revisado el <strong>%s</strong> y aún presenta una "
                        + "diferencia máxima de <strong>%d unidades</strong> entre el sistema y el conteo físico.</p>"
                        + "<p>Han pasado más de %d días sin que se cierre el conteo ni se registren los ajustes "
                        + "correspondientes. Por favor da seguimiento con la supervisora del kiosko.</p>",
                kioskLabel,
                count.getPeriodFrom().format(DATE_FMT),
                count.getPeriodTo().format(DATE_FMT),
                count.getReviewedAt() != null ? count.getReviewedAt().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")) : "-",
                count.getMaxAbsDiff(),
                GRACE_PERIOD_DAYS
        );
        emailService.sendSimpleEmail(toAddresses, subject, body);
    }
}
