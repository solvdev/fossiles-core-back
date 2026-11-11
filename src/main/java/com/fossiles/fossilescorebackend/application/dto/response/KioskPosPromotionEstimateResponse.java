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
public class KioskPosPromotionEstimateResponse {
    private BigDecimal subtotal;
    private BigDecimal discountAmount;
    private BigDecimal totalAmount;
    private Boolean autoApplied;
    private Long promotionId;
    private String promotionName;
    private BigDecimal manualDiscountPercent;
    /** Descuento por línea (mismo orden que los items enviados); solo promociones RULES. */
    private List<KioskPromotionLineDiscountResponse> lines;
    /** Promociones vigentes que casi aplican, p.ej. "Solo pagando con Tarjeta". */
    private List<String> hints;
}
