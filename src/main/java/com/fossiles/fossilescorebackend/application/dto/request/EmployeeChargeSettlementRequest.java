package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Decisión de Contabilidad sobre cómo se liquida un cargo a colaborador. */
@Data
public class EmployeeChargeSettlementRequest {
    /** DESCUENTO, TRANSFERENCIA o EFECTIVO. */
    @NotBlank
    private String settlementMethod;
}
