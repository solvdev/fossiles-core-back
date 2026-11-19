package com.fossiles.fossilescorebackend.application.util;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;

/**
 * Subgrupos del reporte de ventas de kiosko por categoría.
 *
 * <p>Billeteras y cinchos se identifican por producto ({@link KioscoInventoryInitRules#isWalletProduct},
 * {@link KioscoInventoryInitRules#isCinchoProduct}), no por código de {@code product_category}.
 * Los empaques (SUM-) van como subgrupo {@link #EMPAQUE}. El resto de productos deja ambos campos nulos.
 */
public final class KioskSalesCategorySubgroup {

    public static final String CABALLERO = "CABALLERO";
    public static final String DAMA = "DAMA";
    public static final String NINO = "NINO";
    public static final String DAMA_CASUAL = "DAMA_CASUAL";
    public static final String DAMA_REVERSIBLE = "DAMA_REVERSIBLE";
    public static final String CABALLERO_CASUAL = "CABALLERO_CASUAL";
    public static final String CABALLERO_REVERSIBLE = "CABALLERO_REVERSIBLE";
    public static final String SIN_CLASIFICAR = "SIN_CLASIFICAR";
    /** Bolsas y cajas de empaque (códigos SUM-): se cobran pero no son producto vendido. */
    public static final String EMPAQUE = "EMPAQUE";

    public static final String LABEL_EMPAQUE = "Empaque";
    public static final String LABEL_CABALLERO = "Caballero";
    public static final String LABEL_DAMA = "Dama";
    public static final String LABEL_NINO = "Niño";
    public static final String LABEL_DAMA_CASUAL = "Dama casual";
    public static final String LABEL_DAMA_REVERSIBLE = "Dama reversible";
    public static final String LABEL_CABALLERO_CASUAL = "Caballero casual";
    public static final String LABEL_CABALLERO_REVERSIBLE = "Caballero reversible";
    public static final String LABEL_SIN_CLASIFICAR = "Sin clasificar";

    /** {@code product.cincho_type} que mapea a {@code *_REVERSIBLE}. */
    public static final String CINCHO_TYPE_REVERSIBLE = ProductCinchoType.REVERSIBLE;

    public static final Subgroup UNCLASSIFIED = new Subgroup(SIN_CLASIFICAR, LABEL_SIN_CLASIFICAR);

    private KioskSalesCategorySubgroup() {
    }

    public record Subgroup(String code, String label) {
    }

    /** Misma función para ventas, devoluciones y unidades de cambio, con los atributos actuales del producto. */
    public static Subgroup classify(ProductEntity product) {
        if (KioscoInventoryInitRules.isPackagingProduct(product)) {
            return new Subgroup(EMPAQUE, LABEL_EMPAQUE);
        }
        if (KioscoInventoryInitRules.isWalletProduct(product)) {
            return walletSubgroup(product);
        }
        if (KioscoInventoryInitRules.isCinchoProduct(product)) {
            return cinchoSubgroup(product);
        }
        return null;
    }

    private static Subgroup walletSubgroup(ProductEntity product) {
        String audience = ProductAudienceCategory.normalizeProductAudience(product.getAudienceCategory());
        if (ProductAudienceCategory.CABALLERO.equals(audience)) {
            return new Subgroup(CABALLERO, LABEL_CABALLERO);
        }
        if (ProductAudienceCategory.DAMA.equals(audience)) {
            return new Subgroup(DAMA, LABEL_DAMA);
        }
        return UNCLASSIFIED;
    }

    /**
     * Orden: Niño (kids / JR) → CASUAL → REVERSIBLE → Sin clasificar.
     * UNISEX o audiencia nula, y DAMA/CABALLERO sin tipo, caen en Sin clasificar.
     */
    private static Subgroup cinchoSubgroup(ProductEntity product) {
        if (KioscoInventoryInitRules.isKidsCinchoProduct(product)) {
            return new Subgroup(NINO, LABEL_NINO);
        }
        String audience = ProductAudienceCategory.normalizeProductAudience(product.getAudienceCategory());
        boolean dama = ProductAudienceCategory.DAMA.equals(audience);
        boolean caballero = ProductAudienceCategory.CABALLERO.equals(audience);
        if (!dama && !caballero) {
            return UNCLASSIFIED;
        }
        String type = ProductCinchoType.normalizeCinchoType(product.getCinchoType());
        if (ProductCinchoType.CASUAL.equals(type)) {
            return dama
                    ? new Subgroup(DAMA_CASUAL, LABEL_DAMA_CASUAL)
                    : new Subgroup(CABALLERO_CASUAL, LABEL_CABALLERO_CASUAL);
        }
        if (CINCHO_TYPE_REVERSIBLE.equals(type)) {
            return dama
                    ? new Subgroup(DAMA_REVERSIBLE, LABEL_DAMA_REVERSIBLE)
                    : new Subgroup(CABALLERO_REVERSIBLE, LABEL_CABALLERO_REVERSIBLE);
        }
        return UNCLASSIFIED;
    }
}
