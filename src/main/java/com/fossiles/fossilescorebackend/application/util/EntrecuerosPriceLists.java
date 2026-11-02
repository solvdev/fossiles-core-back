package com.fossiles.fossilescorebackend.application.util;

import com.fossiles.fossilescorebackend.application.service.EntrecuerosVolumePricing;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;

/**
 * Listas de precio fijas del kiosko Entrecueros (Pueblito), recosteo aprobado.
 * El tier lo elige la cantidad del propio tipo. Casual usa los tiers del producto
 * cuando están configurados. B-1 (código exacto) cobra Q130 por cada grupo de 3
 * y Q40 por cada unidad sobrante; ese total de línea no siempre es precio × cantidad.
 */
public final class EntrecuerosPriceLists {

    public enum Kind {
        CASUAL,
        REVERSIBLE,
        NINO,
        DAMA,
        WALLET_LEATHER,
        WALLET_SYNTHETIC,
        CARDHOLDER_SYNTHETIC,
        PACKAGING,
        PRODUCT
    }

    public static final int WHOLESALE_UNLOCK_QTY = 6;

    /**
     * PENDING DECISION: kinds other than casual use the fixed lists in this class.
     * Casual already uses the product's configured tiers when they are set.
     * Flip this flag to price the other kinds from those configured tiers too.
     * REVERSIBLE stays 125 and an exact code B-1/B1 keeps the bundle
     * (Q130 per group of 3, leftover units at Q40) either way.
     * The cinchoForKids fallback is a separate pending decision in {@link #cinchoAudience}.
     */
    private static final boolean USE_CONFIGURED_TIERS_FOR_NON_CASUAL = false;

    /** Quantity that selects the top configured tier (12, else 6, else 3, else unit). */
    private static final BigDecimal CONFIGURED_TOP_TIER_QTY = BigDecimal.valueOf(12);

    private EntrecuerosPriceLists() {
    }

