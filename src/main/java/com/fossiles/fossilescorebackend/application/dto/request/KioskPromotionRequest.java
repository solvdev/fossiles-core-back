package com.fossiles.fossilescorebackend.application.dto.request;

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
public class KioskPromotionRequest {
    private String name;
    private String description;
    private String discountType; // PERCENT | FIXED | COMBO
    private BigDecimal discountValue;
    private Integer comboBuyQty;
    private Integer comboPayQty;
    private Long kioskLocationId;
    private LocalDate startDate;
    private LocalDate endDate;
    private Boolean active;
}
