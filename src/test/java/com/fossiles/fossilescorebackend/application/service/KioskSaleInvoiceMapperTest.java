package com.fossiles.fossilescorebackend.application.service;

import org.junit.jupiter.api.Test;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSaleItemEntity;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KioskSaleInvoiceMapperTest {

    private final KioskSaleInvoiceMapper mapper = new KioskSaleInvoiceMapper();

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

    @Test
    void includesZeroValueLinesWhenQuantityPositive() {
        KioskSaleEntity sale = KioskSaleEntity.builder()
                .id(10L)
                .saleNumber("POS-20260721-0001")
                .subtotal(new BigDecimal("100.00"))
                .totalAmount(new BigDecimal("100.00"))
                .items(List.of(
                        KioskSaleItemEntity.builder()
                                .productCode("BOL-01")
                                .productName("Bolso")
                                .quantity(new BigDecimal("1"))
                                .unitPrice(new BigDecimal("100.00"))
                                .lineTotal(new BigDecimal("100.00"))
                                .build(),
                        KioskSaleItemEntity.builder()
                                .productCode("SUM-01")
                                .productName("Empaque")
                                .quantity(new BigDecimal("1"))
                                .unitPrice(new BigDecimal("5.00"))
                                .lineTotal(BigDecimal.ZERO)
                                .build()
                ))
                .build();

        var document = mapper.fromSale(sale);

        assertThat(document.getLines()).hasSize(2);
        assertThat(document.getLines().get(1).getLineTotal()).isEqualByComparingTo("0.00");
        assertThat(document.getLines().get(1).getUnitPrice()).isEqualByComparingTo("5.00");
    }
}
