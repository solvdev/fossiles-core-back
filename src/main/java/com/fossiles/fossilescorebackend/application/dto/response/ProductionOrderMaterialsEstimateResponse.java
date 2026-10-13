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
public class ProductionOrderMaterialsEstimateResponse {
    private Long productionOrderId;
    private String productionOrderCode;
    private String orderType;
    private String status;
    private Integer totalUnits;
    private Integer materialCount;
    private Integer linesMissingBom;
    private Integer linesSkippedNoMaterials;
    private List<LineEstimate> lines;
    private List<MaterialSummary> byMaterial;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LineEstimate {
        private Long itemId;
        private Long productId;
        private String productCode;
        private String productName;
        private Long colorId;
        private String colorName;
        private Integer quantity;
        private Long bomId;
        private String bomName;
        private Long materialId;
        private String materialSku;
        private String materialName;
        private BigDecimal qtyPerUnit;
        private BigDecimal totalQty;
        private String unit;
        private BigDecimal available;
        private BigDecimal toOrder;
        private boolean missingBom;
        private String note;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MaterialSummary {
        private Long materialId;
        private String materialSku;
        private String materialName;
        private String unit;
        private BigDecimal requiredQty;
        private BigDecimal availableQty;
        private BigDecimal toOrderQty;
        private boolean sufficient;
    }
}
