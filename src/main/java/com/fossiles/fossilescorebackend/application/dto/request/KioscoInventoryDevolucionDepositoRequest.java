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
public class KioscoInventoryDevolucionDepositoRequest {

    @NotNull(message = "El producto es obligatorio.")
    private Long productId;

    private Long colorId;

    @NotNull(message = "La cantidad es obligatoria.")
    @Min(value = 1, message = "La cantidad debe ser mayor a cero.")
    private Integer quantity;

    /** Talla cuando el producto maneja inventario por talla (cinchos FOSS). */
    private String sizeKey;

    /** Número de boleta física de respaldo (devolución a bodega). */
    @NotBlank(message = "Debes indicar el número de boleta de devolución a bodega.")
    private String physicalSlipNumber;

    /** Motivo u observación del movimiento. */
    private String reason;

    private Long referenceId;

    /** Conteo físico al que se asocia la salida (aunque el reintegro sea posterior al period_to). */
    private Long physicalCountId;

    private Long userId;
}
