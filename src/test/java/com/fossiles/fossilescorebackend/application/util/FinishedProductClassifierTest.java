package com.fossiles.fossilescorebackend.application.util;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FinishedProductClassifierTest {

    @Test
    void sumPrefixIsPackaging() {
        assertThat(FinishedProductClassifier.isPackaging("SUM-BOLSA")).isTrue();
        assertThat(FinishedProductClassifier.isPackaging(" sum-caja ")).isTrue();
        assertThat(FinishedProductClassifier.isPackaging("SUM001")).isTrue();
    }

    @Test
    void otherCodesBlankAndNullAreFinished() {
        assertThat(FinishedProductClassifier.isPackaging("CIN-01")).isFalse();
        assertThat(FinishedProductClassifier.isPackaging("")).isFalse();
        assertThat(FinishedProductClassifier.isPackaging((String) null)).isFalse();
        assertThat(FinishedProductClassifier.isFinished(null, null, Map.of())).isTrue();
    }

    @Test
    void itemCodeWinsOverCatalogCode() {
        Map<Long, String> catalog = Map.of(7L, "SUM-BOLSA");
        assertThat(FinishedProductClassifier.isPackaging("CIN-01", 7L, catalog)).isFalse();
        assertThat(FinishedProductClassifier.isPackaging("SUM-X", 99L, catalog)).isTrue();
    }

    @Test
    void blankItemCodeFallsBackToProductIdCatalog() {
        Map<Long, String> catalog = Map.of(7L, "SUM-BOLSA", 8L, "CIN-01");
        assertThat(FinishedProductClassifier.isPackaging(null, 7L, catalog)).isTrue();
        assertThat(FinishedProductClassifier.isPackaging("  ", 7L, catalog)).isTrue();
        assertThat(FinishedProductClassifier.isPackaging(null, 8L, catalog)).isFalse();
        assertThat(FinishedProductClassifier.isFinished(null, 8L, catalog)).isTrue();
    }

    @Test
    void unresolvableItemIsFinished() {
        assertThat(FinishedProductClassifier.isPackaging(null, 123L, Map.of())).isFalse();
        assertThat(FinishedProductClassifier.isPackaging(null, null, Map.of(1L, "SUM-A"))).isFalse();
        assertThat(FinishedProductClassifier.isPackaging(null, 1L, null)).isFalse();
    }

    @Test
    void vendorItemUsesProductCatalogCode() {
        Map<Long, ProductEntity> products = Map.of(
                1L, ProductEntity.builder().id(1L).code("SUM-CAJA").build(),
                2L, ProductEntity.builder().id(2L).code("BIL-01").build());
        assertThat(FinishedProductClassifier.isPackaging(1L, products)).isTrue();
        assertThat(FinishedProductClassifier.isPackaging(2L, products)).isFalse();
        assertThat(FinishedProductClassifier.isPackaging(3L, products)).isFalse();
        assertThat(FinishedProductClassifier.isPackaging((Long) null, products)).isFalse();
    }

    @Test
    void codesByIdMapsProducts() {
        Map<Long, String> codes = FinishedProductClassifier.codesById(List.of(
                ProductEntity.builder().id(1L).code("SUM-CAJA").build(),
                ProductEntity.builder().id(2L).code("BIL-01").build()));
        assertThat(codes).containsEntry(1L, "SUM-CAJA").containsEntry(2L, "BIL-01");
    }

    @Test
    void baseProductNameStripsSizeSuffix() {
        assertThat(FinishedProductClassifier.baseProductName("Cincho Cuero T.42")).isEqualTo("Cincho Cuero");
        assertThat(FinishedProductClassifier.baseProductName("Billetera")).isEqualTo("Billetera");
        assertThat(FinishedProductClassifier.baseProductName(null)).isNull();
    }
}
