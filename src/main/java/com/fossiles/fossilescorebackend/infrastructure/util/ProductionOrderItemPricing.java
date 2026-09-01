package com.fossiles.fossilescorebackend.infrastructure.util;

import com.fossiles.fossilescorebackend.application.util.CinchoSizePricing;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderItemEntity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Precio unitario de ítems de OP: {@code unit_price} global + override por talla en {@code unit_prices_json}.
 * Sin override por talla, aplica recargo de cincho (44–48 = +Q50, 50+ = +Q100).
 */
public final class ProductionOrderItemPricing {

    private ProductionOrderItemPricing() {}

    public static Map<String, BigDecimal> parseUnitPrices(String unitPricesJson) {
        return ProductInventorySizesJson.parse(unitPricesJson);
    }

    public static String serializeUnitPrices(Map<String, BigDecimal> unitPrices) {
        return ProductInventorySizesJson.serialize(unitPrices);
    }

    /** True si el ítem tiene precio guardado en OP (global > 0 o mapa por talla). */
    public static boolean hasExplicitUnitPrice(ProductionOrderItemEntity item) {
        if (item == null) {
            return false;
        }
        String json = item.getUnitPricesJson();
        if (json != null && !json.isBlank() && !parseUnitPrices(json).isEmpty()) {
            return true;
        }
        return item.getUnitPrice() != null && item.getUnitPrice().compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * Precio para una talla concreta. Si no hay override en el mapa, usa {@code unitPrice} del ítem
     * y, en su defecto, el precio de catálogo vía {@code productFallback}, más recargo por talla.
     */
    public static BigDecimal resolveForSize(
            ProductionOrderItemEntity item,
            String sizeLabel,
            Function<Long, BigDecimal> productFallback) {
        if (item == null) {
            return BigDecimal.ZERO;
        }
        Map<String, BigDecimal> bySize = parseUnitPrices(item.getUnitPricesJson());
        String sizeKey = ProductInventorySizesJson.normalizeKey(sizeLabel);
        if (!sizeKey.isEmpty() && !bySize.isEmpty()) {
            BigDecimal sized = lookupSizePrice(bySize, sizeKey);
            // Precio explícito por talla (incluye 0 = cortesía). Si no hay clave, seguir cascada.
            if (sized != null) {
                return sized;
            }
        }
        BigDecimal base = null;
        // unit_price = 0 suele venir de formularios que mandan 0 por defecto; no bloquear catálogo.
        if (item.getUnitPrice() != null && item.getUnitPrice().compareTo(BigDecimal.ZERO) > 0) {
            base = item.getUnitPrice();
        } else if (productFallback != null && item.getProductId() != null) {
            BigDecimal fallback = productFallback.apply(item.getProductId());
            if (fallback != null && fallback.compareTo(BigDecimal.ZERO) > 0) {
                base = fallback;
            }
        }
        if (base == null) {
            return BigDecimal.ZERO;
        }
        return CinchoSizePricing.applySurcharge(base, sizeLabel);
    }

    /** Subtotal del ítem respetando precios distintos por talla. */
    public static BigDecimal itemSubtotal(
            ProductionOrderItemEntity item,
            Function<Long, BigDecimal> productFallback) {
        if (item == null) {
            return BigDecimal.ZERO;
        }
        Map<String, BigDecimal> sizes = ProductInventorySizesJson.parse(item.getSizesData());
        BigDecimal total = BigDecimal.ZERO;
        if (!sizes.isEmpty()) {
            for (Map.Entry<String, BigDecimal> entry : sizes.entrySet()) {
                if (entry.getValue() == null || entry.getValue().compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                int qty = entry.getValue().setScale(0, RoundingMode.HALF_UP).intValue();
                if (qty <= 0) {
                    continue;
                }
                BigDecimal unit = resolveForSize(item, entry.getKey(), productFallback);
                total = total.add(unit.multiply(BigDecimal.valueOf(qty)));
            }
            return total.setScale(2, RoundingMode.HALF_UP);
        }
        int qty = item.getQuantity() != null ? item.getQuantity() : 0;
        if (qty <= 0) {
            return BigDecimal.ZERO;
        }
        return resolveForSize(item, null, productFallback)
                .multiply(BigDecimal.valueOf(qty))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal lookupSizePrice(Map<String, BigDecimal> bySize, String sizeKey) {
        BigDecimal direct = bySize.get(sizeKey);
        if (direct != null) {
            return direct;
        }
        String upper = sizeKey.toUpperCase();
        for (Map.Entry<String, BigDecimal> e : bySize.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(sizeKey)) {
                return e.getValue();
            }
            if (e.getKey() != null && Objects.equals(ProductInventorySizesJson.normalizeKey(e.getKey()).toUpperCase(), upper)) {
                return e.getValue();
            }
        }
        return null;
    }
}
