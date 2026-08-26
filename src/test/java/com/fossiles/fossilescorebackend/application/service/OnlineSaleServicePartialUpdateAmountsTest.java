package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.OnlineSaleRequest;
import com.fossiles.fossilescorebackend.application.dto.response.OnlineSaleResponse;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OnlineSaleServicePartialUpdateAmountsTest {

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
    void partialUpdate_withMultipleItems_recalculatesNetFromAllItemsNotLegacyFirstProduct() throws Exception {
        Long saleId = 529L;
        OnlineSaleEntity entity = OnlineSaleEntity.builder()
                .id(saleId)
                .saleNumber("2")
                .customerName("JUAN MANUEL MARROQUIN ALFARO")
                .paymentMethod("VISALINK_PAGADO")
                // Legacy = primer producto (como copyFirstItemToLegacy)
                .productId(1L)
                .unitPrice(new BigDecimal("415.00"))
                .quantity(1)
                // Encabezado corrupto (solo primer ítem)
                .netAmount(new BigDecimal("415.00"))
                .shippingCost(new BigDecimal("15.00"))
                .totalAmount(new BigDecimal("430.00"))
                .packaging(false)
                .build();

        List<OnlineSaleItemEntity> items = List.of(
                OnlineSaleItemEntity.builder()
                        .id(1L)
                        .onlineSaleId(saleId)
                        .productCode("AC-6")
                        .quantity(1)
                        .unitPrice(new BigDecimal("415.00"))
                        .subtotal(new BigDecimal("415.00"))
                        .build(),
                OnlineSaleItemEntity.builder()
                        .id(2L)
                        .onlineSaleId(saleId)
                        .productCode("FOSS-15")
                        .quantity(1)
                        .unitPrice(new BigDecimal("305.00"))
                        .subtotal(new BigDecimal("305.00"))
                        .build(),
                OnlineSaleItemEntity.builder()
                        .id(3L)
                        .onlineSaleId(saleId)
                        .productCode("FOSS-6")
                        .quantity(1)
                        .unitPrice(new BigDecimal("305.00"))
                        .subtotal(new BigDecimal("305.00"))
                        .build()
        );

        when(saleRepository.findById(saleId)).thenReturn(Optional.of(entity));
        when(itemRepository.findByOnlineSaleIdOrderByIdAsc(saleId)).thenReturn(items);
        when(securityUtil.getCurrentUserId()).thenReturn(99L);
        when(saleRepository.save(any(OnlineSaleEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(taxInvoiceRepository.findBySourceTypeAndSourceId("ONLINE_SALE", saleId)).thenReturn(Optional.empty());

        OnlineSaleRequest req = OnlineSaleRequest.builder()
                .packaging(true)
                .build();

        OnlineSaleResponse response = service.update(saleId, req);

        assertThat(entity.getNetAmount()).isEqualByComparingTo("1025.00");
        assertThat(entity.getTotalAmount()).isEqualByComparingTo("1040.00");
        assertThat(entity.getShippingCost()).isEqualByComparingTo("15.00");
        assertThat(response.getNetAmount()).isEqualByComparingTo("1025.00");
        assertThat(response.getTotalAmount()).isEqualByComparingTo("1040.00");
    }

    @Test
    void partialUpdate_legacySingleProductWithoutItems_usesUnitPriceTimesQuantity() throws Exception {
        Long saleId = 10L;
        OnlineSaleEntity entity = OnlineSaleEntity.builder()
                .id(saleId)
                .saleNumber("1")
                .customerName("Legacy")
                .paymentMethod("VISALINK_PAGADO")
                .productId(1L)
                .unitPrice(new BigDecimal("200.00"))
                .quantity(2)
                .netAmount(new BigDecimal("400.00"))
                .shippingCost(new BigDecimal("15.00"))
                .totalAmount(new BigDecimal("415.00"))
                .build();

        when(saleRepository.findById(saleId)).thenReturn(Optional.of(entity));
        when(itemRepository.findByOnlineSaleIdOrderByIdAsc(saleId)).thenReturn(List.of());
        when(securityUtil.getCurrentUserId()).thenReturn(99L);
        when(saleRepository.save(any(OnlineSaleEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(taxInvoiceRepository.findBySourceTypeAndSourceId("ONLINE_SALE", saleId)).thenReturn(Optional.empty());

        OnlineSaleRequest req = OnlineSaleRequest.builder()
                .observations("nota")
                .build();

        service.update(saleId, req);

        assertThat(entity.getNetAmount()).isEqualByComparingTo("400.00");
        assertThat(entity.getTotalAmount()).isEqualByComparingTo("415.00");
    }
}
