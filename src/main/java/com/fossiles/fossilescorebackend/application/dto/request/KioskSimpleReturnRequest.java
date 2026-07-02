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
public class KioskSimpleReturnRequest {
    private Long kioskLocationId;

    @NotNull(message = "La venta original es obligatoria.")
    private Long originalSaleId;

    @NotNull(message = "La línea devuelta es obligatoria.")
    private Long originalSaleItemId;

    @Positive(message = "La cantidad devuelta debe ser mayor a cero.")
    private BigDecimal returnedQuantity;

    @NotNull(message = "Debes indicar si el producto es apto para reventa.")
    private Boolean apto;

    private String reason;
    private String observations;
}
