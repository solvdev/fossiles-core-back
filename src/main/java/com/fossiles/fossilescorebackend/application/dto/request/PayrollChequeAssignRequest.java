package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Primer número de la chequera y banco para numerar los cheques de una planilla aprobada. */
@Data
public class PayrollChequeAssignRequest {
    /** Primer número de la chequera; los siguientes se numeran en orden alfabético. Admite sufijo, p. ej. 99645508/GCF. */
    @NotBlank
    @Size(max = 30)
    private String firstChequeNumber;
    @Size(max = 100)
    private String bankName;
}
