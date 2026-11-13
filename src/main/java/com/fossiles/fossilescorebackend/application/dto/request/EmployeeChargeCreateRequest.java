package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Cargo manual a un colaborador: descuadre de inventario, faltante de caja, daño u otro concepto. */
@Data
public class EmployeeChargeCreateRequest {
    @NotNull
    private Long employeeId;

    /** DESCUADRE_INVENTARIO, FALTANTE_CAJA, DANO u OTRO. */
    @NotBlank
    private String concept;

    @NotNull
    @DecimalMin(value = "0.01", message = "El monto debe ser mayor que cero.")
    private BigDecimal amount;

    /** Qué pasó y cómo se calculó el monto. */
    @NotBlank
    @Size(max = 500)
    private String reason;

    /** Kiosco, conteo de inventario, caja o documento al que se refiere. */
    @Size(max = 100)
    private String reference;

    /** Por defecto, hoy. No puede ser futura. */
    private LocalDate chargedOn;

    /** El colaborador aceptó el cargo. Es obligatorio: sin su aceptación no se registra. */
    @NotNull
    private Boolean employeeAuthorized;

    /** Dónde está la constancia (carta firmada, fecha, quién la guarda). */
    @Size(max = 200)
    private String authorizationNote;

    /** Tope que la planilla descuenta en cada pago; vacío: lo que alcance. */
    @DecimalMin(value = "0.01", message = "La cuota debe ser mayor que cero.")
    private BigDecimal installmentAmount;
}
