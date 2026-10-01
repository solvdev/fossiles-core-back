package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Proyecciones de Finanzas por kiosco. Montos a 2 decimales; porcentajes/factores a 4. Ver el contrato. */
public final class KioskFinancialsForecastResponse {

    private KioskFinancialsForecastResponse() {
    }

    // ------------------------------------------------------------------ cierre del mes en curso

    /** GET /forecast/month-end. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MonthEnd {
        private LocalDate asOf;
        private Integer year;
        private Integer month;
        private Integer daysElapsed;
        /** Días por proyectar (incluye hoy). */
        private Integer daysRemaining;
        private Integer daysInMonth;
        /** Método con que se midió el punto de equilibrio (RATES | FLAT), según la configuración. */
        private String breakEvenMode;
        private List<Site> sites;
        private Totals totals;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Site {
        private Long siteId;
        private String name;
        /** Ventas de los días completos del mes (antes de hoy). */
        private BigDecimal mtd;
        /** Ventas hasta ahora, incluyendo lo vendido hoy. */
        private BigDecimal actualToDate;
        /** Cierre proyectado del mes; null si no hay historia suficiente. */
        private BigDecimal projected;
        /** Rango del 80 %. */
        private BigDecimal low;
        private BigDecimal high;
        private BigDecimal goal;
        /** projected / goal. */
        private BigDecimal goalPctProjected;
        /** actualToDate / goal. */
        private BigDecimal goalPctToDate;
        /** WEEKDAY | RUN_RATE | INSUFFICIENT. */
        private String method;
        private Integer sampleDays;
        /** Mes (yyyy-MM) de cuyos costos y tasas se tomó el P&L proyectado; null si no hay. */
        private String costsFrom;
        private BigDecimal totalCost;
        private BigDecimal difference;
        private BigDecimal margin;
        private BigDecimal breakEven;
        /** Cierre proyectado por debajo del punto de equilibrio. */
        private Boolean belowBreakEven;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Totals {
        private BigDecimal mtd;
        private BigDecimal actualToDate;
        private BigDecimal projected;
        /** Suma de metas de los kioscos con meta y proyección. */
        private BigDecimal goal;
        private BigDecimal goalPctProjected;
        private BigDecimal totalCost;
        private BigDecimal difference;
        private BigDecimal margin;
        private Integer sitesProjected;
        private Integer sitesWithoutProjection;
    }

    // ------------------------------------------------------------------ año siguiente

    /** GET /forecast/next-year. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NextYear {
        private LocalDate asOf;
        /** Año en curso (base de la proyección). */
        private Integer baseYear;
        private Integer targetYear;
        /** SITE_OR_COMPANY | OVERRIDE. */
        private String growthMode;
        /** Crecimiento global (mismos kioscos); null si ningún kiosco tiene base comparable. */
        private BigDecimal companyGrowthFactor;
        private BigDecimal overrideGrowthPct;
        private String breakEvenMode;
        /** Índice estacional por mes (promedio 1), de los kioscos con 12 meses de historia. */
        private List<BigDecimal> seasonalIndex;
        private List<SiteYear> sites;
        /** Kioscos que no se proyectan por tener menos de 2 meses de historia. */
        private List<String> skippedSites;
        private SiteYear totals;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SiteYear {
        private Long siteId;
        private String name;
        /** Factor año sobre año aplicado (1.10 = +10 %). */
        private BigDecimal growthFactor;
        /** SITE | COMPANY | OVERRIDE | NONE. */
        private String growthSource;
        private Boolean growthCapped;
        /** Año en curso estimado: real + cierre proyectado + resto proyectado. */
        private BigDecimal baseSales;
        /** Proyección del año siguiente. */
        private BigDecimal sales;
        private Integer estimatedMonths;
        private String costsFrom;
        private BigDecimal totalCost;
        private BigDecimal difference;
        private BigDecimal margin;
        private List<Month> months;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Month {
        private Integer month;
        private BigDecimal baseSales;
        private BigDecimal sales;
        /** true si el mes salió del nivel del kiosco × índice estacional (no de su propio año anterior). */
        private Boolean estimated;
        private BigDecimal totalCost;
        private BigDecimal difference;
        private BigDecimal margin;
        private BigDecimal breakEven;
    }
}
