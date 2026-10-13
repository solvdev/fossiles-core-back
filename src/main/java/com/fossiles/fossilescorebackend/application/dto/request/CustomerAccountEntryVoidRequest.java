package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CustomerAccountEntryVoidRequest {

    @NotBlank(message = "voidReason is required")
    @Size(max = 500)
    private String voidReason;

    /**
     * Cargo activo que recibe los pagos, notas de crédito y devoluciones del cargo que se anula.
     * Obligatorio si ese cargo los tiene. Puede ser de otra orden u otro tipo (OPV/OPC).
     */
    private Long reassignToChargeId;
}
