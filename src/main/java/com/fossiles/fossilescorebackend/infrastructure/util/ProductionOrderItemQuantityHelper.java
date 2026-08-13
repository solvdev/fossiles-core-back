package com.fossiles.fossilescorebackend.infrastructure.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderItemEntity;

import java.util.Map;

/**
 * Cantidad efectiva por línea de OP para recetas (BOM) y consumo.
 * <p>
 * Misma regla que {@code ProductionOrderController.totalOrderItemQuantity} y
 * {@code ProductionOrderItemPricing.itemSubtotal}: si hay desglose de tallas con
 * suma &gt; 0, ese total es la cantidad de piezas; si no, se usa {@code quantity}.
 * No se suman ambos (eso duplicaba materiales cuando quantity ya reflejaba las tallas).
 */
public final class ProductionOrderItemQuantityHelper {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ProductionOrderItemQuantityHelper() {}

    public static int effectiveQuantityForBom(ProductionOrderItemEntity item) {
        if (item == null) {
            return 1;
        }
        if (item.getSizesData() != null && !item.getSizesData().isBlank()) {
            try {
                Map<String, Integer> sizes = OBJECT_MAPPER.readValue(
                        item.getSizesData(), new TypeReference<Map<String, Integer>>() {});
                int fromSizes = sizes.values().stream()
                        .mapToInt(v -> v != null ? Math.max(v, 0) : 0)
                        .sum();
                if (fromSizes > 0) {
                    return fromSizes;
                }
            } catch (Exception ignored) {
                // quantity como fallback si el JSON está mal
            }
        }
        int qty = item.getQuantity() != null ? Math.max(item.getQuantity(), 0) : 0;
        return Math.max(qty, 1);
    }
}
