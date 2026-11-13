package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/** Datos para preparar la planilla de un grupo en una fecha de pago. */
@Data
public class PayrollRunCreateRequest {
    /** PLANTA o ADMINISTRACION. */
    @NotBlank
    private String payGroup;
    /** Fecha programada del grupo (5/20 o 15/fin de mes), no la fecha corrida por feriado. */
    @NotNull
    private LocalDate scheduledDate;
    /** Opcional: fecha real de pago si difiere del día hábil anterior que da el calendario (p. ej. feria local). */
    private LocalDate payDate;
    @Size(max = 100)
    private String bankName;
    @Size(max = 500)
    private String notes;
}
