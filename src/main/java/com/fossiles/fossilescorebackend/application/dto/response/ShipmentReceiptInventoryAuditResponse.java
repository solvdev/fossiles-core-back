package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShipmentReceiptInventoryAuditResponse {
    private Long shipmentId;
    private String shipmentNumber;
    private Long locationId;
    @Builder.Default
    private List<AuditLine> lines = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuditLine {
        private String lineType;
        private Long productId;
        private String productCode;
        private String productName;
        private Long materialId;
        private String materialSku;
        private BigDecimal qtyExpected;
        private int kioscoStockQty;
        private boolean movementApplied;
        private String lineRef;
    }
}