    public static Kind kind(ProductEntity product, String hardware) {
        if (product == null) {
            return Kind.PRODUCT;
        }
        if (KioscoInventoryInitRules.isPackagingProduct(product)) {
            return Kind.PACKAGING;
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
        if (kind == Kind.PRODUCT || kind == Kind.PACKAGING) {
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
        String ownKey = b2b5ProductQtyKey(product, hardware);
        if (ownKey != null) {
            qtyByVolumeKey.merge(ownKey, quantity, BigDecimal::add);
        }
    }

    public static boolean unlocksWholesale(Map<String, BigDecimal> qtyByPriceKey) {
        if (qtyByPriceKey == null || qtyByPriceKey.isEmpty()) {
            return false;
        }
        BigDecimal threshold = BigDecimal.valueOf(WHOLESALE_UNLOCK_QTY);
        for (Map.Entry<String, BigDecimal> entry : qtyByPriceKey.entrySet()) {
            if (entry.getKey() != null && entry.getKey().startsWith(PRODUCT_QTY_PREFIX)) {
                continue;
            }
            BigDecimal qty = entry.getValue();
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
        if (receivesCourtesy(qtyByVolumeKey, ownQty)) {
            return roundMoney(resolveCourtesyUnitPrice(product, hardware));
        }
        if (isB1Bundle(product, hardware)) {
            return b1DisplayUnitPrice(lineQuantity);
        }
        if (isB2B5OwnTier(product, hardware)) {
            return roundMoney(resolveUnitPrice(
                    product, hardware, b2b5Quantity(product, hardware, lineQuantity, qtyByVolumeKey)));
        }
        return roundMoney(resolveUnitPrice(product, hardware, ownQty));
    }

    /**
     * Total cobrado de la línea. Un B-1 sin cortesía usa el paquete
     * (Q130 cada 3 unidades, sobrante a Q40) en lugar de redondear precio × cantidad.
     */
    public static BigDecimal chargedLineTotal(
            ProductEntity product,
            String hardware,
            BigDecimal lineQuantity,
            Map<String, BigDecimal> qtyByVolumeKey
    ) {
        if (isB1BundleLine(product, hardware, lineQuantity, qtyByVolumeKey)) {
            return b1BundleTotal(lineQuantity);
        }
        return lineTotal(
                resolveChargedUnitPrice(product, hardware, lineQuantity, qtyByVolumeKey),
                lineQuantity);
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
            case NINO -> nonCasual(product, quantity, qty >= 3 ? money("55") : money("75"));
            case DAMA -> nonCasual(product, quantity, qty >= 3 ? money("70") : money("75"));
            case REVERSIBLE -> money("125");
            case WALLET_LEATHER -> nonCasual(
                    product, quantity, qty >= 6 ? money("65") : qty >= 3 ? money("75") : money("125"));
            case WALLET_SYNTHETIC -> resolveSyntheticWallet(product, quantity, qty);
            case CARDHOLDER_SYNTHETIC -> nonCasual(product, quantity, qty >= 3 ? money("10") : money("15"));
            case CASUAL -> resolveCasual(product, quantity);
            case PACKAGING, PRODUCT -> EntrecuerosVolumePricing.resolveUnitPrice(product, quantity);
        };
    }

    private static BigDecimal resolveCourtesyUnitPrice(ProductEntity product, String hardware) {
        Kind kind = kind(product, hardware);
        return switch (kind) {
            case CASUAL -> resolveCasualTopTier(product);
            case REVERSIBLE -> money("125");
            case NINO -> nonCasual(product, CONFIGURED_TOP_TIER_QTY, money("55"));
            case DAMA -> nonCasual(product, CONFIGURED_TOP_TIER_QTY, money("70"));
            case WALLET_LEATHER -> nonCasual(product, CONFIGURED_TOP_TIER_QTY, money("65"));
            case WALLET_SYNTHETIC -> syntheticCourtesy(product);
            case CARDHOLDER_SYNTHETIC -> nonCasual(product, CONFIGURED_TOP_TIER_QTY, money("10"));
            case PACKAGING -> EntrecuerosVolumePricing.resolveUnitPrice(product, BigDecimal.ONE);
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
            return money("85");
        }
        if (qty >= 6) {
            return money("95");
        }
        if (qty >= 3) {
            return money("110");
        }
        return money("125");
    }

    private static BigDecimal resolveCasualTopTier(ProductEntity product) {
        if (hasProductTiers(product)) {
            return EntrecuerosVolumePricing.resolveTopConfiguredTier(product);
        }
        return money("85");
    }

    private static BigDecimal resolveSyntheticWallet(ProductEntity product, BigDecimal quantity, int qty) {
        if (isExactB1Code(product)) {
            return b1DisplayUnitPrice(quantity);
        }
        if (isExactB2OrB5Code(product)) {
            BigDecimal fixed = qty >= 4 ? money("40") : qty >= 3 ? money("30") : money("40");
            return nonCasual(product, quantity, fixed);
        }
        return nonCasual(product, quantity, qty >= 3 ? money("30") : money("40"));
    }

    private static BigDecimal syntheticCourtesy(ProductEntity product) {
        if (isExactB1Code(product)) {
            return money("40");
        }
        if (isExactB2OrB5Code(product)) {
            return nonCasual(product, CONFIGURED_TOP_TIER_QTY, money("40"));
        }
        return nonCasual(product, CONFIGURED_TOP_TIER_QTY, money("30"));
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
        return isExactB1Code(normalizedCode(product));
    }

    static boolean isExactB1Code(String code) {
        if (code == null) {
            return false;
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        return "B-1".equals(normalized) || "B1".equals(normalized);
    }

    /**
     * La línea guardada es un B-1 cobrado por paquete: el total es Q130 por cada
     * grupo de 3 y Q40 por el sobrante, no cortesía ni un descuento distinto.
     */
    public static boolean isChargedB1BundleLine(
            String productCode, BigDecimal quantity, BigDecimal lineTotal) {
        if (!isExactB1Code(productCode) || lineTotal == null || lineTotal.signum() <= 0) {
            return false;
        }
        return lineTotal.setScale(2, RoundingMode.HALF_UP).compareTo(b1BundleTotal(quantity)) == 0;
    }

    static boolean isExactB2OrB5Code(ProductEntity product) {
        String code = normalizedCode(product);
        return "B-2".equals(code) || "B2".equals(code) || "B-5".equals(code) || "B5".equals(code);
    }

    private static boolean isB1Bundle(ProductEntity product, String hardware) {
        return isExactB1Code(product) && kind(product, hardware) == Kind.WALLET_SYNTHETIC;
    }

    private static boolean isB1BundleLine(
            ProductEntity product,
            String hardware,
            BigDecimal lineQuantity,
            Map<String, BigDecimal> qtyByVolumeKey
    ) {
        if (!isB1Bundle(product, hardware)) {
            return false;
        }
        String key = volumeKey(product != null ? product.getId() : null, product, hardware);
        BigDecimal ownQty = ownQuantity(qtyByVolumeKey, key, lineQuantity);
        return !receivesCourtesy(qtyByVolumeKey, ownQty);
    }

    /** Q130 por cada grupo completo de 3; el sobrante queda a Q40. */
    static BigDecimal b1BundleTotal(BigDecimal quantity) {
        int units = wholeUnits(quantity);
        int groups = units / 3;
        int leftover = units % 3;
        BigDecimal total = money("130").multiply(BigDecimal.valueOf(groups))
                .add(money("40").multiply(BigDecimal.valueOf(leftover)));
        if (quantity != null) {
            BigDecimal fraction = quantity.subtract(BigDecimal.valueOf(units));
            if (fraction.signum() > 0) {
                total = total.add(money("40").multiply(fraction));
            }
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Precio unitario mostrado: paquete / cantidad, redondeado a 2 decimales (130/3 = 43.33).
     * El total cobrado sigue siendo el paquete. La factura FEL sube el unitario al céntimo
     * que cubre ese total y lleva la diferencia como descuento de línea.
     */
    private static BigDecimal b1DisplayUnitPrice(BigDecimal quantity) {
        int units = wholeUnits(quantity);
        if (units <= 0) {
            return money("40");
        }
        BigDecimal total = b1BundleTotal(BigDecimal.valueOf(units));
        return total.divide(BigDecimal.valueOf(units), 2, RoundingMode.HALF_UP);
    }

    private static final String PRODUCT_QTY_PREFIX = "sku|";

    /**
     * B-5/B-2 choose the 4+ tier from this product's own units in the sale.
     * The synthetic group still owns courtesy and the 6-unit trigger.
     */
    private static boolean isB2B5OwnTier(ProductEntity product, String hardware) {
        return isExactB2OrB5Code(product) && kind(product, hardware) == Kind.WALLET_SYNTHETIC;
    }

    private static String b2b5ProductQtyKey(ProductEntity product, String hardware) {
        if (!isB2B5OwnTier(product, hardware) || product == null) {
            return null;
        }
        if (product.getId() != null) {
            return PRODUCT_QTY_PREFIX + product.getId();
        }
        String code = normalizedCode(product);
        return code.isEmpty() ? null : PRODUCT_QTY_PREFIX + code;
    }

    private static BigDecimal b2b5Quantity(
            ProductEntity product,
            String hardware,
            BigDecimal lineQuantity,
            Map<String, BigDecimal> qtyByVolumeKey
    ) {
        String key = b2b5ProductQtyKey(product, hardware);
        if (qtyByVolumeKey != null && key != null && qtyByVolumeKey.containsKey(key)) {
            BigDecimal own = qtyByVolumeKey.get(key);
            return own == null ? BigDecimal.ZERO : own;
        }
        return lineQuantity == null ? BigDecimal.ZERO : lineQuantity;
    }

    private static String normalizedCode(ProductEntity product) {
        if (product == null || product.getCode() == null) {
            return "";
        }
        return product.getCode().trim().toUpperCase(Locale.ROOT);
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
