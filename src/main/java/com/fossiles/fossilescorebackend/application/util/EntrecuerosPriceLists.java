package com.fossiles.fossilescorebackend.application.util;

import com.fossiles.fossilescorebackend.application.service.EntrecuerosVolumePricing;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;

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

    public static final int WHOLESALE_UNLOCK_QTY = 6;

    /**
     * PENDING DECISION: kinds other than casual use the fixed lists in this class.
     * Casual already uses the product's configured tiers when they are set.
     * Flip this flag to price the other kinds from those configured tiers too.
     * REVERSIBLE stays 100 and an exact code B-1/B1 stays 40 either way.
     * The cinchoForKids fallback is a separate pending decision in {@link #cinchoAudience}.
     */
    private static final boolean USE_CONFIGURED_TIERS_FOR_NON_CASUAL = false;

    /** Quantity that selects the top configured tier (12, else 6, else 3, else unit). */
    private static final BigDecimal CONFIGURED_TOP_TIER_QTY = BigDecimal.valueOf(12);

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
        String audience = cinchoAudience(hardware);
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

    /**
     * PENDING DECISION: cinchoForKids is not consulted. Audience comes only from
     * the hardware variant. {@link #kind} stays REVERSIBLE, then NINO, then DAMA, then CASUAL.
     */
    private static String cinchoAudience(String hardware) {
        return ProductCinchoAudience.normalize(hardware);
    }

    public static String volumeKey(Long productId, ProductEntity product, String hardware) {
        Kind kind = kind(product, hardware);
        if (kind == Kind.PRODUCT) {
            return (productId != null ? productId : "") + "|" + kind.name();
        }
        return kind.name();
    }

    /**
     * Packaging never counts toward the wholesale trigger and never receives courtesy.
     */
    public static void addVolumeQuantity(
            Map<String, BigDecimal> qtyByVolumeKey,
            Long productId,
            ProductEntity product,
            String hardware,
            BigDecimal quantity
    ) {
        if (qtyByVolumeKey == null || quantity == null || KioscoInventoryInitRules.isPackagingProduct(product)) {
            return;
        }
        qtyByVolumeKey.merge(volumeKey(productId, product, hardware), quantity, BigDecimal::add);
    }

    public static boolean unlocksWholesale(Map<String, BigDecimal> qtyByPriceKey) {
        if (qtyByPriceKey == null || qtyByPriceKey.isEmpty()) {
            return false;
        }
        BigDecimal threshold = BigDecimal.valueOf(WHOLESALE_UNLOCK_QTY);
        for (BigDecimal qty : qtyByPriceKey.values()) {
            if (qty != null && qty.compareTo(threshold) >= 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Unit price charged for one line. Own type total selects the tier. A type below
     * {@link #WHOLESALE_UNLOCK_QTY} receives its highest tier only when another type
     * reached that quantity. Packaging is priced from its own line quantity.
     */
    public static BigDecimal resolveChargedUnitPrice(
            ProductEntity product,
            String hardware,
            BigDecimal lineQuantity,
            Map<String, BigDecimal> qtyByVolumeKey
    ) {
        if (KioscoInventoryInitRules.isPackagingProduct(product)) {
            return roundMoney(resolveUnitPrice(product, hardware, lineQuantity));
        }
        String key = volumeKey(product != null ? product.getId() : null, product, hardware);
        BigDecimal ownQty = ownQuantity(qtyByVolumeKey, key, lineQuantity);
        BigDecimal price = receivesCourtesy(qtyByVolumeKey, ownQty)
                ? resolveCourtesyUnitPrice(product, hardware)
                : resolveUnitPrice(product, hardware, ownQty);
        return roundMoney(price);
    }

    public static BigDecimal lineTotal(BigDecimal unitPrice, BigDecimal quantity) {
        BigDecimal unit = roundMoney(unitPrice);
        BigDecimal qty = quantity == null ? BigDecimal.ZERO : quantity;
        return unit.multiply(qty).setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal resolveUnitPrice(ProductEntity product, String hardware, BigDecimal quantity) {
        Kind kind = kind(product, hardware);
        int qty = wholeUnits(quantity);
        return switch (kind) {
            case NINO -> nonCasual(product, quantity, qty >= 3 ? money("45") : money("65"));
            case DAMA -> nonCasual(product, quantity, qty >= 3 ? money("60") : money("65"));
            case REVERSIBLE -> money("100");
            case WALLET_LEATHER -> nonCasual(
                    product, quantity, qty >= 6 ? money("55") : qty >= 3 ? money("65") : money("100"));
            case WALLET_SYNTHETIC -> resolveSyntheticWallet(product, quantity, qty);
            case CARDHOLDER_SYNTHETIC -> nonCasual(product, quantity, qty >= 3 ? money("6") : money("10"));
            case CASUAL -> resolveCasual(product, quantity);
            case PRODUCT -> EntrecuerosVolumePricing.resolveUnitPrice(product, quantity);
        };
    }

    private static BigDecimal resolveCourtesyUnitPrice(ProductEntity product, String hardware) {
        Kind kind = kind(product, hardware);
        return switch (kind) {
            case CASUAL -> resolveCasualTopTier(product);
            case REVERSIBLE -> money("100");
            case NINO -> nonCasual(product, CONFIGURED_TOP_TIER_QTY, money("45"));
            case DAMA -> nonCasual(product, CONFIGURED_TOP_TIER_QTY, money("60"));
            case WALLET_LEATHER -> nonCasual(product, CONFIGURED_TOP_TIER_QTY, money("55"));
            case WALLET_SYNTHETIC -> isExactB1Code(product)
                    ? money("40")
                    : nonCasual(product, CONFIGURED_TOP_TIER_QTY, money("30"));
            case CARDHOLDER_SYNTHETIC -> nonCasual(product, CONFIGURED_TOP_TIER_QTY, money("6"));
            case PRODUCT -> resolveUntypedCourtesyUnitPrice(product);
        };
    }

    /**
     * Final: an untyped product ({@link Kind#PRODUCT}, one group per product id) that
     * receives courtesy is priced at its top configured tier.
     */
    private static BigDecimal resolveUntypedCourtesyUnitPrice(ProductEntity product) {
        return EntrecuerosVolumePricing.resolveTopConfiguredTier(product);
    }

    private static BigDecimal resolveCasual(ProductEntity product, BigDecimal quantity) {
        if (hasProductTiers(product)) {
            return EntrecuerosVolumePricing.resolveUnitPrice(product, quantity);
        }
        int qty = wholeUnits(quantity);
        if (qty >= 12) {
            return money("75");
        }
        if (qty >= 6) {
            return money("80");
        }
        if (qty >= 3) {
            return money("90");
        }
        return money("100");
    }

    private static BigDecimal resolveCasualTopTier(ProductEntity product) {
        if (hasProductTiers(product)) {
            return EntrecuerosVolumePricing.resolveTopConfiguredTier(product);
        }
        return money("75");
    }

    private static BigDecimal resolveSyntheticWallet(ProductEntity product, BigDecimal quantity, int qty) {
        if (isExactB1Code(product)) {
            return money("40");
        }
        return nonCasual(product, quantity, qty >= 3 ? money("30") : money("40"));
    }

    /**
     * Single switch for the pending non-casual configured-tier decision.
     * {@code fixedListPrice} is what the sale charges while the flag is off.
     */
    private static BigDecimal nonCasual(
            ProductEntity product,
            BigDecimal tierQuantity,
            BigDecimal fixedListPrice
    ) {
        if (USE_CONFIGURED_TIERS_FOR_NON_CASUAL && hasProductTiers(product)) {
            return EntrecuerosVolumePricing.resolveUnitPrice(product, tierQuantity);
        }
        return fixedListPrice;
    }

    private static boolean receivesCourtesy(Map<String, BigDecimal> qtyByVolumeKey, BigDecimal ownQty) {
        if (!unlocksWholesale(qtyByVolumeKey)) {
            return false;
        }
        BigDecimal own = ownQty == null ? BigDecimal.ZERO : ownQty;
        return own.compareTo(BigDecimal.valueOf(WHOLESALE_UNLOCK_QTY)) < 0;
    }

    private static BigDecimal ownQuantity(
            Map<String, BigDecimal> qtyByVolumeKey,
            String key,
            BigDecimal lineQuantity
    ) {
        if (qtyByVolumeKey != null && key != null && qtyByVolumeKey.containsKey(key)) {
            BigDecimal own = qtyByVolumeKey.get(key);
            return own == null ? BigDecimal.ZERO : own;
        }
        return lineQuantity == null ? BigDecimal.ZERO : lineQuantity;
    }

    static boolean isExactB1Code(ProductEntity product) {
        if (product == null || product.getCode() == null) {
            return false;
        }
        String code = product.getCode().trim().toUpperCase(Locale.ROOT);
        return "B-1".equals(code) || "B1".equals(code);
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

    private static int wholeUnits(BigDecimal quantity) {
        return quantity == null ? 0 : quantity.setScale(0, RoundingMode.DOWN).intValue();
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

    private static BigDecimal roundMoney(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }
}
