package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayrollIncidentResponse {
    private Long id;
    private Long employeeId;
    private String employeeName;
    /** PLANTA, KIOSCOS o ADMINISTRACION. */
    private String section;
    private String kind;
    private LocalDate dateFrom;
    private LocalDate dateTo;
    private BigDecimal hours;
    private BigDecimal hourlyRateOverride;
    private String holidayName;
    private String note;
    private LocalDateTime createdAt;
}
