package com.fossiles.fossilescorebackend.application.util;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSaleItemEntity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Precio realmente cobrado en una línea de venta POS.
 * Misma regla que usaba {@code KioskExchangeService.computeEffectivePaidUnitPrice}:
 * el descuento de cabecera se reparte solo entre líneas que no son empaque;
 * una promoción RULES trae el descuento ya guardado en la línea.
 */
public final class KioskSalePricing {

    private KioskSalePricing() {
    }

    /**
     * Precio unitario pagado. El descuento de la venta se reparte solo entre productos
     * (empaques SUM sin descuento).
     */
    public static BigDecimal effectivePaidUnitPrice(
            KioskSaleEntity sale,
            KioskSaleItemEntity item,
            List<KioskSaleItemEntity> allItems
    ) {
        if (item == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal qty = item.getQuantity() != null && item.getQuantity().compareTo(BigDecimal.ZERO) > 0
                ? item.getQuantity()
                : BigDecimal.ONE;
        if (ProductCinchoType.isPackagingProductCode(item.getProductCode())) {
            return packagingLineAmountNoDiscount(item).divide(qty, 2, RoundingMode.HALF_UP);
        }
        BigDecimal lineSubtotal = item.getLineTotal() != null
                ? item.getLineTotal()
                : safeAmount(item.getUnitPrice()).multiply(qty);
        // Promoción dinámica (RULES): cada línea guarda su propio descuento (p.ej. la cara 15%, la barata 30%).
        List<KioskSaleItemEntity> saleItems = allItems != null && !allItems.isEmpty()
                ? allItems
                : (sale != null && sale.getItems() != null ? List.copyOf(sale.getItems()) : List.of());
        boolean perLineDiscount = item.getDiscountAmount() != null
                || saleItems.stream().anyMatch(row -> row != null && row.getDiscountAmount() != null);
        if (perLineDiscount) {
            BigDecimal linePaid = lineSubtotal.subtract(safeAmount(item.getDiscountAmount())).max(BigDecimal.ZERO);
            return linePaid.divide(qty, 2, RoundingMode.HALF_UP);
        }
        BigDecimal discount = sale != null && sale.getDiscountAmount() != null
                ? sale.getDiscountAmount()
                : BigDecimal.ZERO;
        BigDecimal discountBase = BigDecimal.ZERO;
        for (KioskSaleItemEntity saleItem : saleItems) {
            if (saleItem == null || ProductCinchoType.isPackagingProductCode(saleItem.getProductCode())) {
                continue;
            }
            BigDecimal itemQty = saleItem.getQuantity() != null && saleItem.getQuantity().compareTo(BigDecimal.ZERO) > 0
                    ? saleItem.getQuantity() : BigDecimal.ONE;
            BigDecimal itemSub = saleItem.getLineTotal() != null
                    ? saleItem.getLineTotal()
                    : safeAmount(saleItem.getUnitPrice()).multiply(itemQty);
            discountBase = discountBase.add(itemSub);
        }
        if (discountBase.compareTo(BigDecimal.ZERO) <= 0) {
            discountBase = lineSubtotal;
        }
        if (discountBase.compareTo(BigDecimal.ZERO) <= 0) {
            return safeAmount(item.getUnitPrice()).setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal lineDiscountShare = discount.multiply(lineSubtotal)
                .divide(discountBase, 6, RoundingMode.HALF_UP);
        BigDecimal linePaid = lineSubtotal.subtract(lineDiscountShare).max(BigDecimal.ZERO);
        return linePaid.divide(qty, 2, RoundingMode.HALF_UP);
    }

    /**
     * Importe cobrado de cada línea, escala 2. El descuento de cabecera se reparte por
     * {@code line_total} solo entre líneas que no son empaque. El residuo de redondeo
     * (para que la suma cuadre al centavo con el descuento) queda en la línea de mayor importe.
     * Si alguna línea trae {@code discount_amount} (promo RULES), se usa ese monto y no la cabecera.
     */
    public static Map<Long, BigDecimal> paidLineAmounts(KioskSaleEntity sale, List<KioskSaleItemEntity> items) {
        List<KioskSaleItemEntity> lines = items == null ? List.of() : items.stream().filter(row -> row != null && row.getId() != null).toList();
        Map<Long, BigDecimal> paid = new LinkedHashMap<>();
        if (lines.isEmpty()) {
            return paid;
        }
        boolean perLine = lines.stream().anyMatch(row -> row.getDiscountAmount() != null);
        if (perLine) {
            for (KioskSaleItemEntity line : lines) {
                BigDecimal amount = lineSubtotal(line).subtract(safeAmount(line.getDiscountAmount())).max(BigDecimal.ZERO);
                paid.put(line.getId(), amount.setScale(2, RoundingMode.HALF_UP));
            }
            return paid;
        }
        BigDecimal base = BigDecimal.ZERO;
        List<KioskSaleItemEntity> eligible = new ArrayList<>();
        for (KioskSaleItemEntity line : lines) {
            if (ProductCinchoType.isPackagingProductCode(line.getProductCode())) {
                continue;
            }
            eligible.add(line);
            base = base.add(lineSubtotal(line));
        }
        base = base.setScale(2, RoundingMode.HALF_UP);
        BigDecimal discount = sale != null && sale.getDiscountAmount() != null
                ? sale.getDiscountAmount().max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        if (discount.compareTo(base) > 0) {
            discount = base;
        }
        Map<Long, BigDecimal> shareById = new HashMap<>();
        BigDecimal allocated = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        KioskSaleItemEntity largest = null;
        for (KioskSaleItemEntity line : eligible) {
            BigDecimal sub = lineSubtotal(line);
            BigDecimal share = base.signum() == 0
                    ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                    : discount.multiply(sub).divide(base, 2, RoundingMode.HALF_UP);
            shareById.put(line.getId(), share);
            allocated = allocated.add(share);
            if (isLarger(line, largest)) {
                largest = line;
            }
        }
        if (largest != null) {
            BigDecimal remainder = discount.subtract(allocated);
            shareById.put(largest.getId(), shareById.get(largest.getId()).add(remainder));
        }
        for (KioskSaleItemEntity line : lines) {
            BigDecimal share = shareById.getOrDefault(line.getId(), BigDecimal.ZERO);
            paid.put(line.getId(), lineSubtotal(line).subtract(share).setScale(2, RoundingMode.HALF_UP));
        }
        return paid;
    }

    /** Parte del importe de la línea que corresponde a {@code returnedQty}. Una devolución total revierte el importe exacto. */
    public static BigDecimal paidPortion(BigDecimal linePaid, BigDecimal lineQty, BigDecimal returnedQty) {
        BigDecimal paid = linePaid == null ? BigDecimal.ZERO : linePaid;
        BigDecimal qty = returnedQty == null ? BigDecimal.ZERO : returnedQty;
        if (qty.signum() == 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        if (lineQty == null || lineQty.signum() == 0 || qty.compareTo(lineQty) == 0) {
            return paid.setScale(2, RoundingMode.HALF_UP);
        }
        return paid.multiply(qty).divide(lineQty, 2, RoundingMode.HALF_UP);
    }

    private static boolean isLarger(KioskSaleItemEntity line, KioskSaleItemEntity current) {
        if (current == null) {
            return true;
        }
        int byAmount = lineSubtotal(line).compareTo(lineSubtotal(current));
        if (byAmount != 0) {
            return byAmount > 0;
        }
        return line.getId() < current.getId();
    }

    private static BigDecimal lineSubtotal(KioskSaleItemEntity line) {
        if (line.getLineTotal() != null) {
            return line.getLineTotal().setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal qty = line.getQuantity() != null && line.getQuantity().compareTo(BigDecimal.ZERO) > 0
                ? line.getQuantity()
                : BigDecimal.ONE;
        return safeAmount(line.getUnitPrice()).multiply(qty).setScale(2, RoundingMode.HALF_UP);
    }

    /** Precio de empaque según factura original: sin repartir descuento de la venta. */
    public static BigDecimal packagingLineAmountNoDiscount(KioskSaleItemEntity item) {
        if (item == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal qty = item.getQuantity() != null && item.getQuantity().compareTo(BigDecimal.ZERO) > 0
                ? item.getQuantity()
                : BigDecimal.ONE;
        if (item.getLineTotal() != null && item.getLineTotal().compareTo(BigDecimal.ZERO) > 0) {
            return item.getLineTotal().setScale(2, RoundingMode.HALF_UP);
        }
        return safeAmount(item.getUnitPrice()).multiply(qty).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal safeAmount(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
