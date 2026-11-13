package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import com.fossiles.fossilescorebackend.infrastructure.util.PayrollRules;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Cada mañana deja en borrador la planilla de cada grupo que se paga dentro de {@code payroll.auto-draft.days-ahead}
 * días. Contabilidad la revisa y aprueba; una planilla anulada no se vuelve a crear.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PayrollAutoDraftJob {

    private final PayrollRunService runService;

    @Value("${payroll.auto-draft.enabled:true}")
    private boolean enabled;

    @Value("${payroll.auto-draft.days-ahead:3}")
    private int daysAhead;

    @Scheduled(cron = "0 0 6 * * *", zone = "America/Guatemala")
    public void prepareUpcomingDrafts() {
        if (enabled) {
            prepareDraftsFor(GuatemalaDateTime.today());
        }
    }

    /** Devuelve cuántos borradores creó. */
    int prepareDraftsFor(LocalDate today) {
        int created = 0;
        for (String group : List.of(PayrollRules.GROUP_PLANTA, PayrollRules.GROUP_ADMINISTRACION)) {
            for (PayrollCalendar.PayDay day : PayrollCalendar.upcoming(group, today, 2)) {
                if (day.payDate().isAfter(today.plusDays(daysAhead))) {
                    continue;
                }
                try {
                    var runId = runService.createScheduledDraft(group, day.scheduledDate());
                    if (runId.isPresent()) {
                        created++;
                        log.info("Planilla {} del {} preparada en borrador (#{}).", group, day.scheduledDate(), runId.get());
                    }
                } catch (Exception ex) {
                    log.warn("No se pudo preparar la planilla {} del {}: {}", group, day.scheduledDate(), ex.getMessage());
                }
            }
        }
        return created;
    }
}
