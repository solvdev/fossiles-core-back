package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LfPartialReleaseDocumentResponse {
    private Long partialReleaseId;
    private Integer sequenceNum;
    private String label;
    private String status;
    private BigDecimal estimatedTotal;
    private BigDecimal chargedAmount;
    private BigDecimal balanceDue;
    private String chargeStatus;
    private Long chargeEntryId;
    private List<LfShipmentDocumentResponse> shipments;
}
