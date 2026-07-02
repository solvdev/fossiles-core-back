package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskPromotionTierResponse {
    private String audienceCategory;
    private Long categoryId;
    private String categoryName;
    private BigDecimal discountValue;
}
