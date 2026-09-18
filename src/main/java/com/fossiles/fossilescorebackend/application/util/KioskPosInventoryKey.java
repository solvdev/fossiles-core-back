package com.fossiles.fossilescorebackend.application.util;

import com.fossiles.fossilescorebackend.infrastructure.util.ProductInventorySizesJson;

/**
 * Clave POS {@code productId:colorId:hardware[:size]}.
 * Hardware puede ser NUEVO/VIEJO, NINO/DAMA o {@code SINTETICO:MARCA}.
 */
public final class KioskPosInventoryKey {

    public record Parsed(Long productId, Long colorId, String size, String hardwareCondition) {
    }

    private KioskPosInventoryKey() {
    }

    public static String format(Long productId, Long colorId, String hardwareCondition, String size) {
        String hardware = ProductHardwareCondition.normalizeStockDimension(hardwareCondition);
        String base = productId + ":" + (colorId != null ? colorId : "null") + ":" + hardware;
        String normalized = ProductInventorySizesJson.normalizeKey(size);
        if (!normalized.isEmpty()) {
            return base + ":" + normalized;
        }
        return base;
    }

    public static Parsed parse(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Clave de inventario inválida: " + key);
        }
        String[] parts = key.split(":", -1);
        if (parts.length < 2) {
            throw new IllegalArgumentException("Clave de inventario inválida: " + key);
        }
        Long productId = Long.parseLong(parts[0]);
        String colorPart = parts[1];
        Long colorId = "null".equals(colorPart) ? null : Long.parseLong(colorPart);
        if (parts.length == 2) {
            return new Parsed(productId, colorId, null, ProductHardwareCondition.NUEVO);
        }

        String last = parts[parts.length - 1];
        if (parts.length >= 4 && looksLikeSize(last)) {
            String hardwareRaw = String.join(":", java.util.Arrays.copyOfRange(parts, 2, parts.length - 1));
            return new Parsed(
                    productId,
                    colorId,
                    blankToNull(last),
                    ProductHardwareCondition.normalizeStockDimension(hardwareRaw)
            );
        }

        if (parts.length == 3) {
            String dimension = parseDimensionToken(parts[2]);
            if (dimension != null) {
                return new Parsed(productId, colorId, null, dimension);
            }
            return new Parsed(productId, colorId, blankToNull(parts[2]), ProductHardwareCondition.NUEVO);
        }

        String hardwareRaw = String.join(":", java.util.Arrays.copyOfRange(parts, 2, parts.length));
        return new Parsed(
                productId,
                colorId,
                null,
                ProductHardwareCondition.normalizeStockDimension(hardwareRaw)
        );
    }

    static String parseDimensionToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String hardware = ProductHardwareCondition.normalize(token);
        if (hardware != null) {
            return hardware;
        }
        String audience = ProductCinchoAudience.normalize(token);
        if (audience != null) {
            return audience;
        }
        String brand = ProductBrandNames.normalize(token);
        if (brand != null) {
            return brand;
        }
        String wallet = ProductHardwareCondition.resolveWalletDimension(token);
        if (wallet != null) {
            return wallet;
        }
        if (ProductHardwareCondition.isSynthetic(token) || ProductHardwareCondition.isNonSynthetic(token)) {
            return ProductHardwareCondition.normalizeStockDimension(token);
        }
        return null;
    }

    static boolean looksLikeSize(String value) {
        if (value == null || value.isBlank() || parseDimensionToken(value) != null) {
            return false;
        }
        String n = ProductInventorySizesJson.normalizeKey(value);
        return !n.isEmpty() && Character.isDigit(n.charAt(0));
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }
}
