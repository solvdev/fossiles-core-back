package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpcShipmentGenerateRequest {

    @NotBlank(message = "Destino / dirección es obligatorio")
    private String destinationAddress;

    /** Kiosko opcional; si se indica, numeración y tránsito como distribución. */
    private Long locationId;

    private String notes;

    /** Fecha impresa en documento (YYYY-MM-DD); se persiste en notes como DOCUMENT_DATE:… */
    private String documentDate;

    private List<ProductShipmentRequest.PackingItemRequest> packingItems;
}
