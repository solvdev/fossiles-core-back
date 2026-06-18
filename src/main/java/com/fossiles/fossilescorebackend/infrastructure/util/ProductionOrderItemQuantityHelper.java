package com.fossiles.fossilescorebackend.infrastructure.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderItemEntity;

import java.util.Map;

/**
 * Cantidad efectiva por línea de OP para recetas (BOM) y consumo: alinea con
 * {@code ProductionTaskGenerationService.calculateItemTotalQuantity} (quantity + tallas en JSON).
 */
public final class ProductionOrderItemQuantityHelper {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ProductionOrderItemQuantityHelper() {}

    public static int effectiveQuantityForBom(ProductionOrderItemEntity item) {
        if (item == null) {
            return 1;
        }
        int total = 0;
        if (item.getQuantity() != null) {
            total += item.getQuantity();
        }
        if (item.getSizesData() != null && !item.getSizesData().isEmpty()) {
            try {
                Map<String, Integer> sizes = OBJECT_MAPPER.readValue(
                        item.getSizesData(), new TypeReference<Map<String, Integer>>() {});
                total += sizes.values().stream().mapToInt(Integer::intValue).sum();
            } catch (Exception ignored) {
                // ignore malformed JSON
            }
        }
        return Math.max(total, 1);
    }
}
