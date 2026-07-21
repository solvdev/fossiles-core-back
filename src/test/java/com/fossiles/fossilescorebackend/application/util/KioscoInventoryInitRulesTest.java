package com.fossiles.fossilescorebackend.application.util;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KioscoInventoryInitRulesTest {

    private static List<Long> fullCatalog() {
        return LongStream.rangeClosed(1, 42).boxed().collect(Collectors.toList());
    }

    @Test
    void regularProduct_excludesComboColors() {
        ProductEntity bag = ProductEntity.builder()
                .id(10L)
                .code("BOL-001")
                .name("Bolso")
                .build();

        List<Long> colors = KioscoInventoryInitRules.resolveColorIds(bag, fullCatalog());

        assertThat(colors).doesNotContain(37L, 38L, 39L);
        assertThat(colors).contains(2L, 3L, 13L);
    }

    @Test
    void cinchoProduct_usesSixColors() {
        ProductEntity cincho = ProductEntity.builder()
                .id(20L)
                .code("FOSS-001")
                .name("Cincho casual")
                .cinchoType("CASUAL")
                .cinchoForKids(false)
                .build();

        List<Long> colors = KioscoInventoryInitRules.resolveColorIds(cincho, fullCatalog());

        assertThat(colors).containsExactly(2L, 3L, 13L, 37L, 38L, 39L);
    }

    @Test
    void cinchoKids_hasEvenSizes16To30() {
        ProductEntity cincho = ProductEntity.builder()
                .code("FOSS-KIDS")
                .cinchoType("CASUAL")
                .cinchoForKids(true)
                .build();

        assertThat(KioscoInventoryInitRules.resolveCinchoSizes(cincho))
                .containsExactly("16", "18", "20", "22", "24", "26", "28", "30");
    }

    @Test
    void cinchoAdult_hasEvenSizes30To46() {
        ProductEntity cincho = ProductEntity.builder()
                .code("FOSS-ADULT")
                .cinchoType("REVERSIBLE")
                .cinchoForKids(false)
                .build();

        assertThat(KioscoInventoryInitRules.resolveCinchoSizes(cincho))
                .containsExactly("30", "32", "34", "36", "38", "40", "42", "46");
    }

    @Test
    void packagingProduct_isExcludedFromColorVariants() {
        ProductEntity packaging = ProductEntity.builder()
                .code("SUM-001")
                .name("Empaque")
                .build();

        assertThatThrownBy(() -> KioscoInventoryInitRules.resolveColorIds(packaging, fullCatalog()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SUM-");
        assertThat(KioscoInventoryInitRules.buildZeroSizesData(packaging)).isNull();
        assertThat(KioscoInventoryInitRules.isPackagingProduct(packaging)).isTrue();
    }

    @Test
    void cinchoProduct_buildsZeroSizesJson() {
        ProductEntity cincho = ProductEntity.builder()
                .code("FOSS-001")
                .cinchoType("CASUAL")
                .cinchoForKids(false)
                .build();

        String json = KioscoInventoryInitRules.buildZeroSizesData(cincho);

        assertThat(json).contains("\"32\":0");
        assertThat(json).contains("\"42\":0");
    }

    @Test
    void stockColorKey_treatsLegacyNullHardwareAsSameSlot() {
        assertThat(KioscoInventoryInitRules.stockInitKey(1L, 10L, 2L, null))
                .isEqualTo(KioscoInventoryInitRules.stockInitKey(1L, 10L, 2L, "NUEVO"));
    }
}
