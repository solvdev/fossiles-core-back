package com.fossiles.fossilescorebackend.infrastructure.persistence.projection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Cabecera ligera de venta de kiosko para dashboards (evita hidratar las ~50 columnas de la entidad). */
public record KioskSaleHeaderRow(
        Long id,
        String saleNumber,
        Long kioskLocationId,
        LocalDate saleDate,
        LocalDateTime soldAt,
        String paymentMethod,
        String status,
        BigDecimal totalItems,
        BigDecimal totalAmount,
        Boolean testSale
) {
}
