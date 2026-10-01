package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** GET /api/kiosk-financials/pnl. Montos a 2 decimales; porcentajes/margen a 4. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskFinancialsPnlResponse {

    private Integer year;
    /** null = ano completo. */
    private Integer month;
    /** Cómo se midió el punto de equilibrio: RATES (tasas de cada kiosco) | FLAT (tasa fija, 27 %). */
    private String breakEvenMode;
    private List<SitePnl> sites;
    private Figures totals;

    @Data
    @SuperBuilder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Figures {
        private BigDecimal sales;
        private BigDecimal goal;
        private BigDecimal goalPct;
        private BigDecimal participationPct;
        private Variable variable;
        private Fixed fixed;
        private BigDecimal totalCost;
        private BigDecimal difference;
        private BigDecimal margin;
        private BigDecimal breakEven;
        private BigDecimal breakEvenDaily;
        private Integer daysWithSales;
        /** Solo cuando month se omite. */
        private List<MonthLine> byMonth;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    @SuperBuilder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SitePnl extends Figures {
        private Long siteId;
        private String name;
        /** HIST | POS | MIXED. */
        private String source;
        private Boolean complete;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Variable {
        private BigDecimal productCost;
        private BigDecimal salesCommission;
        private BigDecimal cardCommission;
        private BigDecimal tax;
        /** Bono por meta (encargada): Q500 desde 90 % de la meta, Q800 desde 100 %; desde septiembre 2026. */
        private BigDecimal bonus;
        private BigDecimal total;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Fixed {
        private Map<String, BigDecimal> byCategory;
        private BigDecimal total;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MonthLine {
        private Integer month;
        private BigDecimal sales;
        private BigDecimal totalCost;
        private BigDecimal difference;
        private BigDecimal margin;
    }
}
