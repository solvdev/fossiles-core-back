package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StandaloneKioskShipmentRequest {

    @NotNull(message = "El kiosko destino es obligatorio")
    private Long locationId;
    /**
     * Opcional: distribución existente donde meter el envío. Con ella el envío ya no es directo y sale de Bodega PT.
     * Sin ella el envío es directo a kiosco: no tiene distribución y sale de Devoluciones.
     */
    private Long distributionId;

    /**
     * Se conserva por compatibilidad y se ignora: sin distributionId el envío siempre queda sin distribución
     * (withoutDistribution = true).
     */
    private Boolean withoutDistribution;

    private String notes;

    /** Fecha del documento (YYYY-MM-DD). */
    private String documentDate;

    @Valid
    private List<ProductShipmentRequest.ProductShipmentDetailRequest> products;

    @Valid
    private List<ProductShipmentRequest.PackingItemRequest> packingItems;

    /**
     * Si true (por defecto), solo crea el envío confirmado sin validar stock ni enviar.
     * Si false, valida stock, descuenta PT y deja SENT (comportamiento legado).
     */
    private Boolean confirmOnly;

    /** Solo con confirmOnly=false: autoriza tomar el faltante de la otra cubeta al enviar (ver sendShipment). */
    private Boolean takeShortageFromFallback;
}
