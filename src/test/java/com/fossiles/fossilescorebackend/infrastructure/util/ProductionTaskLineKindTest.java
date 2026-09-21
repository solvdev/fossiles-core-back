package com.fossiles.fossilescorebackend.infrastructure.util;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionTaskLineKindTest {

    @Test
    void cinchoAndCarteraAreDifferentFamilies() {
        ProductEntity cartera = ProductEntity.builder().id(1L).code("P-10").name("Cartera").build();
        ProductEntity cincho = ProductEntity.builder().id(2L).code("FOSS-1").name("Cincho casual").cinchoType("CASUAL").build();
        ProductEntity pack = ProductEntity.builder().id(3L).code("SUM-1").name("Empaque cincho").build();

        assertThat(CinchoProductUtils.isCinchoLineForProduction(cartera)).isFalse();
        assertThat(CinchoProductUtils.isCinchoLineForProduction(cincho)).isTrue();
        assertThat(CinchoProductUtils.isCinchoLineForProduction(pack)).isFalse();
    }
}
