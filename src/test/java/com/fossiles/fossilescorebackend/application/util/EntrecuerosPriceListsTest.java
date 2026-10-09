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
        ProductEntity otherWallet = ProductEntity.builder()
                .id(12L)
                .name("Billetera otra")
                .build();
        assertThat(EntrecuerosPriceLists.volumeKey(11L, wallet, "SINTETICO:ABERCROMBIE"))
                .isEqualTo(EntrecuerosPriceLists.volumeKey(12L, otherWallet, "SINTETICO:ABERCROMBIE"));
        assertThat(EntrecuerosPriceLists.resolveUnitPrice(otherWallet, "SINTETICO:ABERCROMBIE", new BigDecimal("4")))
                .isEqualByComparingTo("30.00");
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

    @Test
    void dozenOrHalfDozenUnlocksLowestPriceOnOtherProducts() {
        ProductEntity cincho = ProductEntity.builder()
                .id(1L)
                .name("Cincho casual")
                .cinchoType("CASUAL")
                .build();
        ProductEntity wallet = ProductEntity.builder()
                .id(2L)
                .name("Billetera clasica")
                .build();

        assertThat(EntrecuerosPriceLists.unlocksWholesale(java.util.Map.of(
                "CASUAL", new BigDecimal("12")))).isTrue();
        assertThat(EntrecuerosPriceLists.unlocksWholesale(java.util.Map.of(
                "CASUAL", new BigDecimal("6")))).isTrue();
        assertThat(EntrecuerosPriceLists.unlocksWholesale(java.util.Map.of(
                "CASUAL", new BigDecimal("5")))).isFalse();

        java.util.Map<String, BigDecimal> dozen = volume(cincho, "NUEVO", 12, wallet, "LEVIS", 1);
        assertThat(EntrecuerosPriceLists.resolveChargedUnitPrice(cincho, "NUEVO", new BigDecimal("12"), dozen))
                .isEqualByComparingTo("75.00");
        assertThat(EntrecuerosPriceLists.resolveChargedUnitPrice(wallet, "LEVIS", BigDecimal.ONE, dozen))
                .isEqualByComparingTo("55.00");

        java.util.Map<String, BigDecimal> halfDozen = volume(cincho, "NUEVO", 6, wallet, "LEVIS", 1);
        assertThat(EntrecuerosPriceLists.resolveChargedUnitPrice(cincho, "NUEVO", new BigDecimal("6"), halfDozen))
                .isEqualByComparingTo("80.00");
        assertThat(EntrecuerosPriceLists.resolveChargedUnitPrice(wallet, "LEVIS", BigDecimal.ONE, halfDozen))
                .isEqualByComparingTo("55.00");

        java.util.Map<String, BigDecimal> below = volume(cincho, "NUEVO", 5, wallet, "LEVIS", 1);
        assertThat(EntrecuerosPriceLists.resolveChargedUnitPrice(wallet, "LEVIS", BigDecimal.ONE, below))
                .isEqualByComparingTo("100.00");
        assertThat(EntrecuerosPriceLists.resolveUnitPrice(wallet, "LEVIS", BigDecimal.ONE))
                .isEqualByComparingTo("100.00");
    }

    @Test
    void cinchoForKidsDoesNotChangeCasualPricingYet() {
        ProductEntity kids = ProductEntity.builder()
                .id(1L)
                .name("Cincho casual")
                .cinchoType("CASUAL")
                .cinchoForKids(true)
                .build();

        assertThat(EntrecuerosPriceLists.kind(kids, "NUEVO")).isEqualTo(EntrecuerosPriceLists.Kind.CASUAL);
        assertThat(EntrecuerosPriceLists.resolveUnitPrice(kids, "NUEVO", BigDecimal.ONE))
                .isEqualByComparingTo("100.00");
    }

    @Test
    void b1CodeIsExactMatch() {
        assertThat(EntrecuerosPriceLists.isExactB1Code(productCode("B-1"))).isTrue();
        assertThat(EntrecuerosPriceLists.isExactB1Code(productCode(" b1 "))).isTrue();
        assertThat(EntrecuerosPriceLists.isExactB1Code(productCode("B-10"))).isFalse();
        assertThat(EntrecuerosPriceLists.isExactB1Code(productCode("B-19"))).isFalse();
        assertThat(EntrecuerosPriceLists.isExactB1Code(productCode("B-100"))).isFalse();
    }

    private static ProductEntity productCode(String code) {
        return ProductEntity.builder().code(code).name("Billetera").build();
    }

    private static java.util.Map<String, BigDecimal> volume(
            ProductEntity first,
            String firstHardware,
            int firstQty,
            ProductEntity second,
            String secondHardware,
            int secondQty
    ) {
        java.util.Map<String, BigDecimal> qty = new java.util.HashMap<>();
        EntrecuerosPriceLists.addVolumeQuantity(
                qty, first.getId(), first, firstHardware, new BigDecimal(firstQty));
        EntrecuerosPriceLists.addVolumeQuantity(
                qty, second.getId(), second, secondHardware, new BigDecimal(secondQty));
        return qty;
    }
}
