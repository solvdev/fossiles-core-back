package com.fossiles.fossilescorebackend.application.dto.response;

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
public class ProductInventoryLocationResponse {
    private Long id;
    private Long productId;
    private String productCode;
    private String productName;
    /** Categoría de catálogo del producto */
    private Long productCategoryId;
    private String productCategoryName;
    private Long locationId;
    private String locationCode;
    private String locationName;
    private Long colorId;
    private String colorName;
    private BigDecimal quantity;
    private BigDecimal min;
    private LocalDateTime createdAt;
    private Long createdBy;
    private LocalDateTime updatedAt;
    private Long updatedBy;
}

