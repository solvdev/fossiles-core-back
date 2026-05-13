package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryAdjustmentResponse {
    private Long id;
    private Long locationId;
    private String locationCode;
    private String locationName;
    private Long materialId;
    private String materialSku;
    private String materialName;
    private Long productId;
    private String productCode;
    private String productName;
    private Long colorId;
    private String colorName;
    private Map<String, BigDecimal> systemSizes;
    private Map<String, BigDecimal> physicalSizes;
    private BigDecimal systemStock;
    private BigDecimal physicalStock;
    private BigDecimal adjustmentQuantity;
    private String reason;
    private LocalDateTime adjustmentDate;
    private LocalDateTime createdAt;
    private Long createdBy;
    private String createdByName;
    private LocalDateTime updatedAt;
    private Long updatedBy;
    private String updatedByName;
}

