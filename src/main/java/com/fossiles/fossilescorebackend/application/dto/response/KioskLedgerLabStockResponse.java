package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskLedgerLabStockResponse {
    private Long id;
    private Long locationId;
    private String locationCode;
    private String locationName;
    private Long productId;
    private String productCode;
    private String productName;
    private Long colorId;
    private String colorName;
    private Integer currentStock;
    private Integer minimumStock;
    private String hardwareCondition;
    /** JSON crudo de tallas (editable en lab). */
    private String sizesData;
    private Map<String, Integer> sizes;
    private Integer movementCount;
    private LocalDateTime lastUpdatedAt;
}
