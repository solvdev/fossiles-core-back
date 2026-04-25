package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BulkInventoryTransferRequest {
    @NotNull(message = "From Location ID is required")
    private Long fromLocationId;

    @NotNull(message = "To Location ID is required")
    private Long toLocationId;

    @NotEmpty(message = "Items list cannot be empty")
    @Valid
    private List<InventoryTransferItemRequest> items;

    private String reason;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InventoryTransferItemRequest {
        private Long materialId;
        private Long productId;
        private Long colorId; // Opcional: para productos con variantes de color

        @NotNull(message = "Quantity is required")
        private java.math.BigDecimal quantity;
    }
}

