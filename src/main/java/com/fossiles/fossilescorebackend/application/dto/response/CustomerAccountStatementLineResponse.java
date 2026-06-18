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
    private String vendorShipmentNumber;
    private String orderKind;
    private BigDecimal grossCollectedAmount;
    private BigDecimal paymentDiscountAmount;
    private String status;
    private BigDecimal debit;
    private BigDecimal credit;
    private BigDecimal runningBalance;
}
