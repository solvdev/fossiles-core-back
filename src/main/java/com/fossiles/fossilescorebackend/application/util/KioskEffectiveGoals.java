package com.fossiles.fossilescorebackend.application.util;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskPeriodConfigEntity;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Meta de ventas efectiva por sitio y mes (un anio).
 * <p>
 * Fuente de verdad: el modulo "Metas de kioscos" (tabla {@code kiosk_monthly_goal}, por {@code locations.id}).
 * Solo si ese modulo no tiene meta para el kiosco y mes se usa, como respaldo historico, la meta guardada en
 * {@code kiosk_period_config} (p. ej. las metas 2025 importadas de Excel, o sitios historicos sin location).
 */
public final class KioskEffectiveGoals {

    public static final String SOURCE_GOALS_MODULE = "METAS_KIOSCOS";
    public static final String SOURCE_CONFIG = "CONFIG";

    private final Map<Long, Long> locationBySite;
    private final Map<Long, Map<Integer, BigDecimal>> moduleGoalsByLocation;

    public KioskEffectiveGoals(Map<Long, Long> locationBySite,
                               Map<Long, Map<Integer, BigDecimal>> moduleGoalsByLocation) {
        this.locationBySite = locationBySite == null ? Map.of() : new HashMap<>(locationBySite);
        this.moduleGoalsByLocation = moduleGoalsByLocation == null ? Map.of() : moduleGoalsByLocation;
    }

    public static KioskEffectiveGoals empty() {
        return new KioskEffectiveGoals(Map.of(), Map.of());
    }

    /** true si el sitio es un kiosco real: su meta se administra en el modulo de metas, no en Finanzas. */
    public boolean isManagedByGoalsModule(Long siteId) {
        return siteId != null && locationBySite.get(siteId) != null;
    }

    /** Meta del modulo de metas de kioscos (sin respaldo); null si no hay. */
    public BigDecimal moduleGoal(Long siteId, int month) {
        Long locationId = siteId == null ? null : locationBySite.get(siteId);
        if (locationId == null) {
            return null;
        }
        Map<Integer, BigDecimal> months = moduleGoalsByLocation.get(locationId);
        return months == null ? null : months.get(month);
    }

    /** Meta efectiva: modulo de metas primero; si no hay, la de la configuracion mensual (puede ser null). */
    public BigDecimal goal(Long siteId, int month, KioskPeriodConfigEntity cfg) {
        BigDecimal fromModule = moduleGoal(siteId, month);
        if (fromModule != null) {
            return fromModule;
        }
        return cfg == null ? null : cfg.getSalesGoal();
    }

    /** METAS_KIOSCOS | CONFIG | null (sin meta). */
    public String source(Long siteId, int month, KioskPeriodConfigEntity cfg) {
        if (moduleGoal(siteId, month) != null) {
            return SOURCE_GOALS_MODULE;
        }
        return cfg != null && cfg.getSalesGoal() != null ? SOURCE_CONFIG : null;
    }
}
