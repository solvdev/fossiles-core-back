package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/** Día de pago de un grupo con su período y la planilla que ya tenga. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayrollPayDayResponse {
    private String payGroup;
    private LocalDate scheduledDate;
    /** Día hábil anterior cuando la fecha programada cae en fin de semana o feriado. */
    private LocalDate payDate;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private Long runId;
    private String runStatus;
}
