package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
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
public class MinorExpenseRequest {
    @NotBlank(message = "Invoice number is required")
    @Size(max = 100, message = "Invoice number must not exceed 100 characters")
    private String invoiceNumber;

    @NotNull(message = "Purchase date is required")
    private LocalDate purchaseDate;

    @NotBlank(message = "Description is required")
    @Size(max = 1000, message = "Description must not exceed 1000 characters")
    private String description;

    @NotBlank(message = "Supplier is required")
    @Size(max = 200, message = "Supplier must not exceed 200 characters")
    private String supplier;

    @NotNull(message = "Total amount is required")
    @Positive(message = "Total amount must be positive")
    private BigDecimal totalAmount;

    @NotBlank(message = "Purchaser name is required")
    @Size(max = 100, message = "Purchaser name must not exceed 100 characters")
    private String purchaserName;

    @Size(max = 100, message = "Authorizer name must not exceed 100 characters")
    private String authorizerName;

    private BigDecimal companyAmount;

    private BigDecimal messengerAmount;

    private BigDecimal initialAmountGiven; // Monto inicial que la empresa le dio al mensajero (caja chica)

    private BigDecimal returnedAmount; // Monto que el mensajero devuelve a la empresa (cambio/sobrante)

    @Size(max = 30, message = "Reimbursement status must not exceed 30 characters")
    private String reimbursementStatus; // PENDIENTE, PAGADO, NO_APLICA

    private LocalDate reimbursementDate;

    @Size(max = 100, message = "Reimbursement payment method must not exceed 100 characters")
    private String reimbursementPaymentMethod;

    private BigDecimal reimbursementAdjustment; // Ajuste manual del reembolso (puede ser positivo o negativo)

    @NotBlank(message = "Initial payment method is required")
    @Size(max = 30, message = "Initial payment method must not exceed 30 characters")
    private String initialPaymentMethod; // EMPRESA, MENSAJERO

    @Size(max = 2000, message = "Observations must not exceed 2000 characters")
    private String observations;

    @Size(max = 500, message = "Invoice file URL must not exceed 500 characters")
    private String invoiceFileUrl;

    private Long purchaseNumberId; // ID del número de compra asociado (opcional)
    
    private Long purchaseNumberItemId; // ID del artículo de la compra asociado (opcional)
    
    private BigDecimal estimatedPrice; // Precio estimado del artículo (del PurchaseNumberItem)
}

