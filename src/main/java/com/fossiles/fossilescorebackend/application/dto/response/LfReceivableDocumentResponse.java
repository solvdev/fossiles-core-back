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
    private Long customerId;
    private String customerName;
    private String legacyCode;
    private String nit;
    private String routeLocationCode;
    private String routeLocationLabel;
    private Long productionOrderId;
    private Long partialReleaseId;
    private Long productShipmentId;
    private String orderCode;
    private String orderKind;
    private String invoiceNumber;
    private String documentNumber;
    private String vendorShipmentNumber;
    private String partialReleaseLabel;
    private LocalDate dueDate;
    private LocalDate chargeDate;
    /** Monto original del cargo (CARGOS). */
    private BigDecimal chargeAmount;
    /** Abonos + notas de crédito + devoluciones aplicadas al cargo. */
    private BigDecimal appliedCredits;
    private BigDecimal balanceDue;
    private String chargeStatus;
}
