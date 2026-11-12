package com.fossiles.fossilescorebackend.infrastructure.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Clave de talla de cincho: trim + mayúsculas ({@link Locale#ROOT}).
 * La misma forma usan {@code production_order_item.sizes_data},
 * {@code production_order_warehouse_unit.size_key} y la talla del envío.
 */
public final class CinchoSizeKeys {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CinchoSizeKeys() {}

    public static String normalize(String size) {
        if (size == null || size.isBlank()) {
            return "";
        }
        return size.trim().toUpperCase(Locale.ROOT);
    }

    /** Suma cantidades positivas bajo la clave normalizada. Conserva el orden de aparición. */
    public static Map<String, Integer> aggregate(Map<String, Integer> sizes) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (sizes == null) {
            return out;
        }
        for (Map.Entry<String, Integer> entry : sizes.entrySet()) {
            add(out, entry.getKey(), entry.getValue() != null ? entry.getValue() : 0);
        }
        return out;
    }

    public static void add(Map<String, Integer> target, String size, int quantity) {
        if (target == null || quantity <= 0) {
            return;
        }
        String key = normalize(size);
        if (key.isEmpty()) {
            return;
        }
        target.merge(key, quantity, Integer::sum);
    }

    public static int sum(Map<String, Integer> sizes) {
        if (sizes == null || sizes.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (Integer value : sizes.values()) {
            if (value != null) {
                total += value;
            }
        }
        return total;
    }

    /** {@code null} si no queda ninguna talla con cantidad. */
    public static String toJson(Map<String, Integer> sizes) {
        if (sizes == null || sizes.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(sizes);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
