package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Conteo fisico de inventario kiosco por periodo: Kardex agrupado por categoria de producto,
 * cruzado contra el conteo fisico por ubicacion (V1..V7, E, BO) con la diferencia resultante.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioscoPhysicalCountReportResponse {
    private Long id;
    private Long locationId;
    private String locationCode;
    private String locationName;
    private LocalDate periodFrom;
    private LocalDate periodTo;
    private String status;
    private String notes;
    private Long generatedBy;
    private String generatedByName;
    private LocalDateTime generatedAt;
    private Long reviewedBy;
    private String reviewedByName;
    private LocalDateTime reviewedAt;
    private Long closedBy;
    private String closedByName;
    private LocalDateTime closedAt;
    private int maxAbsDiff;
    private List<KioscoPhysicalCountCategoryGroup> categories;
    private KioscoPhysicalCountRow totalGeneral;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KioscoPhysicalCountCategoryGroup {
        private Long categoryId;
        private String categoryName;
        private List<KioscoPhysicalCountRow> rows;
        private KioscoPhysicalCountRow subtotal;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KioscoPhysicalCountRow {
        private Long productId;
        private String productCode;
        private String productName;
        private Long colorId;
        private String colorName;
        /** DAMA, CABALLERO o UNISEX */
        private String audienceCategory;
        /** CASUAL o REVERSIBLE */
        private String cinchoType;
        /** true si el código del producto es empaque SUM- */
        private boolean packaging;
        /** Desglose por talla en inventario kiosko (cinchos). */
        private Map<String, Integer> systemSizes;
        /** Conteo fisico capturado por talla (cinchos). */
        private Map<String, Integer> physicalSizes;
        /** Texto legible del desglose por talla, p. ej. "28: 2 · 30: 5". */
        private String sizesSummary;
        /** Texto legible del conteo fisico por talla. */
        private String physicalSizesSummary;
        private int inventarioInicial;
        private int comprasAjustes;
        private int anulacionCompras;
        private int entradas;
        private int ventas;
        private int anulacionVenta;
        private int salida;
        private int inventarioFinal;
        /** Conteo fisico por ubicacion: claves fijas V1..V7, E, BO. */
        private Map<String, Integer> counts;
        private int total;
        /** inventarioFinal (sistema) - total (fisico). */
        private int diferencia;
    }
}
