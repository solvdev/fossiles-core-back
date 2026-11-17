package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Rotación y cobertura de inventario: por cada línea, cuánto hay, cuánto se movió en la ventana y cuántos días
 * alcanza. Sirve igual para producto en kioscos (movimiento = venta neta) y para materiales (= consumo).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryRotationResponse {

    /** PRODUCTS o MATERIALS. */
    private String scope;
    private int windowDays;
    private LocalDate since;
    /** Cobertura menor a esto = "Se agota". */
    private int shortDays;
    /** Cobertura mayor a esto = "Lento". */
    private int slowDays;
    private Summary summary;
    private List<Row> rows;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Summary {
        private int lines;
        private BigDecimal totalStock;
        private BigDecimal totalStockValue;
        private int outOfStockCount;
        private int soonOutCount;
        private int normalCount;
        private int slowCount;
        private BigDecimal slowValue;
        private int noMovementCount;
        /** Valor de lo que está parado: existencia de las líneas sin movimiento. */
        private BigDecimal noMovementValue;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Row {
        private String key;
        private String code;
        private String name;
        /** Color del producto (solo productos). */
        private String variant;
        /** Herraje NUEVO / VIEJO / marca (solo productos). */
        private String hardware;
        /** Unidad de medida (solo materiales). */
        private String unit;
        private BigDecimal stock;
        /** Vendido (producto, neto de anulaciones) o consumido (material) en la ventana. */
        private BigDecimal moved;
        private BigDecimal dailyRate;
        /** Días que alcanza el stock al ritmo actual; null si no hubo movimiento. */
        private BigDecimal coverageDays;
        /** SIN_STOCK, SE_AGOTA, NORMAL, LENTO o SIN_MOVIMIENTO. */
        private String status;
        /** Precio de venta (producto) o costo unitario (material). */
        private BigDecimal unitValue;
        private BigDecimal stockValue;
        private LocalDate lastMovementDate;
        /** Kioscos con existencia de la línea (solo productos). */
        private Integer locationsWithStock;
        private Integer min;
        private Integer max;
    }
}
