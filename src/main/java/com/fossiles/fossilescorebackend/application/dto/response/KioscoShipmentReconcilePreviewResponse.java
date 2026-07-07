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
public class KioscoShipmentReconcilePreviewResponse {
    private Long locationId;
    private Long shipmentId;
    private int shipmentsReviewed;
    private int linesWithChanges;
    private int entradasToDelete;
    private int entradasToTrim;
    private int entradasToAdd;
    private int mermasToDelete;
    private int kardexLinesToNormalize;
    private int stockRowsToRecalculate;
    private boolean hasChanges;
    @Builder.Default
    private List<String> warnings = new ArrayList<>();
    @Builder.Default
    private List<PreviewLine> lines = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PreviewLine {
        private Long shipmentId;
        private String shipmentNumber;
        private String lineType;
        private Long productId;
        private String productCode;
        private String productName;
        private Long colorId;
        private String colorName;
        private int qtyExpected;
        private int currentEntradaSum;
        private int movementCount;
        private int currentStockQty;
        /** OK | CHANGE | WARNING */
        private String status;
        @Builder.Default
        private List<PreviewAction> actions = new ArrayList<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PreviewAction {
        /** DELETE_ENTRADA | TRIM_ENTRADA | DELETE_MERMA | ADD_ENTRADA | NORMALIZE_KARDEX | RECALCULATE_STOCK */
        private String type;
        private Long movementId;
        private Integer quantity;
        private String label;
    }
}
