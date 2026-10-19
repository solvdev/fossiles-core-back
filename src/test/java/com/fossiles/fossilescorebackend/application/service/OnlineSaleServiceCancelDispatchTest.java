package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleEntity;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** Al anular un despacho, la venta pierde la guía y también su fecha de despacho. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OnlineSaleServiceCancelDispatchTest {

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

    @Test
    void cancelDispatch_limpiaGuiaYFechaDeDespacho() throws Exception {
        OnlineSaleEntity sale = OnlineSaleEntity.builder()
                .id(77L)
                .status("ENVIADO")
                .inProductionOrder(false)
                .guideNumber("58123456789")
                .dispatchedAt(LocalDateTime.of(2026, 10, 14, 10, 30))
                .build();
        when(saleRepository.findById(77L)).thenReturn(Optional.of(sale));
        when(returnsWarehouseLocator.find()).thenReturn(Optional.of(LocationEntity.builder().id(5L).build()));

        service.cancelDispatch(77L, "Error de captura");

        assertThat(sale.getStatus()).isEqualTo("PRODUCIDO");
        assertThat(sale.getGuideNumber()).isNull();
        assertThat(sale.getDispatchedAt()).isNull();
    }
}
