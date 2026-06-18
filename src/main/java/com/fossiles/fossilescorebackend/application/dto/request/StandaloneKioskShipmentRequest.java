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
}
