package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Motivo para anular una planilla en borrador o aprobada. */
@Data
public class PayrollAnnulRequest {
    @NotBlank
    @Size(max = 500)
    private String reason;
}
