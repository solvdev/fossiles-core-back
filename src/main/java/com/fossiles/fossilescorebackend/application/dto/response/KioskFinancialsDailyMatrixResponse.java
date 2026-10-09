package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** GET /api/kiosk-financials/daily-matrix. values[siteId] = null si no hay dato (distinto de 0). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskFinancialsDailyMatrixResponse {

    private Integer year;
    private Integer month;
    private List<SiteRef> sites;
    private List<Day> days;
    private Map<Long, BigDecimal> siteTotals;
    private BigDecimal grandTotal;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SiteRef {
        private Long siteId;
        private String name;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Day {
        private LocalDate date;
        private Map<Long, BigDecimal> values;
        private BigDecimal total;
        private BigDecimal cumulative;
    }
}
