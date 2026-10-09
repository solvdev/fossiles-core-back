package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.util.KioskEffectiveGoals;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSiteEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Lee las metas mensuales del modulo "Metas de kioscos" ({@code kiosk_monthly_goal}) con SQL directo, para que
 * Finanzas no dependa de las clases de ese modulo (viven en otra rama). Si la tabla no existe en la base
 * (modulo aun no instalado) devuelve vacio, y Finanzas usa su meta de respaldo.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KioskGoalModuleReader {

    private static final String TABLE = "kiosk_monthly_goal";

    private final JdbcTemplate jdbc;

    /** Metas efectivas de un anio para los sitios dados. */
    public KioskEffectiveGoals forYear(int year, Collection<KioskSiteEntity> sites) {
        Map<Long, Long> locationBySite = new HashMap<>();
        if (sites != null) {
            for (KioskSiteEntity s : sites) {
                if (s != null && s.getId() != null && s.getLocationId() != null) {
                    locationBySite.put(s.getId(), s.getLocationId());
                }
            }
        }
        return new KioskEffectiveGoals(locationBySite, goalsByLocation(year));
    }

    Map<Long, Map<Integer, BigDecimal>> goalsByLocation(int year) {
        Map<Long, Map<Integer, BigDecimal>> result = new HashMap<>();
        if (!tableExists()) {
            log.warn("Tabla {} no existe: Finanzas usa solo sus metas de respaldo.", TABLE);
            return result;
        }
        jdbc.query("SELECT kiosk_location_id, goal_month, goal_amount FROM " + TABLE + " WHERE goal_year = ?",
                rs -> {
                    result.computeIfAbsent(rs.getLong("kiosk_location_id"), k -> new HashMap<>())
                            .put(rs.getInt("goal_month"), rs.getBigDecimal("goal_amount"));
                }, year);
        return result;
    }

    /** Se consulta antes para no ejecutar SQL invalido: en PostgreSQL abortaria la transaccion en curso. */
    private boolean tableExists() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE LOWER(table_name) = ?",
                Integer.class, TABLE);
        return n != null && n > 0;
    }
}
