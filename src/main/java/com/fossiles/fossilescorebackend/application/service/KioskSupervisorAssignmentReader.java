package com.fossiles.fossilescorebackend.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lee las asignaciones kiosco-supervisora del módulo "Supervisoras y kioscos" ({@code kiosk_supervisor_assignment})
 * con SQL directo, para que Finanzas no dependa de las clases de ese módulo (viven en otra rama). Si la tabla no
 * existe en la base (módulo aún no instalado) devuelve vacío.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KioskSupervisorAssignmentReader {

    private static final String TABLE = "kiosk_supervisor_assignment";

    /** Supervisora y los {@code locations.id} de los kioscos que tiene asignados. */
    public record Supervisor(Long userId, String name, Set<Long> kioskLocationIds) {
    }

    private final JdbcTemplate jdbc;

    /** Supervisoras con al menos un kiosco asignado, ordenadas por nombre. */
    public List<Supervisor> supervisors() {
        if (!tableExists()) {
            log.warn("Tabla {} no existe: no hay filtro por supervisora en Finanzas.", TABLE);
            return List.of();
        }
        Map<Long, String> names = new LinkedHashMap<>();
        Map<Long, Set<Long>> locations = new LinkedHashMap<>();
        jdbc.query("SELECT a.supervisor_user_id, a.kiosk_location_id, u.first_name, u.last_name, u.username "
                + "FROM " + TABLE + " a JOIN users u ON u.id = a.supervisor_user_id "
                + "ORDER BY a.supervisor_user_id, a.kiosk_location_id", rs -> {
            long userId = rs.getLong("supervisor_user_id");
            String full = (nz(rs.getString("first_name")) + " " + nz(rs.getString("last_name"))).trim();
            names.putIfAbsent(userId, full.isEmpty() ? nz(rs.getString("username")) : full);
            locations.computeIfAbsent(userId, k -> new LinkedHashSet<>()).add(rs.getLong("kiosk_location_id"));
        });
        List<Supervisor> result = new ArrayList<>();
        locations.forEach((userId, ids) -> result.add(new Supervisor(userId, names.get(userId), ids)));
        result.sort(Comparator.comparing(Supervisor::name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    /** Se consulta antes para no ejecutar SQL inválido: en PostgreSQL abortaría la transacción en curso. */
    private boolean tableExists() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE LOWER(table_name) = ?", Integer.class, TABLE);
        return n != null && n > 0;
    }
}
