package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioscoInventoryAnularFacturaRequest {

    @NotNull(message = "La factura es obligatoria.")
    private Long invoiceId;

    @NotNull(message = "El producto es obligatorio.")
    private Long productId;

    private Long colorId;

    @NotNull(message = "La cantidad es obligatoria.")
    @Min(value = 1, message = "La cantidad debe ser mayor a cero.")
    private Integer quantity;

    @NotBlank(message = "El motivo es obligatorio.")
    private String reason;

    @NotNull(message = "Debes indicar si el producto salió del kiosko.")
    private Boolean productLeftKiosk;

    private Long userId;
}
