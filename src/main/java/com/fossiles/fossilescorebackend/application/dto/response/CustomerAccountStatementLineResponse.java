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
public class CustomerAccountStatementLineResponse {
    private Long id;
    private LocalDate entryDate;
    private LocalDate collectionDate;
    private String entryType;
    private String movementConceptCode;
    private String reference;
    private String receiptNumber;
    private String description;
    private String paymentMethod;
    private String invoiceNumber;
    private String documentNumber;
    private String returnVoucherNumber;
    private String productionOrderCode;
    private Long productionOrderId;
    private Long partialReleaseId;
    private Long productShipmentId;
    private String vendorShipmentNumber;
    private String orderKind;
    private BigDecimal grossCollectedAmount;
    private BigDecimal paymentDiscountAmount;
    private String status;
    private BigDecimal debit;
    private BigDecimal credit;
    private BigDecimal runningBalance;
    /** Cargo al que aplica este movimiento (descarga, descuento, devolución). */
    private Long appliedToEntryId;
    /** Saldo pendiente del cargo (solo en filas tipo CHARGE activas). */
    private BigDecimal chargeBalanceDue;
}
