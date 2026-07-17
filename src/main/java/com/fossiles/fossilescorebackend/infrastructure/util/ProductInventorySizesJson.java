package com.fossiles.fossilescorebackend.infrastructure.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON en {@code product_inventory_location.sizes_data}: mapa talla (string) → cantidad.
 * Alineado con {@code production_order_item.sizes_data}.
 */
public final class ProductInventorySizesJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ProductInventorySizesJson() {}

    public static String normalizeKey(String size) {
        if (size == null) {
            return "";
        }
        return size.trim();
    }

    public static boolean hasNonEmptyBreakdown(String sizesDataJson) {
        return !parse(sizesDataJson).isEmpty();
    }

    public static Map<String, BigDecimal> parse(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> raw = MAPPER.readValue(json, new TypeReference<>() {});
            Map<String, BigDecimal> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : raw.entrySet()) {
                if (e.getKey() == null) {
                    continue;
                }
                String k = normalizeKey(e.getKey());
                if (k.isEmpty()) {
                    continue;
                }
                out.put(k, toBd(e.getValue()));
            }
            return out;
        } catch (JsonProcessingException e) {
            return new LinkedHashMap<>();
        }
    }

    private static BigDecimal toBd(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        if (v instanceof BigDecimal bd) {
            return bd;
        }
        if (v instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        try {
            return new BigDecimal(v.toString().trim());
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    public static String serialize(Map<String, BigDecimal> sizes) {
        return serializeInternal(sizes, false);
    }

    /** Ajustes de inventario: incluye tallas en cero (baja de stock por talla). */
    public static String serializeIncludingZeros(Map<String, BigDecimal> sizes) {
        return serializeInternal(sizes, true);
    }

    private static String serializeInternal(Map<String, BigDecimal> sizes, boolean includeZeros) {
        if (sizes == null || sizes.isEmpty()) {
            return null;
        }
        Map<String, BigDecimal> cleaned = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> e : sizes.entrySet()) {
            String k = normalizeKey(e.getKey());
            if (k.isEmpty() || e.getValue() == null) {
                continue;
            }
            if (!includeZeros && e.getValue().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            if (e.getValue().compareTo(BigDecimal.ZERO) < 0) {
                continue;
            }
            cleaned.put(k, e.getValue());
        }
        if (cleaned.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(cleaned);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar sizes_data", e);
        }
    }

    public static BigDecimal sum(Map<String, BigDecimal> sizes) {
        if (sizes == null || sizes.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal s = BigDecimal.ZERO;
        for (BigDecimal v : sizes.values()) {
            if (v != null) {
                s = s.add(v);
            }
        }
        return s;
    }

    public static void removeZeroEntries(Map<String, BigDecimal> m) {
        if (m == null) {
            return;
        }
        m.entrySet().removeIf(e -> e.getValue() == null || e.getValue().compareTo(BigDecimal.ZERO) <= 0);
    }

    /** Normaliza claves y fusiona duplicados; descarta negativos. */
    public static Map<String, BigDecimal> normalizeIncomingMap(Map<String, BigDecimal> raw) {
        Map<String, BigDecimal> m = normalizeAdjustmentSizeMap(raw);
        removeZeroEntries(m);
        return m;
    }

    /**
     * Mapa de tallas para ajustes: conserva ceros (p. ej. conteo físico 0 con sistema &gt; 0).
     */
    public static Map<String, BigDecimal> normalizeAdjustmentSizeMap(Map<String, BigDecimal> raw) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        if (raw == null) {
            return m;
        }
        for (Map.Entry<String, BigDecimal> e : raw.entrySet()) {
            String k = normalizeKey(e.getKey());
            if (k.isEmpty() || e.getValue() == null) {
                continue;
            }
            if (e.getValue().compareTo(BigDecimal.ZERO) < 0) {
                continue;
            }
            m.merge(k, e.getValue(), BigDecimal::add);
        }
        return m;
    }

    /** {@code {"E":{"28":2},"BO":{"30":1}}} para conteo cincho FOSS por vitrina/bodega. */
    public static Map<String, Map<String, BigDecimal>> parseByLocation(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Map<String, Object>> raw = MAPPER.readValue(json, new TypeReference<>() {});
            Map<String, Map<String, BigDecimal>> out = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, Object>> locEntry : raw.entrySet()) {
                if (locEntry.getKey() == null || locEntry.getValue() == null) {
                    continue;
                }
                String locKey = locEntry.getKey().trim().toUpperCase();
                if (locKey.isEmpty()) {
                    continue;
                }
                Map<String, BigDecimal> sizes = new LinkedHashMap<>();
                for (Map.Entry<String, Object> sizeEntry : locEntry.getValue().entrySet()) {
                    String sizeKey = normalizeKey(sizeEntry.getKey());
                    if (sizeKey.isEmpty()) {
                        continue;
                    }
                    sizes.put(sizeKey, toBd(sizeEntry.getValue()));
                }
                out.put(locKey, sizes);
            }
            return out;
        } catch (JsonProcessingException e) {
            return new LinkedHashMap<>();
        }
    }

    /** JSON mapa string → string (p. ej. observaciones por talla). */
    public static Map<String, String> parseStringMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> raw = MAPPER.readValue(json, new TypeReference<>() {});
            Map<String, String> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : raw.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) {
                    continue;
                }
                String k = normalizeKey(e.getKey());
                if (k.isEmpty()) {
                    continue;
                }
                String v = e.getValue().toString().trim();
                if (!v.isEmpty()) {
                    out.put(k, v);
                }
            }
            return out;
        } catch (JsonProcessingException e) {
            return new LinkedHashMap<>();
        }
    }

    public static String serializeStringMap(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        Map<String, String> cleaned = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            String k = normalizeKey(e.getKey());
            if (k.isEmpty() || e.getValue() == null) {
                continue;
            }
            String v = e.getValue().trim();
            if (!v.isEmpty()) {
                cleaned.put(k, v);
            }
        }
        if (cleaned.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(cleaned);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar string map", e);
        }
    }

    public static String serializeByLocation(Map<String, Map<String, BigDecimal>> byLocation) {
        if (byLocation == null || byLocation.isEmpty()) {
            return null;
        }
        Map<String, Map<String, BigDecimal>> cleaned = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, BigDecimal>> locEntry : byLocation.entrySet()) {
            if (locEntry.getKey() == null || locEntry.getValue() == null) {
                continue;
            }
            Map<String, BigDecimal> sizes = normalizeAdjustmentSizeMap(locEntry.getValue());
            removeZeroEntries(sizes);
            if (!sizes.isEmpty()) {
                cleaned.put(locEntry.getKey().trim().toUpperCase(), sizes);
            }
        }
        if (cleaned.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(cleaned);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar size_location_counts_data", e);
        }
    }
}
