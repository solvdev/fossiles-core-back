package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InternalShipmentRequestSlipPrintRequest {

    /** Cantidad de boletas de solicitud a generar para el talonario (default 50). */
    @Min(value = 1, message = "Debe imprimir al menos 1 boleta.")
    @Max(value = 500, message = "No se pueden generar más de 500 boletas por lote.")
    @Builder.Default
    private Integer quantity = 50;
}
