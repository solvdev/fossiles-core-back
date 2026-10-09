package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Inversión en publicidad vs venta online por día (resultado = venta − inversión, sin costo de producción). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OnlineAdSpendReportResponse {
    private LocalDate startDate;
    private LocalDate endDate;
    private Totals totals;
    private List<Day> days;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Totals {
        /** Venta total online de todo el rango (con y sin inversión capturada). */
        private BigDecimal salesAmount;
        private int ordersCount;
        /** Venta de los días con inversión capturada. */
        private BigDecimal comparableSales;
        private BigDecimal adSpend;
        /** comparableSales − adSpend */
        private BigDecimal netResult;
        /** comparableSales / adSpend; null si adSpend = 0 */
        private BigDecimal roas;
        private int daysWithSpend;
        private int daysNoSpend;
        private int daysWin;
        private int daysLoss;
        private int daysEven;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Day {
        private LocalDate date;
        private BigDecimal salesAmount;
        private int ordersCount;
        /** null si no hay captura ese día */
        private BigDecimal adSpend;
        private BigDecimal netResult;
        private BigDecimal roas;
        /** WIN | LOSS | EVEN | NO_SPEND */
        private String status;
        private String notes;
    }
}
