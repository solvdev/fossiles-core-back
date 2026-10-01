package com.fossiles.fossilescorebackend.application.util;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Plan de corrección de "días sin sistema" de un kiosco: días del reporte Excel con venta que caen ANTES de su
 * primera venta en el POS (go-live) y que el sistema tiene en Q 0.00. Esos días se leen de
 * {@code kiosk_daily_sales_hist}, así que basta escribir esas celdas ahí.
 * <p>
 * Nada más se corrige: un día con venta en el sistema nunca se pisa, y los huecos desde el go-live en adelante
 * (el sistema ignora el histórico a partir de ese día) sólo se informan.
 */
public final class KioskGapFillPlanner {

    private KioskGapFillPlanner() {
    }

    /** Entrada de un kiosco: ventas del Excel, ventas que hoy resuelve el sistema y su go-live efectivo. */
    public record SiteInput(Long siteId, String siteName, String excelName, LocalDate goLive,
                            Map<LocalDate, BigDecimal> excel, Map<LocalDate, BigDecimal> system) {
    }

    public record Amount(LocalDate date, BigDecimal excelAmount) {
    }

    public record Difference(LocalDate date, BigDecimal excelAmount, BigDecimal systemAmount) {
    }

    public record SitePlan(Long siteId, String siteName, String excelName, LocalDate goLive,
                           /** Se aplican: antes del go-live, sistema en 0 y Excel con venta. */
                           List<Amount> candidates,
                           BigDecimal candidateTotal,
                           /** Mismo día con monto distinto (centavos, fechas corridas...): sólo informativo. */
                           List<Difference> differences,
                           /** Excel con venta y sistema en 0 desde el go-live: no se pueden cubrir con este flujo. */
                           List<Amount> afterGoLiveGaps) {

        public boolean isEmpty() {
            return candidates.isEmpty() && differences.isEmpty() && afterGoLiveGaps.isEmpty();
        }
    }

    public static SitePlan plan(SiteInput in) {
        Map<LocalDate, BigDecimal> excel = in.excel() == null ? Map.of() : in.excel();
        Map<LocalDate, BigDecimal> system = in.system() == null ? Map.of() : in.system();
        List<Amount> candidates = new ArrayList<>();
        List<Difference> differences = new ArrayList<>();
        List<Amount> afterGoLive = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;

        for (Map.Entry<LocalDate, BigDecimal> e : new TreeMap<>(excel).entrySet()) {
            LocalDate date = e.getKey();
            BigDecimal reported = e.getValue();
            if (reported == null || reported.signum() <= 0) {
                continue;
            }
            BigDecimal inSystem = system.getOrDefault(date, BigDecimal.ZERO);
            if (inSystem == null) {
                inSystem = BigDecimal.ZERO;
            }
            boolean beforeGoLive = in.goLive() != null && date.isBefore(in.goLive());
            if (inSystem.signum() == 0) {
                if (beforeGoLive) {
                    candidates.add(new Amount(date, reported));
                    total = total.add(reported);
                } else {
                    afterGoLive.add(new Amount(date, reported));
                }
            } else if (inSystem.compareTo(reported) != 0) {
                differences.add(new Difference(date, reported, inSystem));
            }
        }
        return new SitePlan(in.siteId(), in.siteName(), in.excelName(), in.goLive(), candidates, total,
                differences, afterGoLive);
    }
}
