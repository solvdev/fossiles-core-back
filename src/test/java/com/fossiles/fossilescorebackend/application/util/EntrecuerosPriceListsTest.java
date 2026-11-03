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
                .isEqualByComparingTo("55.00");
        assertThat(EntrecuerosPriceLists.resolveUnitPrice(cincho, "DAMA", new BigDecimal("3")))
                .isEqualByComparingTo("70.00");
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
                .isEqualByComparingTo("125.00");
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
                .isEqualByComparingTo("125.00");
        assertThat(EntrecuerosPriceLists.resolveUnitPrice(reversible, "NINO", new BigDecimal("8")))
                .isEqualByComparingTo("125.00");
        assertThat(EntrecuerosPriceLists.resolveUnitPrice(cardholder, "SINTETICO:NAUTICA", new BigDecimal("3")))
                .isEqualByComparingTo("10.00");
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
                .isEqualByComparingTo("85.00");
        assertThat(EntrecuerosPriceLists.resolveChargedUnitPrice(wallet, "LEVIS", BigDecimal.ONE, dozen))
                .isEqualByComparingTo("65.00");

        java.util.Map<String, BigDecimal> halfDozen = volume(cincho, "NUEVO", 6, wallet, "LEVIS", 1);
        assertThat(EntrecuerosPriceLists.resolveChargedUnitPrice(cincho, "NUEVO", new BigDecimal("6"), halfDozen))
                .isEqualByComparingTo("95.00");
        assertThat(EntrecuerosPriceLists.resolveChargedUnitPrice(wallet, "LEVIS", BigDecimal.ONE, halfDozen))
                .isEqualByComparingTo("65.00");

        java.util.Map<String, BigDecimal> below = volume(cincho, "NUEVO", 5, wallet, "LEVIS", 1);
        assertThat(EntrecuerosPriceLists.resolveChargedUnitPrice(wallet, "LEVIS", BigDecimal.ONE, below))
                .isEqualByComparingTo("125.00");
        assertThat(EntrecuerosPriceLists.resolveUnitPrice(wallet, "LEVIS", BigDecimal.ONE))
                .isEqualByComparingTo("125.00");
    }

    @Test
    void packagingKindBeatsCinchoSignals() {
        ProductEntity bag = ProductEntity.builder()
                .code("SUM-BOLSA-CINCHO")
                .name("Bolsa para cinchos")
                .build();
        ProductEntity box = ProductEntity.builder()
                .code(" sum-caja ")
                .name("Caja cincho")
                .cinchoType("CASUAL")
                .build();

        assertThat(EntrecuerosPriceLists.kind(bag, null)).isEqualTo(EntrecuerosPriceLists.Kind.PACKAGING);
        assertThat(EntrecuerosPriceLists.kind(box, "NINO")).isEqualTo(EntrecuerosPriceLists.Kind.PACKAGING);
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
                .isEqualByComparingTo("125.00");
    }

    @Test
    void b1CodeIsExactMatch() {
        assertThat(EntrecuerosPriceLists.isExactB1Code(productCode("B-1"))).isTrue();
        assertThat(EntrecuerosPriceLists.isExactB1Code(productCode(" b1 "))).isTrue();
        assertThat(EntrecuerosPriceLists.isExactB1Code(productCode("B-10"))).isFalse();
        assertThat(EntrecuerosPriceLists.isExactB1Code(productCode("B-19"))).isFalse();
        assertThat(EntrecuerosPriceLists.isExactB1Code(productCode("B-100"))).isFalse();
    }

    @Test
    void recosteoBundleAndCodeExceptions() {
        ProductEntity b1 = productCode("B-1");
        assertThat(line(b1, 1)).isEqualByComparingTo("40.00");
        assertThat(line(b1, 2)).isEqualByComparingTo("80.00");
        assertThat(line(b1, 3)).isEqualByComparingTo("130.00");
        assertThat(line(b1, 4)).isEqualByComparingTo("170.00");
        assertThat(line(b1, 6)).isEqualByComparingTo("260.00");
        assertThat(EntrecuerosPriceLists.resolveChargedUnitPrice(
                b1, "SINTETICO", new BigDecimal("3"), volumeOf(b1, 3)))
                .isEqualByComparingTo("43.33");
        assertThat(EntrecuerosPriceLists.resolveChargedUnitPrice(
                b1, "SINTETICO", new BigDecimal("4"), volumeOf(b1, 4)))
                .isEqualByComparingTo("42.50");
        assertThat(EntrecuerosPriceLists.resolveChargedUnitPrice(
                b1, "SINTETICO", new BigDecimal("6"), volumeOf(b1, 6)))
                .isEqualByComparingTo("43.33");

        ProductEntity b5 = productCode("B-5");
        ProductEntity b2 = productCode("B-2");
        assertThat(EntrecuerosPriceLists.resolveUnitPrice(b5, "SINTETICO", new BigDecimal("3")))
                .isEqualByComparingTo("30.00");
        assertThat(EntrecuerosPriceLists.resolveUnitPrice(b5, "SINTETICO", new BigDecimal("4")))
                .isEqualByComparingTo("40.00");
        assertThat(EntrecuerosPriceLists.resolveUnitPrice(b2, "SINTETICO", new BigDecimal("3")))
                .isEqualByComparingTo("30.00");
        assertThat(EntrecuerosPriceLists.resolveUnitPrice(b2, "SINTETICO", new BigDecimal("4")))
                .isEqualByComparingTo("40.00");
        assertThat(EntrecuerosPriceLists.isExactB2OrB5Code(productCode("B5"))).isTrue();
        assertThat(EntrecuerosPriceLists.isExactB2OrB5Code(productCode(" b-2 "))).isTrue();
        assertThat(EntrecuerosPriceLists.isExactB2OrB5Code(productCode("B-50"))).isFalse();
        assertThat(EntrecuerosPriceLists.isExactB2OrB5Code(productCode("B-25"))).isFalse();
        assertThat(EntrecuerosPriceLists.isExactB2OrB5Code(productCode("B-20"))).isFalse();
        assertThat(EntrecuerosPriceLists.isExactB2OrB5Code(productCode("B-1"))).isFalse();
        assertThat(EntrecuerosPriceLists.resolveUnitPrice(productCode("B-20"), "SINTETICO", new BigDecimal("4")))
                .isEqualByComparingTo("30.00");

        ProductEntity b5Own = ProductEntity.builder().id(8L).code("B-5").name("Billetera").build();
        ProductEntity lookalike = ProductEntity.builder().id(9L).code("B-20").name("Billetera").build();
        java.util.Map<String, BigDecimal> mixed = new java.util.HashMap<>();
        EntrecuerosPriceLists.addVolumeQuantity(mixed, 8L, b5Own, "SINTETICO", new BigDecimal("3"));
        EntrecuerosPriceLists.addVolumeQuantity(mixed, 9L, lookalike, "SINTETICO", new BigDecimal("2"));
        assertThat(EntrecuerosPriceLists.resolveChargedUnitPrice(
                b5Own, "SINTETICO", new BigDecimal("3"), mixed))
                .isEqualByComparingTo("30.00");
        assertThat(EntrecuerosPriceLists.resolveChargedUnitPrice(
                lookalike, "SINTETICO", new BigDecimal("2"), mixed))
                .isEqualByComparingTo("30.00");

        ProductEntity split = ProductEntity.builder().id(10L).code("B-5").name("Billetera").build();
        java.util.Map<String, BigDecimal> splitQty = new java.util.HashMap<>();
        EntrecuerosPriceLists.addVolumeQuantity(splitQty, 10L, split, "SINTETICO", new BigDecimal("3"));
        EntrecuerosPriceLists.addVolumeQuantity(splitQty, 10L, split, "SINTETICO", new BigDecimal("1"));
        assertThat(EntrecuerosPriceLists.resolveChargedUnitPrice(
                split, "SINTETICO", new BigDecimal("3"), splitQty))
                .isEqualByComparingTo("40.00");
        assertThat(EntrecuerosPriceLists.resolveChargedUnitPrice(
                split, "SINTETICO", new BigDecimal("1"), splitQty))
                .isEqualByComparingTo("40.00");

        ProductEntity casual = ProductEntity.builder().id(1L).name("Cincho").cinchoType("CASUAL").build();
        ProductEntity b5Line = ProductEntity.builder().id(2L).code("B-5").name("Billetera").build();
        java.util.Map<String, BigDecimal> b5Courtesy = new java.util.HashMap<>();
        EntrecuerosPriceLists.addVolumeQuantity(b5Courtesy, 1L, casual, "NUEVO", new BigDecimal("6"));
        EntrecuerosPriceLists.addVolumeQuantity(b5Courtesy, 2L, b5Line, "SINTETICO", new BigDecimal("3"));
        assertThat(EntrecuerosPriceLists.resolveChargedUnitPrice(
                b5Line, "SINTETICO", new BigDecimal("3"), b5Courtesy))
                .isEqualByComparingTo("40.00");
        assertThat(EntrecuerosPriceLists.chargedLineTotal(
                b5Line, "SINTETICO", new BigDecimal("3"), b5Courtesy))
                .isEqualByComparingTo("120.00");

        ProductEntity b1Line = ProductEntity.builder().id(3L).code("B-1").name("Billetera").build();
        java.util.Map<String, BigDecimal> b1Courtesy = new java.util.HashMap<>();
        EntrecuerosPriceLists.addVolumeQuantity(b1Courtesy, 1L, casual, "NUEVO", new BigDecimal("6"));
        EntrecuerosPriceLists.addVolumeQuantity(b1Courtesy, 3L, b1Line, "SINTETICO", new BigDecimal("3"));
        assertThat(EntrecuerosPriceLists.chargedLineTotal(
                b1Line, "SINTETICO", new BigDecimal("3"), b1Courtesy))
                .isEqualByComparingTo("120.00");
    }

    private static BigDecimal line(ProductEntity product, int qty) {
        return EntrecuerosPriceLists.chargedLineTotal(product, "SINTETICO", new BigDecimal(qty), volumeOf(product, qty));
    }

    private static java.util.Map<String, BigDecimal> volumeOf(ProductEntity product, int qty) {
        java.util.Map<String, BigDecimal> qtyByKey = new java.util.HashMap<>();
        EntrecuerosPriceLists.addVolumeQuantity(
                qtyByKey, product.getId(), product, "SINTETICO", new BigDecimal(qty));
        return qtyByKey;
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
