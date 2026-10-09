package com.fossiles.fossilescorebackend.application.job;

import com.fossiles.fossilescorebackend.application.service.ProductionAutoPlannerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Cron de auto-plan desactivado: el plan solo corre por botón en Centro
 * ({@code POST /api/tasks/auto-plan?regenerate=true}).
 * Se deja el bean por si se quiere reactivar el schedule más adelante.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductionAutoPlanJob {

    @SuppressWarnings("unused")
    private final ProductionAutoPlannerService productionAutoPlannerService;
}
