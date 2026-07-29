package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskCashSessionHistoryItemResponse {
    private Long sessionId;
    private Long kioskLocationId;
    private String kioskCode;
    private String kioskName;
    private String openedByName;
    private String closedByName;
    private LocalDateTime openedAt;
    private LocalDateTime closedAt;
    private Integer salesCount;
    private BigDecimal salesTotal;
    private BigDecimal cashSalesTotal;
    private BigDecimal cardSalesTotal;
    private BigDecimal disbursementsTotal;
    private BigDecimal openingAmount;
    private BigDecimal countedCash;
    private BigDecimal expectedCash;
    /** Diferencia de efectivo (contado − esperado). */
    private BigDecimal variance;
    /** Suma de (voucher − tarjeta en factura) del turno. Informativo; no altera FEL. */
    private BigDecimal cardVoucherDifferencesTotal;
}
