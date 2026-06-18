package com.fossiles.fossilescorebackend.application.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WarehouseUnitReceiptRequest {
    private List<UnitUpdate> units;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UnitUpdate {
        private Long unitId;
        private String receiptStatus; // RECEIVED | REJECTED | PENDING
        private String rejectionReason;
    }
}
