package com.fossiles.fossilescorebackend.application.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioscoPhysicalCountItemUpsertRequest {
    private Long productId;
    private Long colorId;
    /** Conteo por ubicacion: claves fijas V1..V7, E, BO. */
    private Map<String, Integer> counts;
    /** Conteo fisico por talla (cinchos). */
    private Map<String, Integer> physicalSizes;
    /** Conteo FOSS por ubicacion cincho (E vitrina, BO bodega) y talla. */
    private Map<String, Map<String, Integer>> physicalSizesByLocation;
    /** Observacion para filas sin desglose por talla. */
    private String observation;
    /** Observaciones por talla (cinchos expandidos). */
    private Map<String, String> sizeObservations;
}
