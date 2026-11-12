package com.fossiles.fossilescorebackend.application.dto.request;

import com.fossiles.fossilescorebackend.application.service.promotion.PromotionRuleConfig;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskPromotionRequest {
    private String name;
    private String description;
    private String discountType; // PERCENT | FIXED | COMBO | TIERED_PERCENT | RULES
    private BigDecimal discountValue;
    private Integer comboBuyQty;
    private Integer comboPayQty;
    /** Kiosco único (formato viejo); se usa solo si no viene {@link #kioskLocationIds}. */
    private Long kioskLocationId;
    /** Kioscos donde aplica; null o vacío = todos. */
    private List<Long> kioskLocationIds;
    /** DAMA o CABALLERO; vacío = todas las líneas */
    private String audienceCategory;
    private LocalDate startDate;
    private LocalDate endDate;
    private Boolean active;
    private List<KioskPromotionTierRequest> tiers;
    /** Regla dinámica para discountType = RULES. */
    private PromotionRuleConfig ruleConfig;
}
