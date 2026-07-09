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
public class CustomerAccountReceivableSearchResponse {
    private Long customerId;
    private String customerName;
    private String legacyCode;
    private String nit;
    private String routeLocationCode;
    private String routeLocationLabel;

    private Long productionOrderId;
    private String orderCode;
    private String orderKind;
    private String orderType;

    private Long productShipmentId;
    private String shipmentNumber;
    private String vendorShipmentNumber;

    private Long partialReleaseId;
    private String partialReleaseLabel;

    /** ORDER o SHIPMENT */
    private String documentLevel;

    private Long chargeEntryId;
    private String invoiceNumber;
    private String chargeStatus;
    private BigDecimal chargedAmount;
    /** Abonos + notas de crédito + devoluciones aplicadas al cargo. */
    private BigDecimal appliedCredits;
    private BigDecimal balanceDue;
    private BigDecimal estimatedTotal;
    private boolean hasCharge;
    private boolean hasPayment;
    private LocalDate chargeDate;
}
