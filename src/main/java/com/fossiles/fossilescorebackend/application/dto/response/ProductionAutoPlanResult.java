package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductionAutoPlanResult {
    private int centroTasksCreated;
    private int cinchoTasksCreated;
    @Builder.Default
    private List<Long> createdTaskIds = new ArrayList<>();
    @Builder.Default
    private List<BlockedLeatherLine> blockedNoLeather = new ArrayList<>();
    @Builder.Default
    private List<String> notes = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BlockedLeatherLine {
        private Long productionOrderId;
        private String productionOrderCode;
        private Long productionOrderItemId;
        private String productCode;
        private String productName;
        private int remainingQuantity;
        private String reason;
    }
}
