package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Movimiento del libro (anexo de detalle, separado de la cartera). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerAccountPortfolioMovementResponse {
    private Long entryId;
    private Long customerId;
    private String customerName;
    private String legacyCode;
    private LocalDate entryDate;
    private LocalDate collectionDate;
    private String entryType;
    private String documentNumber;
    private String reference;
    private String description;
    /** Monto registrado en el libro (neto). */
    private BigDecimal amount;
    private BigDecimal debit;
    private BigDecimal credit;
    /** ACTIVE | VOID. Los anulados se listan pero nunca suman. */
    private String status;
    private String voidReason;
    private Long appliedToEntryId;
}
