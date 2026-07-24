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
public class MinorExpenseResponse {
    private Long id;
    private String invoiceNumber;
    private LocalDate purchaseDate;
    private String description;
    private String supplier;
    private BigDecimal totalAmount;
    private String purchaserName;
    private String authorizerName;
    private BigDecimal companyAmount;
    /** Legacy mirror: reimbursementAmount (MENSAJERO) or returnedAmount (EMPRESA). */
    private BigDecimal messengerAmount;
    private BigDecimal reimbursementAmount; // Monto a reembolsar al mensajero (MENSAJERO)
    private BigDecimal initialAmountGiven; // Monto inicial que la empresa le dio al mensajero (caja chica)
    private BigDecimal returnedAmount; // Vuelto/cambio que el mensajero devuelve (EMPRESA)
    private String reimbursementStatus;
    private LocalDate reimbursementDate;
    private String reimbursementPaymentMethod;
    private BigDecimal reimbursementAdjustment; // Ajuste manual del reembolso
    private BigDecimal adjustedReimbursementAmount; // reimbursementAmount + reimbursementAdjustment (calculado)
    private String initialPaymentMethod;
    private String observations;
    private String invoiceFileUrl;
    private Long purchaseNumberId;
    private String purchaseNumber; // Número de compra (ej: "COMP-00001")
    private String purchaseNumberDescription; // Descripción del número de compra
    private Long purchaseNumberItemId; // ID del artículo de la compra asociado
    private BigDecimal estimatedPrice; // Precio estimado del artículo
    private Boolean fromPurchaseOrder; // Si este gasto proviene de una orden planificada
    private LocalDateTime createdAt;
    private Long createdBy;
    private String createdByName;
    private LocalDateTime updatedAt;
    private Long updatedBy;
    private String updatedByName;
}

