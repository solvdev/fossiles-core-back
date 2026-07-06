package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskCashSessionDailySummaryResponse {
    private LocalDate workDate;
    private Long sessionId;
    private String sessionStatus;
    private BigDecimal openingAmount;
    private BigDecimal cashSalesTotal;
    private BigDecimal cashExpensesTotal;
    private BigDecimal expectedCash;
    private BigDecimal countedCash;
    private BigDecimal variance;
}
