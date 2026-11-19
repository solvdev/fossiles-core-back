package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Productos de una categoría (y subgrupo) ordenados de más a menos vendidos. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskTopProductsResponse {
    private LocalDate from;
    private LocalDate to;
    private LocalDate previousFrom;
    private LocalDate previousTo;
    private Long categoryId;
    private String categoryName;
    /** Null = todos los subgrupos de la categoría (sin empaque). */
    private String subgroupCode;
    /** Kioscos que entraron en el reporte (para «vendido en N de M»). */
    private int kioskCount;
    private Totals totals;

    @Builder.Default
    private List<Item> items = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        private Long productId;
        private String productCode;
        private String productName;
        private String subgroupCode;
        private String subgroupLabel;
        /** Cantidad neta (ventas menos devoluciones y cambios del periodo). */
        private BigDecimal units;
        /** Cobrado neto con IVA, escala 2. */
        private BigDecimal totalAmount;
        private BigDecimal totalAmountNoIva;
        /** Kioscos donde se vendió al menos una pieza en el periodo. */
        private int kiosksSold;
        /** Null si no se pidió comparar. */
        private BigDecimal previousUnits;
        private BigDecimal previousAmount;
        /** Los colores más vendidos (solo ventas, sin devoluciones). */
        @Builder.Default
        private List<ColorUnits> topColors = new ArrayList<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ColorUnits {
        private String color;
        private BigDecimal units;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Totals {
        /** Suma de la categoría o subgrupo; coincide con la fila del reporte. */
        private BigDecimal units;
        private BigDecimal totalAmount;
        private BigDecimal totalAmountNoIva;
        /** Productos distintos con piezas netas positivas. */
        private int products;
        private BigDecimal previousUnits;
        private BigDecimal previousAmount;
    }
}
