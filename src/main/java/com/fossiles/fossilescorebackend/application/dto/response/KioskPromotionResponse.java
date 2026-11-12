package com.fossiles.fossilescorebackend.application.dto.response;

import com.fossiles.fossilescorebackend.application.service.promotion.PromotionRuleConfig;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskPromotionResponse {
    private Long id;
    private String name;
    private String description;
    private String discountType;
    private BigDecimal discountValue;
    private Integer comboBuyQty;
    private Integer comboPayQty;
    /** Kiosco único (solo si la promoción es de un solo kiosco; compatibilidad). */
    private Long kioskLocationId;
    /** Kioscos donde aplica; vacío = todos. */
    private List<Long> kioskLocationIds;
    private String audienceCategory;
    private LocalDate startDate;
    private LocalDate endDate;
    private Boolean active;
    private List<KioskPromotionTierResponse> tiers;
    private PromotionRuleConfig ruleConfig;
    /** DRAFT (borrador, solo simulador) o APPROVED (vigente en el POS). */
    private String status;
    private LocalDateTime approvedAt;
    private Long approvedBy;
    private String approvedByName;
    private LocalDateTime updatedAt;
}
