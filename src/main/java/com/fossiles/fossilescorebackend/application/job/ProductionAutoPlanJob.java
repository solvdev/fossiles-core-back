package com.fossiles.fossilescorebackend.application.job;

import com.fossiles.fossilescorebackend.application.service.ProductionAutoPlannerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProductionAutoPlanJob {

    private final ProductionAutoPlannerService productionAutoPlannerService;

    /** Arranque del día laboral GT: parte OPs pendientes y asigna mesas. */
    @Scheduled(cron = "0 5 0 * * *", zone = "America/Guatemala")
    public void runAtStartOfDay() {
        log.info("Auto-plan de producción (inicio del día GT)");
        productionAutoPlannerService.planAllQuietly();
    }

    /** Durante el día: OPL, cuero nuevo y OPs que lleguen después de las 00:05. */
    @Scheduled(cron = "0 */15 5-21 * * *", zone = "America/Guatemala")
    public void runDuringDay() {
        log.debug("Auto-plan de producción (cada 15 min, horario laboral GT)");
        productionAutoPlannerService.planAllQuietly();
    }
}
