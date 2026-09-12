package com.fossiles.fossilescorebackend.application.util;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class EntrecuerosPriceListsTest {

    @Test
    void ninoAndDamaDoNotShareVolume() {
        ProductEntity cincho = ProductEntity.builder()
                .id(10L)
                .name("Cincho casual")
                .cinchoType("CASUAL")
                .build();

        assertThat(EntrecuerosPriceLists.kind(cincho, "NINO")).isEqualTo(EntrecuerosPriceLists.Kind.NINO);
        assertThat(EntrecuerosPriceLists.kind(cincho, "DAMA")).isEqualTo(EntrecuerosPriceLists.Kind.DAMA);
        assertThat(EntrecuerosPriceLists.volumeKey(10L, cincho, "NINO"))
                .isNotEqualTo(EntrecuerosPriceLists.volumeKey(10L, cincho, "DAMA"));
        assertThat(EntrecuerosPriceLists.resolveUnitPrice(cincho, "NINO", new BigDecimal("3")))
                .isEqualByComparingTo("45.00");
        assertThat(EntrecuerosPriceLists.resolveUnitPrice(cincho, "DAMA", new BigDecimal("3")))
                .isEqualByComparingTo("60.00");
    }

    @Test
    void walletMaterialSplitsLeatherAndSynthetic() {
        ProductEntity wallet = ProductEntity.builder()
                .id(11L)
                .name("Billetera clasica")
                .build();

        assertThat(EntrecuerosPriceLists.kind(wallet, "LEVIS"))
                .isEqualTo(EntrecuerosPriceLists.Kind.WALLET_LEATHER);
        assertThat(EntrecuerosPriceLists.kind(wallet, "SINTETICO:LEVIS"))
                .isEqualTo(EntrecuerosPriceLists.Kind.WALLET_SYNTHETIC);
        assertThat(EntrecuerosPriceLists.resolveUnitPrice(wallet, "LEVIS", BigDecimal.ONE))
                .isEqualByComparingTo("100.00");
        assertThat(EntrecuerosPriceLists.resolveUnitPrice(wallet, "SINTETICO:LEVIS", BigDecimal.ONE))
                .isEqualByComparingTo("40.00");
    }

    @Test
    void reversibleIsFixedAndCardholderHasOwnScale() {
        ProductEntity reversible = ProductEntity.builder()
                .name("Cincho reversible")
                .cinchoType("REVERSIBLE")
                .build();
        ProductEntity cardholder = ProductEntity.builder()
                .name("Tarjetero sintetico")
                .build();

        assertThat(EntrecuerosPriceLists.resolveUnitPrice(reversible, "DAMA", new BigDecimal("12")))
                .isEqualByComparingTo("100.00");
        assertThat(EntrecuerosPriceLists.resolveUnitPrice(reversible, "NINO", new BigDecimal("8")))
                .isEqualByComparingTo("100.00");
        assertThat(EntrecuerosPriceLists.resolveUnitPrice(cardholder, "SINTETICO:NAUTICA", new BigDecimal("3")))
                .isEqualByComparingTo("6.00");
    }
}
