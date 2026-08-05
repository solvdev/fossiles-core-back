package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class KioskExchangePreviewRequest {
    private Long kioskLocationId;

    private Long originalSaleId;

    private Long originalSaleItemId;

    private Long givenProductId;

    private Long givenColorId;
    private String givenSize;

    private Long returnedProductId;
    private Long returnedColorId;
    private String returnedSize;

    @Positive(message = "La cantidad del producto nuevo debe ser mayor a cero.")
    private BigDecimal givenQuantity;

    @Positive(message = "La cantidad devuelta debe ser mayor a cero.")
    private BigDecimal returnedQuantity;
}
