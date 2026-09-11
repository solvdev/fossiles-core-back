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
        assertThat(ProductHardwareCondition.label("sintetica")).isEqualTo("Sintética");
        assertThat(ProductHardwareCondition.label("NO_SINTETICO")).isEqualTo("No sintética");
    }

    @Test
    void appendMaterialToName_distinguishesSynthetic() {
        assertThat(ProductHardwareCondition.appendMaterialToName("Billetera Megan", "SINTETICO"))
                .isEqualTo("Billetera Megan Sintética");
        assertThat(ProductHardwareCondition.appendMaterialToName("Billetera Megan", "NO_SINTETICO"))
                .isEqualTo("Billetera Megan No sintética");
        assertThat(ProductHardwareCondition.appendMaterialToName("Billetera Megan Sintética", "SINTETICO"))
                .isEqualTo("Billetera Megan Sintética");
        assertThat(ProductHardwareCondition.appendMaterialToName("Billetera Megan No sintética", "NO_SINTETICO"))
                .isEqualTo("Billetera Megan No sintética");
    }

    @Test
    void resolveWalletMaterial_defaultsSyntheticAndKeepsNonSynthetic() {
        assertThat(ProductHardwareCondition.resolveWalletMaterial(null)).isEqualTo("SINTETICO");
        assertThat(ProductHardwareCondition.resolveWalletMaterial("NUEVO")).isEqualTo("SINTETICO");
        assertThat(ProductHardwareCondition.resolveWalletMaterial("no sintetica")).isEqualTo("NO_SINTETICO");
        assertThat(ProductHardwareCondition.resolveWalletMaterial("LEVIS")).isNull();
    }
}
