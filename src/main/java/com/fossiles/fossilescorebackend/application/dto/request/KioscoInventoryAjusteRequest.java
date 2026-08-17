package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioscoInventoryAjusteRequest {

    @NotNull(message = "El producto es obligatorio.")
    private Long productId;

    private Long colorId;

    /**
     * Modo absoluto (legacy / sincronizaciones): stock objetivo.
     * Si viene {@link #quantity} + {@link #direction}, se ignora.
     */
    @Min(value = 0, message = "La cantidad real no puede ser negativa.")
    private Integer realQuantity;

    /**
     * Modo relativo: unidades a sumar (INGRESO) o restar (EGRESO) sobre el stock actual.
     */
    @Min(value = 1, message = "La cantidad debe ser mayor a cero.")
    private Integer quantity;

    /** INGRESO o EGRESO. Requerido junto con {@link #quantity}. */
    private String direction;

    @NotBlank(message = "El motivo es obligatorio.")
    private String reason;

    private Long userId;

    /** Cinchos FOSS (modo absoluto): cantidad real por talla. */
    private Map<String, Integer> realSizes;

    /** Talla (modo relativo) cuando el stock tiene desglose por talla. */
    private String sizeKey;

    /** Herraje: NUEVO o VIEJO. */
    private String hardwareCondition;
}
