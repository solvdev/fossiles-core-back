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
public class KioskExchangePreviewRequest {
    private Long kioskLocationId;

    @NotNull(message = "La venta original es obligatoria.")
    private Long originalSaleId;

    @NotNull(message = "La línea devuelta es obligatoria.")
    private Long originalSaleItemId;

    @NotNull(message = "El producto nuevo es obligatorio.")
    private Long givenProductId;

    private Long givenColorId;
    private String givenSize;

    @Positive(message = "La cantidad del producto nuevo debe ser mayor a cero.")
    private BigDecimal givenQuantity;

    @Positive(message = "La cantidad devuelta debe ser mayor a cero.")
    private BigDecimal returnedQuantity;
}
