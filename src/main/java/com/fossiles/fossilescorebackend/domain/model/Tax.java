package com.fossiles.fossilescorebackend.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Tax {
    private Long id;
    private String code;
    private String name;
    private BigDecimal percentage;
}

