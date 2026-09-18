package com.fossiles.fossilescorebackend.application.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KioskPosInventoryKeyTest {

    @Test
    void parse_ninoWithSize_keepsAudienceAndSize() {
        String key = KioskPosInventoryKey.format(10L, 3L, "NINO", "28");
        assertThat(key).isEqualTo("10:3:NINO:28");

        KioskPosInventoryKey.Parsed parsed = KioskPosInventoryKey.parse(key);
        assertThat(parsed.productId()).isEqualTo(10L);
        assertThat(parsed.colorId()).isEqualTo(3L);
        assertThat(parsed.hardwareCondition()).isEqualTo("NINO");
        assertThat(parsed.size()).isEqualTo("28");
    }

    @Test
    void parse_ninoWithoutSize_isHardwareNotSize() {
        KioskPosInventoryKey.Parsed parsed = KioskPosInventoryKey.parse("10:3:NINO");
        assertThat(parsed.hardwareCondition()).isEqualTo("NINO");
        assertThat(parsed.size()).isNull();
    }

    @Test
    void parse_damaWithSize() {
        KioskPosInventoryKey.Parsed parsed = KioskPosInventoryKey.parse("8:null:DAMA:32");
        assertThat(parsed.colorId()).isNull();
        assertThat(parsed.hardwareCondition()).isEqualTo("DAMA");
        assertThat(parsed.size()).isEqualTo("32");
    }

    @Test
    void parse_legacySizeOnly_staysNuevo() {
        KioskPosInventoryKey.Parsed parsed = KioskPosInventoryKey.parse("10:3:32");
        assertThat(parsed.hardwareCondition()).isEqualTo("NUEVO");
        assertThat(parsed.size()).isEqualTo("32");
    }

    @Test
    void parse_nuevoViejoWithSize() {
        assertThat(KioskPosInventoryKey.parse("1:2:VIEJO:40").hardwareCondition()).isEqualTo("VIEJO");
        assertThat(KioskPosInventoryKey.parse("1:2:VIEJO:40").size()).isEqualTo("40");
    }

    @Test
    void parse_walletSyntheticBrand() {
        String key = KioskPosInventoryKey.format(4L, 9L, "SINTETICO:LEVIS", null);
        KioskPosInventoryKey.Parsed parsed = KioskPosInventoryKey.parse(key);
        assertThat(parsed.hardwareCondition()).isEqualTo("SINTETICO:LEVIS");
        assertThat(parsed.size()).isNull();
    }
}
