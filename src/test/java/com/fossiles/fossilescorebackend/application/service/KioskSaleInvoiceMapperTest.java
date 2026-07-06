package com.fossiles.fossilescorebackend.application.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KioskSaleInvoiceMapperTest {

    @Test
    void emitsAutomaticallyForNit() {
        assertThat(KioskSaleInvoiceMapper.shouldEmitForPos("1234567-8", false)).isTrue();
        assertThat(KioskSaleInvoiceMapper.shouldEmitForPos("12345678", null)).isTrue();
    }

    @Test
    void alwaysEmitsForPosIncludingCf() {
        assertThat(KioskSaleInvoiceMapper.shouldEmitForPos("CF", false)).isTrue();
        assertThat(KioskSaleInvoiceMapper.shouldEmitForPos("CF", null)).isTrue();
        assertThat(KioskSaleInvoiceMapper.shouldEmitForPos("CF", true)).isTrue();
        assertThat(KioskSaleInvoiceMapper.shouldEmitForPos("c/f", false)).isTrue();
    }

    @Test
    void normalizesTaxId() {
        assertThat(KioskSaleInvoiceMapper.normalizeTaxId(" cf ")).isEqualTo("CF");
        assertThat(KioskSaleInvoiceMapper.normalizeTaxId("1234-56789")).isEqualTo("123456789");
    }
}
