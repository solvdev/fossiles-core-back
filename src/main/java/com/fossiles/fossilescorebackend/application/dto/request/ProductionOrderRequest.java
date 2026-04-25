package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductionOrderRequest {
    @Size(max = 30, message = "Code must not exceed 30 characters")
    private String code; // Opcional: se genera automáticamente si no se proporciona

    @NotBlank(message = "Order type is required")
    @Size(max = 20, message = "Order type must not exceed 20 characters")
    private String orderType; // CINCHOS, MARCAS, NORMAL

    private Long customerId;

    @Size(max = 200, message = "Customer name must not exceed 200 characters")
    private String customerName;

    @Size(max = 150, message = "Seller name must not exceed 150 characters")
    private String sellerName;

    private LocalDate startDate;

    private LocalDate deliveryDate;

    @Size(max = 1000, message = "Observations must not exceed 1000 characters")
    private String observations;

    @Size(max = 20, message = "Status must not exceed 20 characters")
    private String status;

    private Long distributionId;

    private BigDecimal shippingCost;

    @Valid
    private List<PackingItemRequest> packingItems;

    @Valid
    @NotNull(message = "Items are required")
    private List<ProductionOrderItemRequest> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PackingItemRequest {
        @NotNull(message = "Material ID is required")
        private Long materialId;

        @NotNull(message = "Quantity is required")
        private BigDecimal quantity;

        private BigDecimal unitPrice;
        private String materialCode;
        private String materialName;
    }
}

