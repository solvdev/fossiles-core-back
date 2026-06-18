package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DispatchStockPreviewResponse {
    private BigDecimal availableTotal;
    private List<DispatchStockBreakdownRow> breakdown;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DispatchStockBreakdownRow {
        private Long locationId;
        private String locationCode;
        private String locationName;
        private BigDecimal quantity;
    }
}
