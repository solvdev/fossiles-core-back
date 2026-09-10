package com.fossiles.fossilescorebackend.application.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductHardwareConditionTest {

    @Test
    void normalize_onlyMapsHardware() {
        assertThat(ProductHardwareCondition.normalize("nuevo")).isEqualTo("NUEVO");
        assertThat(ProductHardwareCondition.normalize("VIEJO")).isEqualTo("VIEJO");
        assertThat(ProductHardwareCondition.normalize("LEVIS")).isNull();
    }

    @Test
    void normalizeStockDimension_keepsBrand() {
        assertThat(ProductHardwareCondition.normalizeStockDimension("levis")).isEqualTo("LEVIS");
        assertThat(ProductHardwareCondition.normalizeStockDimension("tommy  hilfiger"))
                .isEqualTo("TOMMY HILFIGER");
        assertThat(ProductHardwareCondition.normalizeStockDimension(null)).isEqualTo("NUEVO");
    }

    @Test
    void label_showsBrandName() {
        assertThat(ProductHardwareCondition.label("LEVIS")).isEqualTo("LEVIS");
        assertThat(ProductHardwareCondition.label("NUEVO")).isEqualTo("Herraje nuevo");
    }
}
