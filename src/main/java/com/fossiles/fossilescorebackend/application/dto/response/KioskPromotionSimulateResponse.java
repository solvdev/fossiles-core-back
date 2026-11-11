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
public class KioskPromotionSimulateResponse {
    /** true = la promoción simulada aplica a este carrito. */
    private Boolean applies;
    /** Por qué no aplica (fechas, kiosco, días, horario, pago, mínimos...). */
    private List<String> reasons;
    /** Avisos que no impiden calcular, p.ej. "Está en borrador: el POS todavía no la usa". */
    private List<String> warnings;
    private BigDecimal subtotal;
    private BigDecimal discountAmount;
    private BigDecimal totalAmount;
    /** Lo que el POS cobraría HOY con las promociones aprobadas (para comparar). */
    private BigDecimal currentDiscountAmount;
    private String currentPromotionName;
    private List<Line> lines;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Line {
        private Integer index;
        private Long productId;
        private String productCode;
        private String productName;
        private Long colorId;
        private String colorName;
        private String size;
        private String audience;
        private String categoryName;
        private BigDecimal quantity;
        private BigDecimal unitPrice;
        private BigDecimal lineTotal;
        private BigDecimal discountAmount;
        private BigDecimal totalAmount;
        /** false = empaque: nunca lleva descuento. */
        private Boolean eligible;
        private String note;
    }
}
