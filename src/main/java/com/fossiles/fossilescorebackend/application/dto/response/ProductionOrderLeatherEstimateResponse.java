package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Estimado de cuero de una OP. Los campos existentes de cantidad y ft²
 * ({@code quantity}, {@code lineFt2}, {@code totalUnits}, {@code totalFt2})
 * son el restante. Junto a ellos van el pedido completo
 * ({@code totalQuantity} y, en cada línea, {@code totalFt2}) y lo ya usado
 * ({@code usedQuantity}, {@code usedFt2}).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductionOrderLeatherEstimateResponse {
    private Long productionOrderId;
    private String productionOrderCode;
    private String orderType;
    private String status;
    /** ft² restantes. */
    private BigDecimal totalFt2;
    /** Unidades restantes. */
    private Integer totalUnits;
    /** Unidades del pedido completo. */
    private Integer totalQuantity;
    private Integer usedQuantity;
    private BigDecimal usedFt2;
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
        /** Unidades restantes. */
        private Integer quantity;
        private Integer totalQuantity;
        private Integer usedQuantity;
        private BigDecimal ft2PerUnit;
        /** ft² restantes de la línea. */
        private BigDecimal lineFt2;
        /** ft² del pedido completo de la línea. */
        private BigDecimal totalFt2;
        private BigDecimal usedFt2;
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
        /** Unidades restantes. */
        private Integer quantity;
        private Integer totalQuantity;
        private Integer usedQuantity;
        /** ft² restantes. */
        private BigDecimal totalFt2;
        private BigDecimal usedFt2;
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
        /** ft² restantes. Faltante = max(totalFt2 − availableFt2, 0). */
        private BigDecimal totalFt2;
        private BigDecimal usedFt2;
        private BigDecimal availableFt2;
    }
}
