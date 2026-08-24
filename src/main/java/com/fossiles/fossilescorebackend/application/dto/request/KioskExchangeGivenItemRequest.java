package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskExchangeGivenItemRequest {

    @NotNull(message = "El producto entregado es obligatorio.")
    private Long productId;

    private Long colorId;

    private String size;

    /** Herraje del producto a entregar (NUEVO/VIEJO) según inventario kiosco. */
    private String hardwareCondition;

    @Positive(message = "La cantidad entregada debe ser mayor a cero.")
    private BigDecimal quantity;

    /** Solo kiosko A15 (Miraflores): precio unitario de esta línea. */
    private BigDecimal unitPrice;
}
