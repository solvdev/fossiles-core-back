package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Novedad de uno o varios colaboradores: horas extra, feriado trabajado, ausencia sin goce o vacaciones. */
@Data
public class PayrollIncidentRequest {
    /** A quiénes aplica: se registra una novedad por persona, todas o ninguna. */
    @NotEmpty
    private List<Long> employeeIds;

    /** HORA_EXTRA, FERIADO_TRABAJADO, AUSENCIA o VACACIONES. */
    @NotBlank
    private String kind;

    /** El día (horas extra y feriado) o el primer día (ausencia y vacaciones). */
    @NotNull
    private LocalDate dateFrom;

    /** Último día de una ausencia o de unas vacaciones; en horas extra y feriado se ignora. */
    private LocalDate dateTo;

    /** Horas extra (obligatorias) u horas trabajadas en el feriado (vacío: la jornada completa). */
    private BigDecimal hours;

    /** Pago por hora distinto del que calculan los parámetros; vacío usa el recargo vigente. */
    private BigDecimal hourlyRate;

    @Size(max = 300)
    private String note;
}
