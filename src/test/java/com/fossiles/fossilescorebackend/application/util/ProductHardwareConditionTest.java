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
        assertThat(ProductHardwareCondition.label("NINO")).isEqualTo("Niño");
        assertThat(ProductHardwareCondition.label("NINA")).isEqualTo("Niña");
        assertThat(ProductHardwareCondition.label("SINTETICO")).isEqualTo("Sintética");
        assertThat(ProductHardwareCondition.label("SINTETICO:LEVIS")).isEqualTo("Sintética · LEVIS");
    }

    @Test
    void appendMaterialToName_addsSyntheticAndBrand() {
        assertThat(ProductHardwareCondition.appendMaterialToName("Billetera Megan", "LEVIS"))
                .isEqualTo("Billetera Megan LEVIS");
        assertThat(ProductHardwareCondition.appendMaterialToName("Billetera Megan", "SINTETICO:LEVIS"))
                .isEqualTo("Billetera Megan Sintética LEVIS");
        assertThat(ProductHardwareCondition.appendMaterialToName("Billetera Megan Sintética LEVIS", "SINTETICO:LEVIS"))
                .isEqualTo("Billetera Megan Sintética LEVIS");
    }

    @Test
    void resolveWalletDimension_requiresBrandAndDefaultsNonSynthetic() {
        assertThat(ProductHardwareCondition.resolveWalletDimension(null)).isNull();
        assertThat(ProductHardwareCondition.resolveWalletDimension("NUEVO")).isNull();
        assertThat(ProductHardwareCondition.resolveWalletDimension("levis")).isEqualTo("LEVIS");
        assertThat(ProductHardwareCondition.resolveWalletDimension("SINTETICO:levis")).isEqualTo("SINTETICO:LEVIS");
        assertThat(ProductHardwareCondition.resolveWalletDimension("SINTETICO")).isNull();
    }
}
