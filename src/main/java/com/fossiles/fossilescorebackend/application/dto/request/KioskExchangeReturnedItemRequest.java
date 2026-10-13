package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Línea de la factura original que el cliente devuelve en un cambio (N líneas por boleta). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskExchangeReturnedItemRequest {

    @NotNull(message = "La línea de la factura devuelta es obligatoria.")
    private Long originalSaleItemId;

    /** Cantidad devuelta de esa línea; por defecto, todo lo vendido. */
    @Positive(message = "La cantidad devuelta debe ser mayor a cero.")
    private BigDecimal quantity;

    /** Solo kiosko A15 (Miraflores): precio unitario acreditado de esta línea. */
    private BigDecimal unitPrice;
}
