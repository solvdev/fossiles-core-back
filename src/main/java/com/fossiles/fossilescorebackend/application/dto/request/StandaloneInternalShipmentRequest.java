package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StandaloneInternalShipmentRequest {

    @NotBlank(message = "El nombre del colaborador es obligatorio")
    private String recipientName;

    private String recipientPhone;

    private String recipientTaxId;

    private String notes;

    /** Fecha del documento (YYYY-MM-DD). */
    private String documentDate;

    @Builder.Default
    private boolean applyCollaboratorDiscount = true;

    @NotEmpty(message = "Debe incluir al menos un producto")
    @Valid
    private List<ProductShipmentRequest.ProductShipmentDetailRequest> products;
}
