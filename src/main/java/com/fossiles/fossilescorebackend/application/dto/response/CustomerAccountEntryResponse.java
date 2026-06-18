package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerAccountEntryResponse {
    private Long id;
    private Long customerId;
    private String entryType;
    private LocalDate entryDate;
    private BigDecimal amount;
    private String reference;
    private String description;
    private String paymentMethod;
    private String movementConceptCode;
    private String receiptNumber;
    private LocalDate collectionDate;
    private BigDecimal paymentDiscountAmount;
    private BigDecimal paymentDiscountPercent;
    private BigDecimal grossCollectedAmount;
    private Long appliedToEntryId;
    private String invoiceNumber;
    private String documentNumber;
    private String returnVoucherNumber;
    private LocalDate returnDate;
    private String returnReason;
    private Long productionOrderId;
    private String productionOrderCode;
    private Long partialReleaseId;
    private Long productShipmentId;
    private String vendorShipmentNumber;
    private String orderKind;
    private String status;
    private LocalDateTime voidedAt;
    private Long voidedBy;
    private String voidedByName;
    private String voidReason;
    private LocalDateTime createdAt;
    private Long createdBy;
    private String createdByName;
}
