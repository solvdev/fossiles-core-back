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

    /** Primera prioridad que el usuario puede asignar a mano: 0 y 1 son cupos reservados. */
    public static final int MIN_MANUAL_SCHEDULING_PRIORITY = 2;

    /** Última prioridad manual: 100 es el valor por defecto de las órdenes sin prioridad propia. */
    public static final int MAX_MANUAL_SCHEDULING_PRIORITY = 99;

    /** Número de mesas por defecto si no hay configuración. */
    public static final int MAX_DESKS = 12;

    public static final List<String> DESKS_COUNT_CONFIG_KEYS = List.of(
            "MANUFACTURING_NUMBER_OF_TABLES",
            "PRODUCTION_TABLES_COUNT"
    );

    private ProductionPlanningConstants() {}

    public static boolean isManualSchedulingPriority(Integer priority) {
        return priority != null
                && priority >= MIN_MANUAL_SCHEDULING_PRIORITY
                && priority <= MAX_MANUAL_SCHEDULING_PRIORITY;
    }

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

    /** Prefijo de las observaciones de una tarea de OP consolidada: «Consolidada LF-OPV-012: OPV-64, …». */
    public static final String CONSOLIDATED_PREFIX = "Consolidada ";

    /**
     * OPV de Luis Felipe: vendedor «Luis Felipe» y orden OPV. Son las únicas órdenes que se
     * pueden juntar en una sola tarea («tarea consolidada»), porque suelen ser muy pequeñas.
     *
     * <p>Se mira también el prefijo del código: la mayoría de las OPV de Luis Felipe se guardan
     * con tipo NORMAL y código {@code OPV-…} (en prod, 76 de 83 al 2026-10-21).
     */
    public static boolean isLuisFelipeVendorOrder(String sellerName, String orderType, String code) {
        String seller = sellerName == null ? "" : sellerName.trim().toUpperCase(Locale.ROOT);
        if (!seller.contains("LUIS FELIPE")) {
            return false;
        }
        String c = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        return c.startsWith("OPV-") || "OPV".equals(orderFamilyLabel(orderType, code));
    }

    /** OPC (cinchos) de Luis Felipe: también se pueden juntar, en su propia consolidada. */
    public static boolean isLuisFelipeCinchoOrder(String sellerName, String orderType, String code) {
        String seller = sellerName == null ? "" : sellerName.trim().toUpperCase(Locale.ROOT);
        return seller.contains("LUIS FELIPE") && "OPC".equals(orderFamilyLabel(orderType, code));
    }

    /**
     * OPV y OPC de Luis Felipe: todo lo que se produce ya está vendido, así que se guarda en la reserva
     * (no en stock ni en Devoluciones) hasta que bodega cierra la recepción de la orden.
     */
    public static boolean isLuisFelipeReserveOrder(String sellerName, String orderType, String code) {
        return isLuisFelipeVendorOrder(sellerName, orderType, code)
                || isLuisFelipeCinchoOrder(sellerName, orderType, code);
    }

    /** Solo se trabaja de lunes a viernes: ninguna tarea debe programarse sábado/domingo. */
    public static boolean isWorkday(LocalDate date) {
        if (date == null) {
            return true; // sin fecha = "sin asignar", válido
        }
        DayOfWeek dow = date.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
    }

    /** Día hábil anterior: el del lunes es el viernes. */
    public static LocalDate previousWorkday(LocalDate date) {
        LocalDate d = date.minusDays(1);
        while (!isWorkday(d)) {
            d = d.minusDays(1);
        }
        return d;
    }
}
