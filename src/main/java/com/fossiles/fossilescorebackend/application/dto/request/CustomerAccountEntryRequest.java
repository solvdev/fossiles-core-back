package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class CustomerAccountEntryRequest {

    @NotBlank(message = "entryType is required")
    @Size(max = 30)
    private String entryType;

    @NotNull(message = "entryDate is required")
    private LocalDate entryDate;

    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.01", message = "amount must be greater than zero")
    private BigDecimal amount;

    @Size(max = 100)
    private String reference;

    @Size(max = 2000)
    private String description;

    @Size(max = 50)
    private String paymentMethod;

    @Size(max = 10)
    private String movementConceptCode;

    @Size(max = 50)
    private String receiptNumber;

    private LocalDate collectionDate;

    private BigDecimal paymentDiscountAmount;

    private BigDecimal paymentDiscountPercent;

    private BigDecimal grossCollectedAmount;

    private Long appliedToEntryId;

    @Size(max = 50)
    private String invoiceNumber;

    @Size(max = 50)
    private String documentNumber;

    @Size(max = 50)
    private String returnVoucherNumber;

    private LocalDate returnDate;

    @Size(max = 2000)
    private String returnReason;

    private Long productionOrderId;

    private Long partialReleaseId;

    private Long productShipmentId;

    @Size(max = 30)
    private String vendorShipmentNumber;
}
