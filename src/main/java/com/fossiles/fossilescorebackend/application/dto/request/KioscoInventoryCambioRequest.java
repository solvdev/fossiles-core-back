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
public class KioscoInventoryCambioRequest {

    /** Producto que el cliente devuelve al kiosko (entra al stock). */
    @NotNull(message = "El producto devuelto es obligatorio.")
    private Long returnedProductId;

    private Long returnedColorId;

    /** Producto que el kiosko entrega al cliente (sale del stock). */
    @NotNull(message = "El producto entregado es obligatorio.")
    private Long givenProductId;

    private Long givenColorId;

    @NotNull(message = "La cantidad es obligatoria.")
    @Min(value = 1, message = "La cantidad debe ser mayor a cero.")
    private Integer quantity;

    private Long referenceId;

    private String reason;

    private Long userId;
}
