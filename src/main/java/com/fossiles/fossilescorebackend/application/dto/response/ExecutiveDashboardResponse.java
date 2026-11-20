package com.fossiles.fossilescorebackend.application.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Tablero gerencial (Dashboards &gt; Gerencial): un solo payload con lo necesario para decidir sobre producción,
 * órdenes, ventas de kioscos, rentabilidad y finanzas. Solo datos; las alertas y textos los arma el frontend.
 * Montos en quetzales con IVA salvo que el nombre diga otra cosa; piezas sin cinchos por talla.
 */
public record ExecutiveDashboardResponse(
        LocalDate from,
        LocalDate to,
        int days,
        LocalDateTime generatedAt,
        Production production,
        Orders orders,
        KioskSales kioskSales,
        Finance finance,
        Profitability profitability,
        List<LeatherRow> leather,
        Receivables receivables,
        List<AdMonth> ads
) {

    /** Semana (lunes) o mes con su valor; {@code current} = periodo en curso, incompleto. */
    public record Point(LocalDate start, double value, boolean current) {
    }

    public record Production(
            long tasks,
            long pieces,
            double hours,
            long delayedTasks,
            LocalDate startedOn,
            long totalTasks,
            long totalPieces,
            List<Point> weeks,
            /** Promedio de piezas de las últimas 4 semanas completas. */
            double avgWeekPieces,
            /** Horas estándar terminadas por semana (piezas × horas por pieza del producto, 0.1 si no tiene). */
            List<Point> weeksStdHours,
            double avgWeekStdHours,
            List<DeskRow> desks,
            List<ProductTimeRow> productTime,
            /**
             * Órdenes activas con el mismo cálculo que «Avance por orden» del Dashboard de Producción: piezas pedidas,
             * terminadas (incluye lo ya recibido en bodega), en bodega/QA, en mesa, por iniciar, sin tarea; faltan =
             * pedidas − terminadas; horas que faltan = faltan × horas por pieza de cada línea.
             */
            Stages backlog,
            long backlogPending,
            double backlogHoursLeft
    ) {
    }

    public record Stages(long units, long noTask, long pending, long inProgress, long warehouse, long done) {
    }

    /** {@code desk} null = tareas sin mesa; {@code hours} null = la mesa no registra horas (maquila). */
    public record DeskRow(Integer desk, long tasks, long pieces, Double hours) {
    }

    /** Horas = inicio→fin de la tarea; {@code medianHoursPerPiece} = mediana por tarea, menos sensible a tareas abiertas. */
    public record ProductTimeRow(String code, String name, long pieces, double hours, Double medianHoursPerPiece) {
    }

    /** Mismas órdenes que «Avance por orden» (PENDING, IN_PROGRESS, IN_QA). */
    public record Orders(long open, long pending, long inProgress, long inQa, long overdue, long workingNow,
                         double hoursLeft, double lateHoursLeft, List<TypeCount> byType, List<OrderRow> orders) {
    }

    public record TypeCount(String type, long count) {
    }

    /** {@code stages} igual que en Avance por orden; {@code hoursLeft} = (pedidas − terminadas) × horas por pieza. */
    public record OrderRow(Long id, String code, String type, String status, String customer, LocalDate deliveryDate,
                           boolean late, Stages stages, double hoursLeft) {
    }

    /** Ventas con el día cerrado: {@code from}–{@code to} terminan ayer. */
    public record KioskSales(
            LocalDate from,
            LocalDate to,
            double sales,
            double previousSales,
            long tickets,
            double units,
            List<Point> weeks,
            List<KioskRow> kiosks,
            List<TopProduct> topProducts
    ) {
    }

    public record KioskRow(Long locationId, String name, long tickets, double sales, double units, List<ProductQty> top) {
    }

    public record ProductQty(String code, String name, double qty) {
    }

    /** Más vendidos con su existencia: en kioscos, en Bodega PT y cuántos kioscos están en 0 o con 1–2 unidades. */
    public record TopProduct(String code, String name, double qty, double amount, long kioskStock, long warehouseStock,
                             long kiosksWithout, long kiosksLow) {
    }

    /**
     * Finanzas de kioscos en el rango elegido. Hoy nunca cuenta: el rango termina como máximo ayer (día cerrado);
     * {@code cappedToYesterday} = se pidió hasta hoy o después y se recortó. {@code projectionFactor}: × ventas para
     * proyectar el cierre del mes (1 = mes completo; null = el rango no empieza el 1, no se proyecta).
     */
    public record Finance(
            LocalDate from,
            LocalDate to,
            boolean cappedToYesterday,
            int days,
            int year,
            int month,
            int daysInMonth,
            Double projectionFactor,
            List<GoalRow> goals,
            Integer pnlYear,
            Integer pnlMonth,
            List<PnlRow> pnl,
            /** Motivo cuando el P&amp;L oficial no se pudo leer (por ejemplo, sin permiso de Finanzas). */
            String pnlUnavailable,
            double sales,
            double lastYearSameRange,
            double lastYearMonth,
            double lastYearNextMonth
    ) {
    }

    /** {@code monthGoal} = meta del mes del rango; {@code goal} = metas prorrateadas a los días del rango. */
    public record GoalRow(Long locationId, String name, double monthGoal, double goal, double sales) {
    }

    /** P&amp;L oficial del mes cerrado (Finanzas por kiosco): utilidad = diferencia; equilibrio = venta que cubre todo. */
    public record PnlRow(Long siteId, Long locationId, String name, double sales, double fixedCosts, double profit, double breakEven) {
    }

    public record Profitability(
            List<ProducedVsSold> producedVsSold,
            List<MarginRow> margins,
            List<InventoryBand> bands,
            List<SlowRow> slowMovers
    ) {
    }

    /** Producido para kioscos = tareas terminadas de órdenes OPK; para clientes = el resto. */
    public record ProducedVsSold(String code, String name, long producedForKiosks, long producedForClients,
                                 double soldKiosks, double soldOnline, long warehouseStock, long kioskStock) {
    }

    /** Precio de kiosco, costo de receta (materiales y cuero) y horas por pieza estándar y reales (mediana 90 días). */
    public record MarginRow(String code, String name, double price, double materialsCost, double leatherCost,
                            Double standardHours, Double realHours) {
    }

    /** key: UP_TO_90, UP_TO_180, UP_TO_365, OVER_365, NO_SALES (cobertura al ritmo de venta de 90 días). */
    public record InventoryBand(String key, long products, long pieces, double value) {
    }

    public record SlowRow(String code, String name, long kioskStock, long warehouseStock, double sold90, double value,
                          Double coverageDays) {
    }

    /** Pies² de cuero que piden las piezas que faltan de las órdenes abiertas contra lo disponible en bodega. */
    public record LeatherRow(String material, double needed, double available) {
    }

    public record Receivables(double balance, long customers, double collectedInPeriod, boolean creditDaysConfigured,
                              List<DebtorRow> top) {
    }

    public record DebtorRow(Long customerId, String name, double balance, LocalDate lastPayment) {
    }

    public record AdMonth(int year, int month, double adSpend, double onlineSales) {
    }
}
