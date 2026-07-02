package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

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
    private Long kioskLocationId;
    private String audienceCategory;
    private LocalDate startDate;
    private LocalDate endDate;
    private Boolean active;
}
