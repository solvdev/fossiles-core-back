package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Descuento que lleva una línea del carrito (promociones RULES). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskPromotionLineDiscountResponse {
    /** Posición de la línea en la lista de items enviada. */
    private Integer index;
    private Long productId;
    private Long colorId;
    private String size;
    private BigDecimal discountAmount;
    /** Cómo se ganó el descuento, p.ej. "Pos. 2 · 30%". */
    private String note;
}
