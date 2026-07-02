package com.fossiles.fossilescorebackend.application.dto.request;

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
    private String discountType; // PERCENT | FIXED | COMBO | TIERED_PERCENT
    private BigDecimal discountValue;
    private Integer comboBuyQty;
    private Integer comboPayQty;
    private Long kioskLocationId;
    /** DAMA o CABALLERO; vacío = todas las líneas */
    private String audienceCategory;
    private LocalDate startDate;
    private LocalDate endDate;
    private Boolean active;
    private List<KioskPromotionTierRequest> tiers;
}
