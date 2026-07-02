package com.fossiles.fossilescorebackend.infrastructure.util;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaxInvoiceEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class FelEmissionDateResolverTest {

    @Test
    void usesExactFechaHoraEmisionFromCertifiedXml() {
        String certifiedXml = """
                <dte:GTDocumento>
                  <dte:DatosGenerales CodigoMoneda="GTQ" FechaHoraEmision="2026-06-17T15:42:11.123-06:00" Tipo="FACT"/>
                </dte:GTDocumento>
                """;
        TaxInvoiceEntity invoice = TaxInvoiceEntity.builder()
                .felCertifiedXml(certifiedXml)
                .felCertifiedAt(LocalDateTime.of(2026, 6, 26, 10, 0))
                .issuedAt(LocalDateTime.of(2026, 6, 26, 10, 0))
                .build();

        assertThat(FelEmissionDateResolver.resolveAnnulmentEmissionDateTime(invoice))
                .isEqualTo("2026-06-17T15:42:11.123-06:00");
    }

    @Test
    void fallsBackToIssuedAtWhenXmlMissing() {
        TaxInvoiceEntity invoice = TaxInvoiceEntity.builder()
                .issuedAt(LocalDateTime.of(2026, 6, 17, 15, 42, 11, 123_000_000))
                .build();

        assertThat(FelEmissionDateResolver.resolveAnnulmentEmissionDateTime(invoice))
                .isEqualTo("2026-06-17T15:42:11.123-06:00");
    }

    @Test
    void extractsEmissionFromXml() {
        String xml = "<dte:DatosGenerales FechaHoraEmision=\"2026-06-09T08:00:00.000-06:00\" Tipo=\"FACT\"/>";
        assertThat(FelEmissionDateResolver.extractEmissionDateTimeFromCertifiedXml(xml))
                .isEqualTo("2026-06-09T08:00:00.000-06:00");
    }
}
