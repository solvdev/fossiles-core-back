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
public class PurchaseNumberItemResponse {
    private Long id;
    private Long purchaseNumberId;
    private String itemName;
    private String description;
    private String supplier;
    private BigDecimal estimatedPrice;
    private Integer quantity;
    private BigDecimal estimatedTotal;
    private BigDecimal actualPrice; // Precio real cuando se registra el gasto
    private Long minorExpenseId; // ID del gasto asociado si ya se registró
    private LocalDateTime createdAt;
    private Long createdBy;
    private String createdByName;
    private LocalDateTime updatedAt;
    private Long updatedBy;
    private String updatedByName;
    
    // Campos calculados para mostrar diferencias
    private BigDecimal priceDifference; // actualPrice - estimatedPrice (si existe actualPrice)
    private Boolean isPurchased; // Si ya tiene un minorExpense asociado
}


