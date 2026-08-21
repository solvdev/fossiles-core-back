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

    /**
     * Cantidad única (cambio sin diferencia de unidades).
     * Si se envían {@link #returnedQuantity} y {@link #givenQuantity}, se usan esas.
     */
    @Min(value = 1, message = "La cantidad debe ser mayor a cero.")
    private Integer quantity;

    /** Unidades que entran (producto devuelto). Cambio con diferencia. */
    @Min(value = 1, message = "La cantidad devuelta debe ser mayor a cero.")
    private Integer returnedQuantity;

    /** Unidades que salen (producto entregado). Cambio con diferencia. */
    @Min(value = 1, message = "La cantidad entregada debe ser mayor a cero.")
    private Integer givenQuantity;

    private Long referenceId;

    private String reason;

    private Long userId;

    private String physicalSlipNumber;

    private String returnedSizeKey;

    private String givenSizeKey;

    private String returnedHardwareCondition;

    private String givenHardwareCondition;
}
