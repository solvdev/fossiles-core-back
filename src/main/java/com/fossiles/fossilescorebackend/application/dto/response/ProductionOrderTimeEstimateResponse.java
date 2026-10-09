package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductionOrderTimeEstimateResponse {
    private Long productionOrderId;
    private String productionOrderCode;
    private String orderType;
    private String status;
    private Integer deskCount;
    private LocalDate efficiencyFrom;
    private Double efficiencyPercent;
    private Integer measuredTasks;
    private Double theoreticalHours;
    private Double adjustedHours;
    private Integer businessDays;
    private LocalDate estimatedStartDate;
    private LocalDate estimatedEndDate;
    private List<LineEstimate> lines;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LineEstimate {
        private Long itemId;
        private Long productId;
        private String productCode;
        private String productName;
        private Integer quantity;
        private Double prdTimePerUnit;
        private Double lineHours;
    }
}
