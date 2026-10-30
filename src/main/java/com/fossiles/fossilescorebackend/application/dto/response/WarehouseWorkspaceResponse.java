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
public class WarehouseWorkspaceResponse {
    private WarehouseOrderViewResponse order;
    private List<WarehouseUnitResponse> units;
    private Summary summary;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Summary {
        private int totalUnits;
        private int pendingUnits;
        private int receivedUnits;
        private int rejectedUnits;
        private int shippedUnits;
        /** Se recibieron todas las piezas de la orden. */
        private boolean complete;
        private boolean receiptClosed;
        private LocalDateTime warehouseReceiptClosedAt;
    }
}
