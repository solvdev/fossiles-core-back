package com.fossiles.fossilescorebackend.application.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.ALWAYS)
public class PurchaseNumberResponse {
    private Long id;
    private String purchaseNumber;
    private String status;
    private String description;
    private BigDecimal totalAmount;           // Monto estimado/asignado para la compra

    // ====== Campos de balance contable ======
    private BigDecimal totalSpent;            // Suma real de gastos (facturas)
    private BigDecimal rawBalance;            // totalAmount - totalSpent (positivo = sobrante, negativo = faltante)
    private BigDecimal compensationsGiven;    // Sobrante cedido a otras compras
    private BigDecimal compensationsReceived; // Sobrante recibido de otras compras
    private BigDecimal netBalance;            // rawBalance - compensationsGiven + compensationsReceived

    private LocalDateTime createdAt;
    private Long createdBy;
    private String createdByName;
    private LocalDateTime updatedAt;
    private Long updatedBy;
    private String updatedByName;
    private Long expenseCount;
    private Boolean editable;
    private Boolean itemsEditable;
}

