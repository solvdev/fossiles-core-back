package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.OnlineSaleExchangeRequest;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineSaleItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineSaleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineSaleReturnLineRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineSaleReturnRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ReturnInventoryRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaxInvoiceRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OnlineSaleServiceExchangeToExistingShipmentTest {

    @Mock private OnlineSaleRepository saleRepository;
    @Mock private OnlineSaleItemRepository itemRepository;
    @Mock private ProductRepository productRepository;
    @Mock private ColorRepository colorRepository;
    @Mock private ReturnInventoryRepository returnInventoryRepository;
    @Mock private OnlineSaleReturnRepository onlineSaleReturnRepository;
    @Mock private OnlineSaleReturnLineRepository onlineSaleReturnLineRepository;
    @Mock private SecurityUtil securityUtil;
    @Mock private OnlineSaleShipmentNumberService onlineSaleShipmentNumberService;
    @Mock private OnlineSaleReturnsWarehouseLocator returnsWarehouseLocator;
    @Mock private TaxInvoiceRepository taxInvoiceRepository;
    @Mock private ProductInventoryService productInventoryService;
    @Mock private ProductionOrderItemRepository productionOrderItemRepository;
    @Mock private ProductionOrderRepository productionOrderRepository;
    @Mock private ProductionOrderWarehouseUnitService productionOrderWarehouseUnitService;

    @InjectMocks
    private OnlineSaleService service;

    private OnlineSaleEntity sale(Long id, String status, String shipment) {
        return OnlineSaleEntity.builder()
                .id(id)
                .saleNumber(String.valueOf(id))
                .customerName("CLIENTE")
                .paymentMethod("TARJETA_PAGADO")
                .status(status)
                .shipmentNumber(shipment)
                .netAmount(new BigDecimal("400.00"))
                .shippingCost(new BigDecimal("15.00"))
                .totalAmount(new BigDecimal("415.00"))
                .build();
    }

    private OnlineSaleItemEntity soldItem(Long saleId) {
        return OnlineSaleItemEntity.builder()
                .id(10L).onlineSaleId(saleId).productId(7L).size("M").quantity(3)
                .unitPrice(new BigDecimal("100.00")).build();
    }

    private OnlineSaleExchangeRequest request(Long targetId) {
        return OnlineSaleExchangeRequest.builder()
                .targetOnlineSaleId(targetId)
                .observations("cambio por dano")
                .items(List.of(OnlineSaleExchangeRequest.ExchangeItemRequest.builder()
                        .productId(7L).colorId(null).size("M").quantity(2).build()))
                .build();
    }

    @Test
    void createExchange_withTarget_addsZeroPriceItemsToExistingShipment_withoutCreatingNewSale() throws Exception {
        OnlineSaleEntity original = sale(1L, "ENVIADO", "ENVL-00001");
        OnlineSaleEntity target = sale(2L, "ENVIADO", "ENVL-00002"); // envío ya despachado: se permite
        when(saleRepository.findById(1L)).thenReturn(Optional.of(original));
        when(saleRepository.findById(2L)).thenReturn(Optional.of(target));
        when(itemRepository.save(any(OnlineSaleItemEntity.class))).thenAnswer(i -> i.getArgument(0));
        when(itemRepository.findByOnlineSaleIdOrderByIdAsc(1L)).thenReturn(List.of(soldItem(1L)));
        when(onlineSaleReturnLineRepository.findByOnlineSaleId(1L)).thenReturn(List.of());
        when(returnsWarehouseLocator.find()).thenReturn(Optional.of(LocationEntity.builder().id(99L).build()));
        when(onlineSaleReturnRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.createExchange(1L, request(2L));

        ArgumentCaptor<OnlineSaleItemEntity> itemCaptor = ArgumentCaptor.forClass(OnlineSaleItemEntity.class);
        verify(itemRepository).save(itemCaptor.capture());
        assertThat(itemCaptor.getValue().getOnlineSaleId()).isEqualTo(2L);
        assertThat(itemCaptor.getValue().getUnitPrice()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(itemCaptor.getValue().getQuantity()).isEqualTo(2);

        // Los montos del envío destino no cambian
        ArgumentCaptor<OnlineSaleEntity> saleCaptor = ArgumentCaptor.forClass(OnlineSaleEntity.class);
        verify(saleRepository, atLeastOnce()).save(saleCaptor.capture());
        // Se guardan el destino y la venta original (nota); no se crea ninguna venta nueva
        assertThat(saleCaptor.getAllValues()).extracting(OnlineSaleEntity::getId).containsOnly(1L, 2L);
        OnlineSaleEntity savedTarget = saleCaptor.getAllValues().stream().filter(s -> s.getId().equals(2L)).findFirst().orElseThrow();
        assertThat(savedTarget.getId()).isEqualTo(2L);
        assertThat(savedTarget.getShipmentNumber()).isEqualTo("ENVL-00002");
        assertThat(savedTarget.getTotalAmount()).isEqualByComparingTo("415.00");
        assertThat(savedTarget.getObservations()).contains("CAMBIO_Q0(ENVIO_REL=ENVL-00001)");
        verify(onlineSaleShipmentNumberService, never()).nextNumber();
    }

    @Test
    void createExchange_withTargetVoided_isRejected() {
        when(itemRepository.findByOnlineSaleIdOrderByIdAsc(1L)).thenReturn(List.of(soldItem(1L)));
        when(onlineSaleReturnLineRepository.findByOnlineSaleId(1L)).thenReturn(List.of());
        OnlineSaleEntity original = sale(1L, "ENVIADO", "ENVL-00001");
        OnlineSaleEntity target = sale(2L, "ANULADA", "ENVL-00002");
        when(saleRepository.findById(1L)).thenReturn(Optional.of(original));
        when(saleRepository.findById(2L)).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> service.createExchange(1L, request(2L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ANULADA");
        verify(itemRepository, never()).save(any());
    }
}
