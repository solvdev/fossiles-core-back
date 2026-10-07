package com.fossiles.fossilescorebackend.infrastructure.persistence.projection;

import java.math.BigDecimal;

/** Ítem ligero de venta de kiosko para dashboards. */
public record KioskSaleItemRow(
        Long kioskSaleId,
        Long productId,
        String productCode,
        String productName,
        BigDecimal quantity,
        BigDecimal lineTotal
) {
}
