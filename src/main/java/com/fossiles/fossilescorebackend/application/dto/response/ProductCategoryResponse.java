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
public class ProductCategoryResponse {
    private Long id;
    private String code;
    private String name;
    private BigDecimal hourlyCost;
    private BigDecimal payrollTotal;
    private BigDecimal availableHours;
    private Integer numberOfTables;
}

