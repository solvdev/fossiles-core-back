package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.infrastructure.config.FelEmissionProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FelAnulacionXmlBuilderTest {

    @Test
    void usesProvidedOriginalEmissionDateTimeLiteral() {
        FelEmissionProperties props = new FelEmissionProperties();
        props.setNitEmisor("11700874K");

        String xml = new FelAnulacionXmlBuilder(props).buildUnsignedAnulacionXml(
                "A1B2C3D4-E5F6-7890-ABCD-EF1234567890",
                "11700874K",
                "CF",
                "2026-06-17T15:42:11.123-06:00",
                "Error en venta"
        );

        assertThat(xml).contains("FechaEmisionDocumentoAnular=\"2026-06-17T15:42:11.123-06:00\"");
        assertThat(xml).doesNotContain("2026-06-26");
    }
}
