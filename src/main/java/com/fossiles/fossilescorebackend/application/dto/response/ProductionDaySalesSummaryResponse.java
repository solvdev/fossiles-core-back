package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductionDaySalesSummaryResponse {
    private LocalDate date;
    @Builder.Default
    private List<Row> goingToProduction = new ArrayList<>();
    @Builder.Default
    private List<Row> notGoingToProduction = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Row {
        private Long productionOrderId;
        private String code;
        private String orderType;
        private String customerName;
        private boolean onlineSale;
        private String status;
        private int remainingCentroQty;
        private int remainingCinchoQty;
        private String reason;
    }
}
