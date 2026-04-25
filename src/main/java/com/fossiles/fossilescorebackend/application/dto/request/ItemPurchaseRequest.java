package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ItemPurchaseRequest {
    @NotNull(message = "Item ID is required")
    private Long itemId;

    @NotNull(message = "Actual price is required")
    @Positive(message = "Actual price must be positive")
    private BigDecimal actualPrice;

    @Size(max = 200, message = "Supplier must not exceed 200 characters")
    private String supplier;

    @Size(max = 2000, message = "Notes must not exceed 2000 characters")
    private String notes;
}

