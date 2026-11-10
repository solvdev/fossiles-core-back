package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Cuero (ft²) que piden las OP activas: total por color, por cuero (material) y por orden.
 * «Entregado» = piezas cuyo cuero ya salió a mesa (tarea iniciada/terminada o cuero marcado);
 * «Falta» = lo que todavía hay que entregar.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductionLeatherDemandResponse {
    private LocalDateTime generatedAt;
    private Totals totals;
    private List<ColorDemand> byColor;
    private List<MaterialDemand> byMaterial;
    private List<OrderDemand> orders;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Totals {
        private Integer orders;
        private Integer units;
        private Integer deliveredUnits;
        private Integer pendingUnits;
        private BigDecimal totalFt2;
        private BigDecimal deliveredFt2;
        private BigDecimal pendingFt2;
        private BigDecimal shortageFt2;
        private Integer missingRecipeLines;
        private Integer missingRecipeUnits;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ColorDemand {
        private Long colorId;
        private String colorName;
        private Integer orders;
        private Integer units;
        private Integer pendingUnits;
        private BigDecimal totalFt2;
        private BigDecimal deliveredFt2;
        private BigDecimal pendingFt2;
        /** Stock de los cueros que usa el color (suma de materiales distintos). */
        private BigDecimal availableFt2;
        private BigDecimal shortageFt2;
        private Integer missingRecipeUnits;
        private List<String> materials;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MaterialDemand {
        private Long materialId;
        private String sku;
        private String name;
        private Integer orders;
        private BigDecimal totalFt2;
        private BigDecimal pendingFt2;
        private BigDecimal availableFt2;
        private BigDecimal shortageFt2;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderDemand {
        private Long orderId;
        private String code;
        private String orderType;
        private String status;
        private String customerName;
        private LocalDate deliveryDate;
        private Integer units;
        private Integer pendingUnits;
        private BigDecimal totalFt2;
        private BigDecimal deliveredFt2;
        private BigDecimal pendingFt2;
        private Integer missingRecipeLines;
        private List<OrderLine> lines;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderLine {
        private Long itemId;
        private String productCode;
        private String productName;
        private Long colorId;
        private String colorName;
        private Long materialId;
        private String materialName;
        private Integer units;
        private Integer deliveredUnits;
        private Integer pendingUnits;
        private BigDecimal ft2PerUnit;
        private BigDecimal totalFt2;
        private BigDecimal pendingFt2;
        private Boolean missingRecipe;
    }
}
