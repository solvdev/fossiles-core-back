package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

/** Fecha real de pago de una planilla: no después de la programada ni más de 10 días antes. */
@Data
public class PayrollPayDateRequest {
    @NotNull
    private LocalDate payDate;
}
