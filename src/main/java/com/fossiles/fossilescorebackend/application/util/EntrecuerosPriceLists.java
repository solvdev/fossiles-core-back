package com.fossiles.fossilescorebackend.application.util;

import com.fossiles.fossilescorebackend.application.service.EntrecuerosVolumePricing;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/** Listas de precio Entrecueros por variante (mismo SKU, distinto PARA / material). */
public final class EntrecuerosPriceLists {

    public enum Kind {
        CASUAL,
        REVERSIBLE,
        NINO,
        DAMA,
        WALLET_LEATHER,
        WALLET_SYNTHETIC,
        CARDHOLDER_SYNTHETIC,
        PRODUCT
    }

    private EntrecuerosPriceLists() {
    }

    public static Kind kind(ProductEntity product, String hardware) {
        if (product == null || KioscoInventoryInitRules.isPackagingProduct(product)) {
            return Kind.PRODUCT;
        }
        boolean cincho = KioscoInventoryInitRules.isCinchoProduct(product);
        if (cincho && "REVERSIBLE".equals(ProductCinchoType.normalizeCinchoType(product.getCinchoType()))) {
            return Kind.REVERSIBLE;
        }
        String audience = ProductCinchoAudience.normalize(hardware);
        if (cincho && ProductCinchoAudience.NINO.equals(audience)) {
            return Kind.NINO;
        }
        if (cincho && ProductCinchoAudience.DAMA.equals(audience)) {
            return Kind.DAMA;
        }
        if (cincho) {
            return Kind.CASUAL;
        }
        String name = product.getName() != null ? product.getName().toUpperCase(Locale.ROOT) : "";
        boolean synthetic = ProductHardwareCondition.isSynthetic(hardware);
        if (name.contains("TARJETER")) {
            return Kind.CARDHOLDER_SYNTHETIC;
        }
        if (KioscoInventoryInitRules.isWalletProduct(product)) {
            return synthetic ? Kind.WALLET_SYNTHETIC : Kind.WALLET_LEATHER;
        }
        return synthetic ? Kind.WALLET_SYNTHETIC : Kind.PRODUCT;
    }

    public static String volumeKey(Long productId, ProductEntity product, String hardware) {
        return (productId != null ? productId : "") + "|" + kind(product, hardware).name();
    }

    public static BigDecimal resolveUnitPrice(ProductEntity product, String hardware, BigDecimal quantity) {
        Kind kind = kind(product, hardware);
        int qty = quantity == null ? 0 : quantity.setScale(0, RoundingMode.DOWN).intValue();
        return switch (kind) {
            case NINO -> qty >= 3 ? money("45") : money("65");
            case DAMA -> qty >= 3 ? money("60") : money("65");
            case REVERSIBLE -> money("100");
            case WALLET_LEATHER -> qty >= 6 ? money("55") : qty >= 3 ? money("65") : money("100");
            case WALLET_SYNTHETIC -> resolveSyntheticWallet(product, qty);
            case CARDHOLDER_SYNTHETIC -> qty >= 3 ? money("6") : money("10");
            case CASUAL -> resolveCasual(product, quantity);
            case PRODUCT -> EntrecuerosVolumePricing.resolveUnitPrice(product, quantity);
        };
    }

    private static BigDecimal resolveCasual(ProductEntity product, BigDecimal quantity) {
        if (hasProductTiers(product)) {
            return EntrecuerosVolumePricing.resolveUnitPrice(product, quantity);
        }
        int qty = quantity == null ? 0 : quantity.setScale(0, RoundingMode.DOWN).intValue();
        if (qty >= 12) return money("75");
        if (qty >= 6) return money("80");
        if (qty >= 3) return money("90");
        return money("100");
    }

    private static BigDecimal resolveSyntheticWallet(ProductEntity product, int qty) {
        String code = product != null && product.getCode() != null
                ? product.getCode().trim().toUpperCase(Locale.ROOT)
                : "";
        if (code.contains("B-1") || code.equals("B1")) {
            return money("40");
        }
        if (qty >= 3) {
            return money("30");
        }
        return money("40");
    }

    private static boolean hasProductTiers(ProductEntity product) {
        if (product == null) {
            return false;
        }
        return positive(product.getEntrecuerosPriceUnit()) != null
                || positive(product.getEntrecuerosPriceQty3()) != null
                || positive(product.getEntrecuerosPriceQty6()) != null
                || positive(product.getEntrecuerosPriceQty12()) != null;
    }

    private static BigDecimal positive(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return value;
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2, RoundingMode.HALF_UP);
    }
}
