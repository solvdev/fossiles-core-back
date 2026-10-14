package com.fossiles.fossilescorebackend.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductionOrderItemUpdateServiceTest {
    private final ProductionOrderItemRepository items = mock(ProductionOrderItemRepository.class);
    private final TaskItemRepository taskItems = mock(TaskItemRepository.class);
    private final ProductionOrderItemUpdateService service = new ProductionOrderItemUpdateService(items, taskItems,
            mock(TaskRepository.class), mock(ProductionOrderWarehouseUnitRepository.class),
            mock(ProductionOrderPartialReleaseLineRepository.class), mock(ProductionCinchoDayStatusRepository.class), new ObjectMapper());

    @Test
    void preservesIdentityReceiptsAndOnlineReferencesWhenEditingDescription() throws Exception {
        var original = line(11L, 10);
        original.setWarehouseReceivedQty(3);
        original.setOnlineSaleId(27L);
        when(items.findByProductionOrderId(1L)).thenReturn(List.of(original));
        var requested = line(11L, 10);
        requested.setObservations("Detalle actualizado");
        var plan = service.prepare(1L, List.of(requested));
        assertThat(plan.updates().getFirst().getId()).isEqualTo(11L);
        assertThat(plan.updates().getFirst().getWarehouseReceivedQty()).isEqualTo(3);
        assertThat(plan.updates().getFirst().getOnlineSaleId()).isEqualTo(27L);
        assertThat(plan.removed()).isEmpty();
        assertThat(original.getObservations()).isNull();
        verify(items, never()).saveAll(any());
    }

    @Test
    void legacyRequestWithoutIdRetainsUniqueExistingVariant() throws Exception {
        when(items.findByProductionOrderId(1L)).thenReturn(List.of(line(11L, 10)));
        assertThat(service.prepare(1L, List.of(line(null, 12))).updates().getFirst().getId()).isEqualTo(11L);
    }

    @Test
    void rejectsRemovingAnItemAlreadyAssignedToProduction() {
        when(items.findByProductionOrderId(1L)).thenReturn(List.of(line(11L, 10)));
        when(taskItems.existsByProductionOrderItemId(11L)).thenReturn(true);
        assertThatThrownBy(() -> service.prepare(1L, List.of())).isInstanceOf(BusinessException.class);
        verify(items, never()).deleteAll(any());
    }

    @Test
    void rejectsQuantityChangesAfterWarehouseReception() {
        var original = line(11L, 10);
        original.setWarehouseReceivedQty(3);
        when(items.findByProductionOrderId(1L)).thenReturn(List.of(original));
        assertThatThrownBy(() -> service.prepare(1L, List.of(line(11L, 12)))).isInstanceOf(BusinessException.class);
        assertThat(original.getQuantity()).isEqualTo(10);
    }

    @Test
    void rejectsForeignAndRepeatedIdentifiersBeforeAnyWrite() {
        when(items.findByProductionOrderId(1L)).thenReturn(List.of(line(11L, 10)));
        assertThatThrownBy(() -> service.prepare(1L, List.of(line(99L, 10)))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.prepare(1L, List.of(line(11L, 10), line(11L, 10)))).isInstanceOf(BusinessException.class);
        verify(items, never()).saveAll(any());
    }

    @Test
    void equalSizeMapsWithDifferentOrderingDoNotInvalidateReceivedItem() throws Exception {
        var original = line(11L, null);
        original.setWarehouseReceivedQty(3);
        original.setSizesData("{\"16\":3,\"18\":2}");
        var requested = line(11L, null);
        requested.setSizesData("{\"18\":2,\"16\":3}");
        when(items.findByProductionOrderId(1L)).thenReturn(List.of(original));
        assertThat(service.prepare(1L, List.of(requested)).updates().getFirst().getWarehouseReceivedQty()).isEqualTo(3);
    }

    private ProductionOrderItemEntity line(Long id, Integer quantity) {
        return ProductionOrderItemEntity.builder().id(id).productionOrderId(1L).productId(5L).colorId(2L)
                .quantity(quantity).warehouseReceivedQty(0).unitPrice(BigDecimal.TEN).build();
    }

    @Test void preservesOmittedPricesAndLegacyQuantityOfReceivedSizes() throws Exception {
        var original = line(11L, null);
        original.setSizesData("{\"16\":3}");
        original.setUnitPricesJson("{\"16\":25}");
        original.setWarehouseReceivedQty(3);
        var requested = line(11L, 0);
        requested.setSizesData("{\"16\":3}");
        requested.setUnitPrice(null);
        when(items.findByProductionOrderId(1L)).thenReturn(List.of(original));
        var result = service.prepare(1L, List.of(requested)).updates().getFirst();
        assertThat(result.getQuantity()).isNull();
        assertThat(result.getUnitPrice()).isEqualByComparingTo(BigDecimal.TEN);
        assertThat(result.getUnitPricesJson()).isEqualTo("{\"16\":25}");
    }
}
