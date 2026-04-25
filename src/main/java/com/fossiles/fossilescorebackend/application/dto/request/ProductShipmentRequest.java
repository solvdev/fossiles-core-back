package com.fossiles.fossilescorebackend.application.dto.request;

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
public class ProductShipmentRequest {
    private Long shipmentId;

    @NotNull(message = "Location ID is required")
    private Long locationId;
    
    private String notes;
    
    private List<ProductShipmentDetailRequest> products;
    private List<PackingItemRequest> packingItems;
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProductShipmentDetailRequest {
        @NotNull(message = "Product ID is required")
        private Long productId;

        private Long colorId;
        private String size;
        
        @NotNull(message = "Quantity is required")
        private java.math.BigDecimal quantity;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PackingItemRequest {
        @NotNull(message = "Material ID is required")
        private Long materialId;

        @NotNull(message = "Quantity is required")
        private java.math.BigDecimal quantity;

        private java.math.BigDecimal unitPrice;
    }
}

