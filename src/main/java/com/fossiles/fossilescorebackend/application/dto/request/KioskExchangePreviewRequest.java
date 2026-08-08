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

    /** Solo kiosko A15 (Miraflores): precio unitario cobrado/acreditado del producto que ingresa. */
    private BigDecimal returnedUnitPrice;

    /** Solo kiosko A15 (Miraflores): precio unitario del producto que egresa. */
    private BigDecimal givenUnitPrice;

    /**
     * Si el producto que ingresa se había vendido con descuento.
     * Con true/false se calcula el crédito desde precio de venta de catálogo ± %.
     */
    private Boolean returnedSoldWithDiscount;

    /** Porcentaje de descuento aplicado en la venta original del producto que ingresa (0–99). */
    private BigDecimal returnedDiscountPercent;
}
