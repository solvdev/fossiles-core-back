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

    /**
     * Corte KPI post-saneamiento: la eficiencia del dashboard y la estimación de OP
     * solo usan tareas medidas desde esta fecha (datos previos se cerraron a 100%).
     */
    public static final LocalDate KPI_EFFICIENCY_FROM = LocalDate.of(2026, 9, 14);

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

    /**
     * Venta en línea (OPL): por tipo {@code VENTA_EN_LINEA} o código {@code OPL-*}.
     * Estas líneas nunca consumen cupo de mesa/día.
     */
    public static boolean isOnlineSaleOrder(String orderType, String code) {
        String normalizedType = String.valueOf(orderType == null ? "" : orderType).trim().toUpperCase(Locale.ROOT);
        if ("VENTA_EN_LINEA".equals(normalizedType)) {
            return true;
        }
        String normalizedCode = String.valueOf(code == null ? "" : code).trim().toUpperCase(Locale.ROOT);
        return normalizedCode.startsWith("OPL-") || "OPL".equals(normalizedCode);
    }

    /**
     * Horas que cuentan contra el cupo de mesa/día (4h ideal / 5h tope).
     * Tareas OPL enteras → 0. Ítems {@code daySaleExtra} tampoco cuentan.
     */
    public static double deskCupoBaseHours(Double estimatedHours, String productionOrderCode, double daySaleExtraHours) {
        if (isOnlineSaleOrder(null, productionOrderCode)) {
            return 0.0;
        }
        double total = estimatedHours != null ? estimatedHours : 0.0;
        double extra = Math.max(daySaleExtraHours, 0.0);
        return Math.max(total - extra, 0.0);
    }

    /**
     * Familia para cola/prioridad de distribución: OPV / OPK / OPI / OPCK / OPL.
     * {@code null} si no aplica al tablero de prioridad.
     *
     * <p><b>No conoce OPD ni los cinchos, y es a propósito.</b> Su único llamador —la cola
     * de distribución en TaskController— trata el {@code null} como «esta orden no entra a
     * la cola» y se la salta. Ampliarlo a las siete familias metería distribución y cinchos
     * en la planificación, que es otra decisión. Para listados y filtros de interfaz está
     * {@link #orderFamilyLabel}, que sí las cubre.
     */
    public static String distributionFamilyLabel(String orderType, String code) {
        String ot = orderType == null ? "" : orderType.trim();
        String c = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if ("VENTA_EN_LINEA".equalsIgnoreCase(ot)) return "OPL";
        if ("NORMAL".equalsIgnoreCase(ot)) return "OPK";
        if ("MARCAS".equalsIgnoreCase(ot) || "OPV".equalsIgnoreCase(ot)) return "OPV";
        if ("INTERNA".equalsIgnoreCase(ot)) return "OPI";
        if ("CLIENTE_KIOSKO".equalsIgnoreCase(ot)) return "OPCK";
        if (c.startsWith("OPL-") || "OPL".equals(c)) return "OPL";
        if (c.startsWith("OPK-")) return "OPK";
        if (c.startsWith("OPV-")) return "OPV";
        if (c.startsWith("OPI-")) return "OPI";
        if (c.startsWith("OPCK-")) return "OPCK";
        return null;
    }

    /**
     * Familia comercial de la orden, para listados y filtros de interfaz:
     * OPL | OPK | OPV | OPI | OPCK | OPD | OPC, con el prefijo del código como fallback.
     *
     * <p>Cubre las siete familias, incluidas DISTRIBUTION (OPD) y los tres tipos de cincho
     * (OPC), que {@link #distributionFamilyLabel} deja fuera a propósito.
     *
     * <p><b>No sustituye a {@code distributionFamilyLabel}.</b> Aquel devuelve {@code null}
     * precisamente para las familias que no entran al tablero de prioridad, y su llamador
     * usa ese {@code null} como filtro para saltarse la orden. Cambiarlo por este metería
     * las órdenes de distribución y los cinchos en la cola de planificación, que es otra
     * decisión y no la de un listado.
     *
     * @return la familia, o {@code null} si no hay tipo ni código de los que deducirla.
     */
    public static String orderFamilyLabel(String orderType, String code) {
        String ot = orderType == null ? "" : orderType.trim().toUpperCase(Locale.ROOT);
        switch (ot) {
            case "VENTA_EN_LINEA": return "OPL";
            case "NORMAL": return "OPK";
            case "MARCAS", "OPV": return "OPV";
            case "INTERNA": return "OPI";
            case "CLIENTE_KIOSKO": return "OPCK";
            case "DISTRIBUTION": return "OPD";
            case "CINCHOS", "CINCHOS_FOSSILES", "CINCHOS_MARCAS": return "OPC";
            default:
                String c = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
                int dash = c.indexOf('-');
                return dash > 0 ? c.substring(0, dash) : (c.isEmpty() ? null : c);
        }
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
