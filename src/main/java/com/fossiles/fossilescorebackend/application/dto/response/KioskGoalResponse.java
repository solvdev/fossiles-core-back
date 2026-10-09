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
public class KioskGoalResponse {
    private Long kioskLocationId;
    private String kioskName;
    private Integer goalYear;
    private Integer goalMonth;
    private BigDecimal goalAmount;
}
