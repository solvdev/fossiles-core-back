package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
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
public class KioscoInventoryCambioRequest {

    /** Producto que el cliente devuelve al kiosko (entra al stock). */
    @NotNull(message = "El producto devuelto es obligatorio.")
    private Long returnedProductId;

    private Long returnedColorId;

    /**
     * Productos que el kiosko entrega (1→N). Si viene con ítems, tiene prioridad sobre
     * {@link #givenProductId} / cantidades escalares.
     */
    @Valid
    private List<GivenLine> givenItems;

    /** Compat 1→1: producto entregado único. */
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

    /**
     * Si true (default), valida que Σ(salePrice×qty) entregado ≥ salePrice×qty del devuelto.
     * La boleta POS puede desactivar esto porque ya valida/cobra la diferencia.
     */
    private Boolean validateNonNegativePriceDifference;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GivenLine {
        @NotNull(message = "El producto entregado es obligatorio.")
        private Long productId;

        private Long colorId;

        @Min(value = 1, message = "La cantidad entregada debe ser mayor a cero.")
        private Integer quantity;

        private String sizeKey;

        private String hardwareCondition;
    }
}
