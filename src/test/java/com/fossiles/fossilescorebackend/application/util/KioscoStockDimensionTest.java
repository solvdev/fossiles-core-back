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
        return ProductEntity.builder().id(1L).code("N-113").name("Cincho casual").cinchoType("CASUAL").build();
    }

    private static ProductEntity kidsCincho() {
        return ProductEntity.builder()
                .id(5L)
                .code("N-113-JR")
                .name("Cincho junior")
                .cinchoType("CASUAL")
                .build();
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
    void entreCuerosAdultCincho_doesNotUseAudience() throws Exception {
        assertThat(KioscoStockDimension.kind(entreCueros(), cincho()))
                .isEqualTo(KioscoStockDimension.Kind.NONE);
        assertThat(KioscoStockDimension.resolve(entreCueros(), cincho(), "NUEVO", false))
                .isEqualTo("NUEVO");
        assertThat(KioscoStockDimension.resolve(entreCueros(), cincho(), "nino", false))
                .isEqualTo("NUEVO");
    }

    @Test
    void entreCuerosKidsCincho_requiresAudience() throws Exception {
        assertThat(KioscoStockDimension.resolve(entreCueros(), kidsCincho(), "nino", false))
                .isEqualTo("NINO");
        assertThat(KioscoStockDimension.resolve(entreCueros(), kidsCincho(), "dama", false))
                .isEqualTo("DAMA");
        assertThatThrownBy(() -> KioscoStockDimension.resolve(entreCueros(), kidsCincho(), "NUEVO", false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Niño o Dama");
    }

    @Test
    void entreCuerosJrCincho_infersAudienceFromSizeOnReceipt() throws Exception {
        assertThat(KioscoStockDimension.kind(entreCueros(), kidsCincho()))
                .isEqualTo(KioscoStockDimension.Kind.PARA);
        assertThat(KioscoStockDimension.resolve(entreCueros(), kidsCincho(), "NUEVO", false, "28"))
                .isEqualTo("NINO");
        assertThat(KioscoStockDimension.resolve(entreCueros(), kidsCincho(), null, false, "30"))
                .isEqualTo("DAMA");
        assertThat(KioscoStockDimension.resolve(entreCueros(), kidsCincho(), "", false, "32"))
                .isEqualTo("DAMA");
    }

    @Test
    void entreCuerosKidsCincho_allowsResidualOnOutflow() throws Exception {
        assertThat(KioscoStockDimension.resolve(entreCueros(), kidsCincho(), "NUEVO", true))
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
