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
public class LfReceivableDocumentResponse {
    private Long chargeEntryId;
    private Long productionOrderId;
    private Long partialReleaseId;
    private Long productShipmentId;
    private String orderCode;
    private String orderKind;
    private String invoiceNumber;
    private String documentNumber;
    private String partialReleaseLabel;
    private LocalDate dueDate;
    private BigDecimal chargeAmount;
    private BigDecimal balanceDue;
    private String chargeStatus;
}
