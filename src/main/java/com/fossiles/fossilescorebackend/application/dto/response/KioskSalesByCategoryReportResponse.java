package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskSalesByCategoryReportResponse {
    private LocalDate from;
    private LocalDate to;

    @Builder.Default
    private List<Row> rows = new ArrayList<>();

    private Totals totals;

    /** Solo con {@code compare=true}: los días inmediatamente anteriores, con la misma duración. */
    private LocalDate previousFrom;
    private LocalDate previousTo;
    /** Mismo reporte para el periodo anterior (sin su propio {@code previous}). */
    private KioskSalesByCategoryReportResponse previous;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Row {
        private Long categoryId;
        private String categoryName;
        /** Null si el producto no es billetera ni cincho. */
        private String subgroupCode;
        /** Null si el producto no es billetera ni cincho. */
        private String subgroupLabel;
        /** Cantidad neta. NUMERIC(12,3); se omite el cero a la derecha. */
        private BigDecimal units;
        /** Cobrado con IVA incluido, escala 2. */
        private BigDecimal totalAmount;
        /** totalAmount / 1.12, HALF_UP a 2 decimales. Se calcula sobre el total de la fila. */
        private BigDecimal totalAmountNoIva;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Totals {
        /** Piezas de producto: no incluye bolsas ni cajas de empaque (subgrupo EMPAQUE). */
        private BigDecimal units;
        /** Todo lo cobrado, empaque incluido (cuadra con caja y Finanzas). */
        private BigDecimal totalAmount;
        /** totalAmount / 1.12 una sola vez. No es la suma de las filas ya redondeadas. */
        private BigDecimal totalAmountNoIva;
        /** Unidades de empaque (bolsas y cajas), aparte de {@code units}. */
        private BigDecimal packagingUnits;
        /** Cobrado por empaque con IVA; ya está dentro de {@code totalAmount}. */
        private BigDecimal packagingAmount;
    }
}
