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
public class KioscoInventoryTrasladoRequest {

    @NotNull(message = "La ubicación origen es obligatoria.")
    private Long locationOriginId;

    @NotNull(message = "La ubicación destino es obligatoria.")
    private Long locationDestinationId;

    @NotNull(message = "El producto es obligatorio.")
    private Long productId;

    private Long colorId;

    @NotNull(message = "La cantidad es obligatoria.")
    @Min(value = 1, message = "La cantidad debe ser mayor a cero.")
    private Integer quantity;

    private Long userId;
}
