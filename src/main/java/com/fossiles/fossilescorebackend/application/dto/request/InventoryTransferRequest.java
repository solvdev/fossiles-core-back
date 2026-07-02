package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.DecimalMin;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryTransferRequest {
    @NotNull(message = "From Location ID is required")
    private Long fromLocationId;

    @NotNull(message = "To Location ID is required")
    private Long toLocationId;

    private Long materialId;
    private Long productId;
    private Long colorId; // Opcional: para productos con variantes de color

    @NotNull(message = "Quantity is required")
    @DecimalMin(value = "0.0001", message = "Quantity must be greater than 0")
    private BigDecimal quantity;

    private String reason;

    private String physicalSlipNumber;
}

