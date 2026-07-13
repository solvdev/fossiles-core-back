package com.fossiles.fossilescorebackend.infrastructure.util;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * Fuente única de las reglas de capacidad del centro de producción
 * (antes duplicadas en TaskController, ProductionTaskGenerationService y TaskDeskBackfillService).
 */
public final class ProductionPlanningConstants {

    /** Cupo de horas base por mesa y día (guía/objetivo — algoritmos automáticos de generación clásica). */
    public static final double MAX_HOURS_PER_DESK_PER_DAY = 4.0;

    /**
     * Tope duro al crear una tarea manual en el Organizador: 4h es lo ideal, pero se permite
     * hasta este límite para que el usuario decida cuánto mandar según la carga real del día.
     * Por encima de esto sí se bloquea la creación.
     */
    public static final double MAX_HOURS_PER_TASK_HARD_CAP = 5.0;

    /** Horas por unidad cuando el producto no tiene prd_time configurado. */
    public static final double DEFAULT_PRD_TIME_PER_UNIT = 0.1;

    /** Número de mesas por defecto si no hay configuración. */
    public static final int MAX_DESKS = 12;

    public static final List<String> DESKS_COUNT_CONFIG_KEYS = List.of(
            "MANUFACTURING_NUMBER_OF_TABLES",
            "PRODUCTION_TABLES_COUNT"
    );

    private ProductionPlanningConstants() {}

    /** Órdenes que pueden exceder el cupo de la mesa en su día ancla (extra sobre las 4h). */
    public static boolean canOvercapDeskDay(String orderType) {
        String normalizedType = String.valueOf(orderType == null ? "" : orderType).trim().toUpperCase(Locale.ROOT);
        return "VENTA_EN_LINEA".equals(normalizedType) || "CLIENTE_KIOSKO".equals(normalizedType);
    }

    /** Solo se trabaja de lunes a viernes: ninguna tarea debe programarse sábado/domingo. */
    public static boolean isWorkday(LocalDate date) {
        if (date == null) {
            return true; // sin fecha = "sin asignar", válido
        }
        DayOfWeek dow = date.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
    }
}
