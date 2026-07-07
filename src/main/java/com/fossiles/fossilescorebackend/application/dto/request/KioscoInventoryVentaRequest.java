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
public class KioscoInventoryVentaRequest {

    @NotNull(message = "El producto es obligatorio.")
    private Long productId;

    private Long colorId;

    @NotNull(message = "La cantidad es obligatoria.")
    @Min(value = 1, message = "La cantidad debe ser mayor a cero.")
    private Integer quantity;

    @NotNull(message = "La referencia de factura es obligatoria.")
    private Long invoiceId;

    private Long userId;

    /** Talla para cinchos FOSS cuando el stock tiene desglose por talla. */
    private String sizeKey;
}
