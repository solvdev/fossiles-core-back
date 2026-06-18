package com.fossiles.fossilescorebackend.infrastructure.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FelTaxpayerNameFormatterTest {

    @Test
    void format_personNatural_apellidosThenNombres() {
        assertThat(FelTaxpayerNameFormatter.format("IXCAJÓ,QUELEX,,ANTHONY,LUIS,DAVID"))
                .isEqualTo("ANTHONY LUIS DAVID IXCAJÓ QUELEX");
    }

    @Test
    void format_empresa_joinsCommas() {
        assertThat(FelTaxpayerNameFormatter.format("CUEROGLAM, SOCIEDAD ANÓNIMA"))
                .isEqualTo("CUEROGLAM SOCIEDAD ANÓNIMA");
    }

    @Test
    void format_singleApellido_doubleComma() {
        assertThat(FelTaxpayerNameFormatter.format("IXCAJÓ,,ANTHONY"))
                .isEqualTo("ANTHONY IXCAJÓ");
    }
}
