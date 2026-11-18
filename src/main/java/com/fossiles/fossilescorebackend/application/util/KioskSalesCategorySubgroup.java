package com.fossiles.fossilescorebackend.application.util;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;

/**
 * Subgrupos del reporte de ventas de kiosko por categoría.
 *
 * <p>Billeteras y cinchos se identifican por producto ({@link KioscoInventoryInitRules#isWalletProduct},
 * {@link KioscoInventoryInitRules#isCinchoProduct}), no por código de {@code product_category}.
 * El resto de productos deja ambos campos nulos.
 */
public final class KioskSalesCategorySubgroup {

    public static final String CABALLERO = "CABALLERO";
    public static final String DAMA = "DAMA";
    public static final String NINO = "NINO";
    public static final String DAMA_CASUAL = "DAMA_CASUAL";
    public static final String DAMA_FORMAL = "DAMA_FORMAL";
    public static final String CABALLERO_CASUAL = "CABALLERO_CASUAL";
    public static final String CABALLERO_FORMAL = "CABALLERO_FORMAL";
    public static final String SIN_CLASIFICAR = "SIN_CLASIFICAR";

    public static final String LABEL_CABALLERO = "Caballero";
    public static final String LABEL_DAMA = "Dama";
    public static final String LABEL_NINO = "Niño";
    public static final String LABEL_DAMA_CASUAL = "Dama casual";
    public static final String LABEL_DAMA_FORMAL = "Dama formal";
    public static final String LABEL_CABALLERO_CASUAL = "Caballero casual";
    public static final String LABEL_CABALLERO_FORMAL = "Caballero formal";
    public static final String LABEL_SIN_CLASIFICAR = "Sin clasificar";

    /**
     * PROVISIONAL: {@code product.cincho_type} solo tiene CASUAL / REVERSIBLE; no existe FORMAL.
     * Este valor se trata como FORMAL. Un cambio de una línea cuando Eduardo confirme.
     */
    public static final String CINCHO_TYPE_TREATED_AS_FORMAL = ProductCinchoType.REVERSIBLE;

    public static final Subgroup UNCLASSIFIED = new Subgroup(SIN_CLASIFICAR, LABEL_SIN_CLASIFICAR);

    private KioskSalesCategorySubgroup() {
    }

    public record Subgroup(String code, String label) {
    }

    /** Misma función para ventas, devoluciones y unidades de cambio, con los atributos actuales del producto. */
    public static Subgroup classify(ProductEntity product) {
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
     * Orden: Niño (kids / JR) → CASUAL → REVERSIBLE=FORMAL → Sin clasificar.
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
        if (CINCHO_TYPE_TREATED_AS_FORMAL.equals(type)) {
            return dama
                    ? new Subgroup(DAMA_FORMAL, LABEL_DAMA_FORMAL)
                    : new Subgroup(CABALLERO_FORMAL, LABEL_CABALLERO_FORMAL);
        }
        return UNCLASSIFIED;
    }
}
