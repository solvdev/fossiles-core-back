package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Versión de un parámetro legal de planilla con su fecha de vigencia. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayrollParameterResponse {
    private Long id;
    private String code;
    private BigDecimal value;
    private LocalDate effectiveFrom;
    private String description;
    /** Es la versión que aplica hoy para su código. */
    private boolean current;
}
