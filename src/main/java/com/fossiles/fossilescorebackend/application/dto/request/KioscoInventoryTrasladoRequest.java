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
public class KioscoInventoryTrasladoRequest {

    @NotNull(message = "La ubicación origen es obligatoria.")
    private Long locationOriginId;

    @NotNull(message = "La ubicación destino es obligatoria.")
    private Long locationDestinationId;

    private Long userId;

    private String physicalSlipNumber;

    /**
     * Ítem único (compatibilidad). Si {@link #items} viene poblado, se ignora.
     */
    private Long productId;
    private Long colorId;
    @Min(value = 1, message = "La cantidad debe ser mayor a cero.")
    private Integer quantity;
    /** Talla FOSS / cincho con breakdown. */
    private String sizeKey;

    /** Herraje: NUEVO o VIEJO (ítem único). */
    private String hardwareCondition;

    /** Varias líneas producto+color+talla+cantidad en un solo traslado / boleta. */
    @Valid
    private List<Item> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        @NotNull(message = "El producto es obligatorio.")
        private Long productId;
        private Long colorId;
        @NotNull(message = "La cantidad es obligatoria.")
        @Min(value = 1, message = "La cantidad debe ser mayor a cero.")
        private Integer quantity;
        private String sizeKey;
        /** Herraje: NUEVO o VIEJO. */
        private String hardwareCondition;
    }
}
