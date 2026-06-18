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
public class LfShipmentDocumentResponse {
    private Long productShipmentId;
    private String shipmentNumber;
    private String status;
    private BigDecimal estimatedTotal;
    private BigDecimal chargedAmount;
    private BigDecimal balanceDue;
    private String chargeStatus;
    private Long chargeEntryId;
}
