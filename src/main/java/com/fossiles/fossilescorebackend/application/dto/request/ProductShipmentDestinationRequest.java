package com.fossiles.fossilescorebackend.application.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductShipmentDestinationRequest {
    /** Kiosko / ubicación destino. Si viene, actualiza location_id. */
    private Long locationId;
    /**
     * Destino textual (línea DESTINO: en notas). Null = no tocar;
     * string vacío = quitar línea DESTINO.
     */
    private String destinationAddress;
}
