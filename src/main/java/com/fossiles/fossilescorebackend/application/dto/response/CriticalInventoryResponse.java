package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CriticalInventoryResponse {
    private Long materialId;
    private String materialSku;
    private String materialName;
    private Long locationId;
    private String locationCode;
    private String locationName;
    private BigDecimal currentStock;
    private Integer minStock;
    private Integer maxStock;
    private BigDecimal deficit;
    private String priority; // CRITICAL, LOW, WARNING
    private String reason; // BELOW_MIN, BELOW_REORDER_POINT, BELOW_THRESHOLD
}

