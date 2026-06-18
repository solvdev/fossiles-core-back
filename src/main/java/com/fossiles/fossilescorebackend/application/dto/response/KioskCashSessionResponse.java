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
public class KioskCashSessionResponse {
    private Long id;
    private Long kioskLocationId;
    private String kioskCode;
    private String kioskName;
    private Long openedByUserId;
    private String openedByName;
    private LocalDateTime openedAt;
    private BigDecimal openingAmount;
    private LocalDateTime closedAt;
    private Long closedByUserId;
    private String closedByName;
    private BigDecimal countedCash;
    private BigDecimal expectedCash;
    private BigDecimal variance;
    private String closeNotes;
    private String status;
    private Integer salesCount;
    private BigDecimal cashSalesTotal;
    private BigDecimal cardSalesTotal;
}
