package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioscoConsolidatedReportResponse {
    private LocalDateTime generatedAt;
    private Integer totalKiosks;
    private Integer totalStockRows;
    private Integer totalUnits;
    private Integer totalLowStockRows;
    private List<KioscoSummary> kiosks;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KioscoSummary {
        private Long locationId;
        private String locationCode;
        private String locationName;
        private Integer totalUnits;
        private Integer lowStockRows;
        private Integer stockRows;
    }
}
