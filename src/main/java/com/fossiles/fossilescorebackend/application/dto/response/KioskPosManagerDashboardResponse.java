package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskPosManagerDashboardResponse {
    private Metric today;
    private Metric todayLastYear;
    private Metric lastMonth;
    private Metric monthToDate;
    /** Acumulado del mes en curso en el mismo rango de dias del año anterior (1 al dia de hoy). */
    private Metric monthToDateLastYear;
    private BigDecimal growthVsLastYearPercent;
    private BigDecimal growthMonthToDateVsLastYearPercent;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Metric {
        private BigDecimal amount;
        /** Cantidad de ventas; null cuando el dato viene del historico (solo guarda montos diarios). */
        private Integer count;
    }
}
