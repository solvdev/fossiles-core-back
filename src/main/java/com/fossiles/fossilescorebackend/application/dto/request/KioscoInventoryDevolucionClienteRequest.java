package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioscoInventoryDevolucionClienteRequest {

    @NotNull(message = "El producto es obligatorio.")
    private Long productId;

    private Long colorId;

    @NotNull(message = "La cantidad es obligatoria.")
    @Min(value = 1, message = "La cantidad debe ser mayor a cero.")
    private Integer quantity;

    @NotNull(message = "La factura original es obligatoria.")
    private Long originalInvoiceId;

    @NotNull(message = "Debes indicar si el producto es apto.")
    private Boolean apto;

    private Long userId;
}
