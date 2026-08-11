package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.infrastructure.config.FelEmissionProperties;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class FelAnulacionXmlBuilderTest {

    private static final ZoneId GUATEMALA = ZoneId.of("America/Guatemala");

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

    @Test
    void usesFixedAnnulmentClockForFechaHoraAnulacion() {
        FelEmissionProperties props = new FelEmissionProperties();
        props.setNitEmisor("11700874K");
        ZonedDateTime fixed = ZonedDateTime.of(LocalDateTime.of(2026, 8, 9, 14, 30, 45, 123_000_000), GUATEMALA);

        String xml = new FelAnulacionXmlBuilder(props).buildUnsignedAnulacionXml(
                "A1B2C3D4-E5F6-7890-ABCD-EF1234567890",
                "11700874K",
                "CF",
                "2026-08-08T10:00:00.000-06:00",
                "Corrección",
                fixed
        );

        assertThat(xml).contains("FechaHoraAnulacion=\"2026-08-09T14:30:45.123-06:00\"");
        assertThat(xml).contains("FechaEmisionDocumentoAnular=\"2026-08-08T10:00:00.000-06:00\"");
    }
}
