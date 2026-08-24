package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.util.List;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class KioskExchangePreviewRequest {
    private Long kioskLocationId;

    private Long originalSaleId;

    private Long originalSaleItemId;

    /**
     * Productos a entregar (1→N). Si viene con ítems, tiene prioridad sobre los campos escalares
     * {@code givenProductId}/{@code givenQuantity}/…
     */
    @Valid
    private List<KioskExchangeGivenItemRequest> givenItems;

    /** Compat 1→1: producto entregado único (usado si {@link #givenItems} está vacío). */
    private Long givenProductId;

    private Long givenColorId;
    private String givenSize;
    /** Herraje del producto a entregar (NUEVO/VIEJO) según inventario kiosco. */
    private String givenHardwareCondition;

    private Long returnedProductId;
    private Long returnedColorId;
    private String returnedSize;

    @Positive(message = "La cantidad del producto nuevo debe ser mayor a cero.")
    private BigDecimal givenQuantity;

    @Positive(message = "La cantidad devuelta debe ser mayor a cero.")
    private BigDecimal returnedQuantity;

    /** Solo kiosko A15 (Miraflores): precio unitario cobrado/acreditado del producto que ingresa. */
    private BigDecimal returnedUnitPrice;

    /** Solo kiosko A15 (Miraflores): precio unitario del producto que egresa (modo 1→1). */
    private BigDecimal givenUnitPrice;

    /**
     * Si el producto que ingresa se había vendido con descuento.
     * Con true/false se calcula el crédito desde precio de venta de catálogo ± %.
     */
    private Boolean returnedSoldWithDiscount;

    /** Porcentaje de descuento aplicado en la venta original del producto que ingresa (0–99). */
    private BigDecimal returnedDiscountPercent;
}
