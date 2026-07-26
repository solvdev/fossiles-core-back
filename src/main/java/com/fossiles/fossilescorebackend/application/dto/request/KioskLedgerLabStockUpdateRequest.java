package com.fossiles.fossilescorebackend.application.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskLedgerLabStockUpdateRequest {
    private Integer currentStock;
    private Integer minimumStock;
    private String sizesData;
    private String hardwareCondition;
}
