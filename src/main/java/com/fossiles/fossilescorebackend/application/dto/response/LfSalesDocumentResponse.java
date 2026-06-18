package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LfSalesDocumentResponse {
    private Long productionOrderId;
    private String orderCode;
    private String orderKind;
    private String orderType;
    private String vendorShipmentNumber;
    private String status;
    private LocalDate startDate;
    private LocalDate deliveryDate;
    private BigDecimal estimatedTotal;
    private BigDecimal chargedAmount;
    private BigDecimal balanceDue;
    private String chargeStatus;
    private Long chargeEntryId;
    private boolean vendorShipmentVoided;
    private List<LfPartialReleaseDocumentResponse> partialReleases;
}
