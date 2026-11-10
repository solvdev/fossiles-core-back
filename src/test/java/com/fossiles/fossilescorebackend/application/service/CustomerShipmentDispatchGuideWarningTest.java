package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineSaleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El despacho registra cuándo salió la venta (dispatched_at) y avisa, sin bloquear, cuando un envío de
 * Forza o Guatex sale sin guía.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CustomerShipmentDispatchGuideWarningTest {

    @Mock private OnlineSaleRepository onlineSaleRepository;
    @Mock private OnlineSaleShipmentNumberService onlineSaleShipmentNumberService;
    @Mock private ProductInventoryService productInventoryService;

    @InjectMocks
    private CustomerShipmentDispatchService service;

    private OnlineSaleEntity producedDirectSale(String carrier, String guide) {
        OnlineSaleEntity sale = OnlineSaleEntity.builder()
                .id(10L)
                .customerName("Cliente de prueba")
                .status("PRODUCIDO")
                .inProductionOrder(false)
                .shippingCarrier(carrier)
                .guideNumber(guide)
                .shipmentNumber("ENVL-09999")
                .build();
        when(onlineSaleRepository.findById(10L)).thenReturn(Optional.of(sale));
        return sale;
    }

    @Test
    void forzaSinGuia_despachaIgual_conAvisoYFechaDeDespacho() throws Exception {
        OnlineSaleEntity sale = producedDirectSale("FORZA_DELIVERY", null);

        Map<String, Object> result = service.dispatchDirectOnlineSale(10L, Map.of());

        assertThat(sale.getStatus()).isEqualTo("ENVIADO");
        assertThat(sale.getDispatchedAt()).isNotNull();
        assertThat(result.get("warning").toString()).contains("sin número de guía").contains("FORZA_DELIVERY");
        verify(onlineSaleRepository).save(sale);
    }

    @Test
    void guatexConGuiaEnLaPeticion_noAvisa() throws Exception {
        OnlineSaleEntity sale = producedDirectSale(null, null);

        Map<String, Object> result = service.dispatchDirectOnlineSale(10L,
                Map.of("shippingCarrier", "GUATEX", "guideNumber", "1234567890"));

        assertThat(sale.getGuideNumber()).isEqualTo("1234567890");
        assertThat(result).doesNotContainKey("warning");
    }

    @Test
    void guiaYaGuardadaEnLaVenta_cuentaYNoAvisa() throws Exception {
        producedDirectSale("FORZA_DELIVERY", "58123456789");

        Map<String, Object> result = service.dispatchDirectOnlineSale(10L, Map.of());

        assertThat(result).doesNotContainKey("warning");
    }

    @Test
    void motoristaSinGuia_noAvisa() throws Exception {
        OnlineSaleEntity sale = producedDirectSale("MOTORISTA", null);

        Map<String, Object> result = service.dispatchDirectOnlineSale(10L, Map.of());

        assertThat(sale.getStatus()).isEqualTo("ENVIADO");
        assertThat(sale.getDispatchedAt()).isNotNull();
        assertThat(result).doesNotContainKey("warning");
    }

    @Test
    void guiaEnBlancoEnLaPeticion_seTrataComoFaltante() {
        OnlineSaleEntity sale = OnlineSaleEntity.builder().shippingCarrier("GUATEX").build();

        String warning = service.markDispatched(sale, Map.of("guideNumber", "   "));

        assertThat(warning).isNotNull();
        assertThat(sale.getDispatchedAt()).isNotNull();
    }

    @Test
    void transportistaEnMinusculas_seReconoce() {
        OnlineSaleEntity sale = OnlineSaleEntity.builder().shippingCarrier("guatex").build();

        assertThat(service.markDispatched(sale, null)).isNotNull();
    }

    @Test
    void sinTransportista_noAvisaPeroRegistraLaFecha() {
        OnlineSaleEntity sale = OnlineSaleEntity.builder().build();

        assertThat(service.markDispatched(sale, null)).isNull();
        assertThat(sale.getDispatchedAt()).isNotNull();
        verify(onlineSaleRepository, org.mockito.Mockito.never()).save(any());
    }
}
