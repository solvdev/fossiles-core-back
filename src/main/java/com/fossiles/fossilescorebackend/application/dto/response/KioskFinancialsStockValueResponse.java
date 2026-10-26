package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Valor del inventario actual de cada kiosco ({@code kiosco_stock}): existencias × precio de venta del POS
 * (con recargo por talla de cincho) y costo estimado con el % de costo de producto de Finanzas.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskFinancialsStockValueResponse {

    /** Momento en que se calculó (hora de Guatemala). */
    private LocalDateTime generatedAt;
    private Totals totals;
    private List<SiteStock> sites;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Totals {
        private long units;
        /** Valor a precio de venta (con IVA). */
        private BigDecimal saleValue;
        /** Suma de los costos estimados de los kioscos que tienen % de costo configurado. */
        private BigDecimal estimatedCost;
        /** Unidades sin precio de venta en catálogo (no suman valor). */
        private long unitsWithoutPrice;
        private int sites;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SiteStock {
        private Long siteId;
        private String siteName;
        private Long locationId;
        private String salesCategory;
        private long units;
        /** Productos distintos (producto + color + herraje/marca) con existencia. */
        private int skus;
        private BigDecimal saleValue;
        /** % de costo de producto usado (último mes configurado); null si el kiosco no tiene. */
        private BigDecimal productCostPct;
        /** Año-mes de donde salió el % ("2026-09"); null si no hay. */
        private String productCostPeriod;
        /** saleValue × productCostPct; null si no hay %. */
        private BigDecimal estimatedCost;
        private long unitsWithoutPrice;
        /** Participación del kiosco en el valor total (0..1). */
        private BigDecimal share;
        /** Última vez que se movió el stock del kiosco. */
        private LocalDateTime lastUpdatedAt;
        private List<Line> lines;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Line {
        private Long productId;
        private String productCode;
        private String productName;
        private String categoryName;
        private String colorName;
        /** NUEVO | VIEJO o marca en Entre Cueros. */
        private String hardwareCondition;
        private long units;
        /** Precio unitario de catálogo (sin recargo por talla); 0 si el producto no tiene precio. */
        private BigDecimal unitPrice;
        /** Valor de la línea, con el recargo de talla de las unidades que lo llevan. */
        private BigDecimal saleValue;
        /** Desglose por talla ("38: 2, 44: 1"); null si no aplica. */
        private String sizes;
    }
}
