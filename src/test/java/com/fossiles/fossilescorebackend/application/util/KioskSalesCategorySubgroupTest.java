package com.fossiles.fossilescorebackend.application.util;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KioskSalesCategorySubgroupTest {

    @Test
    void formalMappingIsTheReversibleSwitch() {
        assertThat(KioskSalesCategorySubgroup.CINCHO_TYPE_TREATED_AS_FORMAL)
                .isEqualTo(ProductCinchoType.REVERSIBLE);
    }

    @Test
    void walletSplitsByAudienceAndUnisexOrNullIsUnclassified() {
        assertSubgroup(wallet("CABALLERO"), "CABALLERO", "Caballero");
        assertSubgroup(wallet("DAMA"), "DAMA", "Dama");
        assertSubgroup(wallet("UNISEX"), "SIN_CLASIFICAR", "Sin clasificar");
        assertSubgroup(wallet(null), "SIN_CLASIFICAR", "Sin clasificar");
    }

    @Test
    void kidsCinchoTakesPrecedenceOverAudienceAndType() {
        ProductEntity kids = cincho("FOSS-KIDS", "DAMA", ProductCinchoType.CASUAL, true);
        assertSubgroup(kids, "NINO", "Niño");
        ProductEntity jr = cincho("N-113-JR", "CABALLERO", ProductCinchoType.REVERSIBLE, false);
        assertSubgroup(jr, "NINO", "Niño");
    }

    @Test
    void casualAndReversibleMapWithAudience() {
        assertSubgroup(cincho("FOSS-DC", "DAMA", ProductCinchoType.CASUAL, false),
                "DAMA_CASUAL", "Dama casual");
        assertSubgroup(cincho("FOSS-CC", "CABALLERO", ProductCinchoType.CASUAL, false),
                "CABALLERO_CASUAL", "Caballero casual");
        assertSubgroup(cincho("FOSS-DF", "DAMA", ProductCinchoType.REVERSIBLE, false),
                "DAMA_FORMAL", "Dama formal");
        assertSubgroup(cincho("FOSS-CF", "CABALLERO", ProductCinchoType.REVERSIBLE, false),
                "CABALLERO_FORMAL", "Caballero formal");
    }

    @Test
    void unisexOrNullAudienceAndNullTypeGoToSinClasificar() {
        assertSubgroup(cincho("FOSS-U", "UNISEX", ProductCinchoType.CASUAL, false),
                "SIN_CLASIFICAR", "Sin clasificar");
        assertSubgroup(cincho("FOSS-N", null, ProductCinchoType.CASUAL, false),
                "SIN_CLASIFICAR", "Sin clasificar");
        assertSubgroup(cincho("FOSS-DAMA-NULL", "DAMA", null, false),
                "SIN_CLASIFICAR", "Sin clasificar");
        assertSubgroup(cincho("FOSS-CAB-NULL", "CABALLERO", null, false),
                "SIN_CLASIFICAR", "Sin clasificar");
    }

    @Test
    void otherProductsHaveNullSubgroup() {
        ProductEntity bag = ProductEntity.builder()
                .code("BOL-1")
                .name("Bolso")
                .audienceCategory("DAMA")
                .build();
        assertThat(KioskSalesCategorySubgroup.classify(bag)).isNull();
        assertThat(KioskSalesCategorySubgroup.classify(null)).isNull();
    }

    private static ProductEntity wallet(String audience) {
        return ProductEntity.builder()
                .code("BILL-1")
                .name("Billetera")
                .audienceCategory(audience)
                .build();
    }

    private static ProductEntity cincho(String code, String audience, String cinchoType, boolean kids) {
        return ProductEntity.builder()
                .code(code)
                .name("Cincho")
                .audienceCategory(audience)
                .cinchoType(cinchoType)
                .cinchoForKids(kids)
                .build();
    }

    private static void assertSubgroup(ProductEntity product, String code, String label) {
        KioskSalesCategorySubgroup.Subgroup subgroup = KioskSalesCategorySubgroup.classify(product);
        assertThat(subgroup).isNotNull();
        assertThat(subgroup.code()).isEqualTo(code);
        assertThat(subgroup.label()).isEqualTo(label);
    }
}
