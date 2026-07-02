package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class CustomerAccountDocumentSettlementRequest {

    @NotNull(message = "appliedToEntryId is required")
    private Long appliedToEntryId;

    /** Descuento comercial que reduce saldo sin cobro (Q). */
    private BigDecimal discountAmount;

    /** Descuento comercial (% del saldo del documento). */
    private BigDecimal discountPercent;

    /** Bruto a descargar (concepto 11). Opcional si solo hay descuento. */
    private BigDecimal paymentGross;

    /** Descuento al cobrar (Q) sobre paymentGross. */
    private BigDecimal paymentDiscountAmount;

    /** Descuento al cobrar (%) sobre paymentGross. */
    private BigDecimal paymentDiscountPercent;

    @NotNull(message = "entryDate is required")
    private LocalDate entryDate;

    private LocalDate collectionDate;

    @Size(max = 50)
    private String receiptNumber;

    @Size(max = 50)
    private String documentNumber;

    @Size(max = 50)
    private String paymentMethod;

    @Size(max = 50)
    private String returnVoucherNumber;

    private LocalDate returnDate;

    @Size(max = 2000)
    private String notes;

    private Long productionOrderId;

    private Long partialReleaseId;

    private Long productShipmentId;

    @Size(max = 50)
    private String invoiceNumber;

    @Size(max = 30)
    private String vendorShipmentNumber;
}
