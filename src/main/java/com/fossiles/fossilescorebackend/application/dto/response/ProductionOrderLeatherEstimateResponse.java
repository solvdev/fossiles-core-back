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
public class ProductionOrderLeatherEstimateResponse {
    private Long productionOrderId;
    private String productionOrderCode;
    private String orderType;
    private String status;
    private BigDecimal totalFt2;
    private Integer totalUnits;
    private Integer linesMissingRecipe;
    private List<LineEstimate> lines;
    private List<ColorSummary> byColor;
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
        private BigDecimal ft2PerUnit;
        private BigDecimal lineFt2;
        private Long leatherMaterialId;
        private String leatherMaterialSku;
        private String leatherMaterialName;
        private BigDecimal availableFt2;
        private boolean missingRecipe;
        private String note;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ColorSummary {
        private Long colorId;
        private String colorName;
        private Integer quantity;
        private BigDecimal totalFt2;
        private Integer lineCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MaterialSummary {
        private Long leatherMaterialId;
        private String leatherMaterialSku;
        private String leatherMaterialName;
        private BigDecimal totalFt2;
        private BigDecimal availableFt2;
    }
}
