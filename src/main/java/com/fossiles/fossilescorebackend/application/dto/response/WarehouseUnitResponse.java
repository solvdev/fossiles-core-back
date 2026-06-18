package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WarehouseUnitResponse {
    private Long id;
    private Long productionOrderId;
    private Long productionOrderItemId;
    private String unitLabel;
    private Long productId;
    private String productCode;
    private String productName;
    private Long colorId;
    private String colorName;
    private String sizeKey;
    private Integer unitSeq;
    private String receiptStatus;
    private String rejectionReason;
    private LocalDateTime receivedAt;
    private String shipmentRefType;
    private Long shipmentRefId;
    private LocalDateTime shippedAt;
    private boolean shipped;
}
