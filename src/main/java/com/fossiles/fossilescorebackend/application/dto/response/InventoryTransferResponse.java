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
public class InventoryTransferResponse {
    private Long id;
    private Long fromLocationId;
    private String fromLocationCode;
    private String fromLocationName;
    private Long toLocationId;
    private String toLocationCode;
    private String toLocationName;
    private Long materialId;
    private String materialSku;
    private String materialName;
    private Long productId;
    private String productCode;
    private String productName;
    private Long colorId;
    private String colorName;
    private BigDecimal quantity;
    private String reason;
    private String physicalSlipNumber;
    private String status;
    private LocalDateTime transferDate;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private Long createdBy;
    private String createdByName;
    private LocalDateTime updatedAt;
    private Long updatedBy;
    private String updatedByName;
}

