package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Mapa de calor de kioscos (docs/SALES-DASHBOARD-CONTRACT.md, addendum 3): la venta diaria de TODOS los sitios
 * incluidos en reportes, con la misma fuente de dinero que Finanzas kioscos (histórico + POS, empaque incluido).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskHeatmapResponse {
    private LocalDate startDate;
    private LocalDate endDate;
    private LocalDate previousStartDate;
    private LocalDate previousEndDate;
    /** Cada día del rango actual, en orden; {@code SiteRow.daily} va alineado índice a índice con esta lista. */
    private List<LocalDate> days;
    /** Solo sitios con venta distinta de cero en el periodo actual o en el anterior. */
    private List<SiteRow> sites;
    /** Una fila por categoría presente en {@code sites}, en orden A, B, C, sin categoría. */
    private List<CategoryRow> categories;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SiteRow {
        /** Id del sitio de Finanzas kioscos ({@code kiosk_site.id}). */
        private Long siteId;
        private String name;
        /** "A", "B", "C" o null (sin clasificar). */
        private String category;
        /** Id de la location POS ligada al sitio; null si es un sitio histórico. */
        private Long locationId;
        /** "HIST" | "POS" | "MIXED" (según {@code SiteSales.source()}) o "NONE" si el sitio no tiene datos en el rango. */
        private String source;
        /** Σ de la venta en el periodo actual (2 decimales). */
        private BigDecimal total;
        /** Σ de la venta en el periodo anterior (2 decimales). */
        private BigDecimal previousTotal;
        /** Porcentaje ya escalado (12.4 = +12.4 %), mismo criterio que el resto del dashboard. */
        private BigDecimal growthPercent;
        /** Días del periodo actual con venta mayor que cero. */
        private int daysWithSales;
        /** Un monto por día (0.00 si no hubo venta), alineado índice a índice con {@code days}. */
        private List<BigDecimal> daily;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategoryRow {
        /** "A", "B", "C" o null (sin clasificar). */
        private String category;
        /** Sitios de {@code sites} con esa categoría. */
        private int kioskCount;
        private BigDecimal total;
        private BigDecimal previousTotal;
        private BigDecimal growthPercent;
        /** Porcentaje del total de todos los sitios en el periodo actual (0 si ese total es 0). */
        private BigDecimal sharePercent;
    }
}
