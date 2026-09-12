package com.fossiles.fossilescorebackend.application.util;

import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.util.KioskPosMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KioscoStockDimensionTest {

    private static LocationEntity entreCueros() {
        return LocationEntity.builder().id(KioskPosMode.ENTRECUEROS_LOCATION_ID).name("Entre Cueros").build();
    }

    private static LocationEntity otherKiosk() {
        return LocationEntity.builder().id(10L).name("Kiosko A").build();
    }

    private static ProductEntity cincho() {
        return ProductEntity.builder().id(1L).code("N-113-JR").name("Cincho casual").cinchoType("CASUAL").build();
    }

    private static ProductEntity wallet() {
        return ProductEntity.builder().id(2L).code("B-10").name("Billetera Megan").build();
    }

    private static ProductEntity branded() {
        return ProductEntity.builder().id(3L).code("P-1").name("Portafolio").build();
    }

    private static ProductEntity packaging() {
        return ProductEntity.builder().id(4L).code("SUM-001").name("Empaque").build();
    }

    @Test
    void otherKiosk_usesHerraje() throws Exception {
        assertThat(KioscoStockDimension.resolve(otherKiosk(), cincho(), "VIEJO", false))
                .isEqualTo("VIEJO");
        assertThat(KioscoStockDimension.resolve(otherKiosk(), cincho(), null, false))
                .isEqualTo("NUEVO");
    }

    @Test
    void entreCuerosCincho_requiresAudience() throws Exception {
        assertThat(KioscoStockDimension.resolve(entreCueros(), cincho(), "nino", false))
                .isEqualTo("NINO");
        assertThatThrownBy(() -> KioscoStockDimension.resolve(entreCueros(), cincho(), "NUEVO", false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Niño o Dama");
    }

    @Test
    void entreCuerosCincho_allowsResidualOnOutflow() throws Exception {
        assertThat(KioscoStockDimension.resolve(entreCueros(), cincho(), "NUEVO", true))
                .isEqualTo("NUEVO");
    }

    @Test
    void entreCuerosWallet_requiresBrand() throws Exception {
        assertThat(KioscoStockDimension.resolve(entreCueros(), wallet(), "SINTETICO:levis", false))
                .isEqualTo("SINTETICO:LEVIS");
        assertThatThrownBy(() -> KioscoStockDimension.resolve(entreCueros(), wallet(), "NUEVO", false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("marca");
    }

    @Test
    void remap_fromEntreCueros_landsNuevo() throws Exception {
        assertThat(KioscoStockDimension.remapTrasladoDestination(
                entreCueros(), otherKiosk(), cincho(), "NINO", null))
                .isEqualTo("NUEVO");
    }

    @Test
    void remap_toEntreCueros_requiresDestination() throws Exception {
        assertThat(KioscoStockDimension.remapTrasladoDestination(
                otherKiosk(), entreCueros(), branded(), "NUEVO", "LACOSTE"))
                .isEqualTo("LACOSTE");
        assertThatThrownBy(() -> KioscoStockDimension.remapTrasladoDestination(
                otherKiosk(), entreCueros(), branded(), "NUEVO", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("marca");
    }

    @Test
    void packaging_isAlwaysNuevo() throws Exception {
        assertThat(KioscoStockDimension.resolve(entreCueros(), packaging(), "LACOSTE", false))
                .isEqualTo("NUEVO");
    }
}
