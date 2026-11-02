package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.ProductionOrderItemRequest;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ProductionOrderItemConflictException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderWarehouseUnitRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductionOrderItemSyncServiceTest {

    private static final Long ORDER_ID = 50L;

    @Mock private ProductionOrderItemRepository itemRepository;
    @Mock private TaskRepository taskRepository;
    @Mock private TaskItemRepository taskItemRepository;
    @Mock private ProductionOrderWarehouseUnitRepository warehouseUnitRepository;
    @Mock private ProductRepository productRepository;
    @Mock private ColorRepository colorRepository;

    private ProductionOrderItemSyncService service;

    @BeforeEach
    void setUp() {
        service = new ProductionOrderItemSyncService(
                itemRepository,
                taskRepository,
                taskItemRepository,
                warehouseUnitRepository,
                productRepository,
                colorRepository);
    }

    @Test
    void updatesExistingLineInPlaceAndKeepsIdAndWarehouseReceivedQty() throws Exception {
        ProductionOrderItemEntity line = line(10L, 1L, 2L, "VIEJA", 7);
        line.setOnlineSaleId(55L);
        line.setOnlineSaleItemId(66L);
        line.setSizesData("{\"18\":1}");
        line.setUnitPricesJson("{\"30\":80}");
        line.setObservations("vieja");
        when(itemRepository.findByProductionOrderId(ORDER_ID)).thenReturn(List.of(line));

        ProductionOrderItemRequest request = ProductionOrderItemRequest.builder()
                .id(10L)
                .productId(9L)
                .colorId(4L)
                .brandName("CUALQUIERA")
                .quantity(8)
                .sizes(Map.of("16", 3))
                .observations("nota")
                .unitPrice(new BigDecimal("12.50"))
                .build();

        service.sync(ORDER_ID, "NORMAL", List.of(request));

        ProductionOrderItemEntity saved = single(savedItems());
        assertThat(saved.getId()).isEqualTo(10L);
        assertThat(saved.getWarehouseReceivedQty()).isEqualTo(7);
        assertThat(saved.getOnlineSaleId()).isEqualTo(55L);
        assertThat(saved.getOnlineSaleItemId()).isEqualTo(66L);
        assertThat(saved.getProductId()).isEqualTo(9L);
        assertThat(saved.getColorId()).isEqualTo(4L);
        assertThat(saved.getBrandName()).isNull();
        assertThat(saved.getQuantity()).isEqualTo(8);
        assertThat(saved.getSizesData()).isEqualTo("{\"16\":3}");
        assertThat(saved.getObservations()).isEqualTo("nota");
        assertThat(saved.getUnitPrice()).isEqualByComparingTo("12.50");
        assertThat(saved.getUnitPricesJson()).isNull();
        verify(itemRepository, never()).deleteAll(any());
    }

    @Test
    void insertsLineWithoutId() throws Exception {
        when(itemRepository.findByProductionOrderId(ORDER_ID)).thenReturn(List.of());

        service.sync(ORDER_ID, "NORMAL", List.of(request(null, 9L, 4L, null, 2)));

        ProductionOrderItemEntity saved = single(savedItems());
        assertThat(saved.getId()).isNull();
        assertThat(saved.getProductionOrderId()).isEqualTo(ORDER_ID);
        assertThat(saved.getProductId()).isEqualTo(9L);
        assertThat(saved.getColorId()).isEqualTo(4L);
        assertThat(saved.getQuantity()).isEqualTo(2);
        assertThat(saved.getWarehouseReceivedQty()).isEqualTo(0);
        verify(itemRepository, never()).deleteAll(any());
    }

    @Test
    void deletesUnreferencedLineMissingFromRequest() throws Exception {
        ProductionOrderItemEntity kept = line(10L, 1L, 2L, null, 3);
        ProductionOrderItemEntity removed = line(11L, 2L, 3L, null, 0);
        when(itemRepository.findByProductionOrderId(ORDER_ID)).thenReturn(List.of(kept, removed));
        givenNoReferences();

        service.sync(ORDER_ID, "NORMAL", List.of(request(10L, 1L, 2L, null, 3)));

        assertThat(savedItems()).extracting(ProductionOrderItemEntity::getId).containsExactly(10L);
        assertThat(deletedItems()).extracting(ProductionOrderItemEntity::getId).containsExactly(11L);
        assertThat(kept.getWarehouseReceivedQty()).isEqualTo(3);
    }

    @Test
    void rejectsRemovalWhenTaskReferencesLineAndWritesNothing() {
        assertRemovalRejected(List.of(11L), List.of(), List.of(), "tareas");
    }

    @Test
    void rejectsRemovalWhenTaskItemReferencesLineAndWritesNothing() {
        assertRemovalRejected(List.of(), List.of(11L), List.of(), "tareas");
    }

    @Test
    void rejectsRemovalWhenWarehouseUnitReferencesLineAndWritesNothing() {
        assertRemovalRejected(List.of(), List.of(), List.of(11L), "unidades de bodega");
    }

    @Test
    void rejectsIdFromAnotherOrder() {
        ProductionOrderItemEntity own = line(10L, 1L, 2L, null, 4);
        when(itemRepository.findByProductionOrderId(ORDER_ID)).thenReturn(List.of(own));
        ProductionOrderItemEntity foreign = line(99L, 1L, 2L, null, 1);
        foreign.setProductionOrderId(8L);
        when(itemRepository.findById(99L)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.sync(ORDER_ID, "NORMAL", List.of(request(99L, 1L, 2L, null, 1))))
                .isInstanceOf(BusinessException.class)
                .hasMessage("La línea 99 no pertenece a esta orden de producción.");
        assertThat(own.getQuantity()).isEqualTo(1);
        verifyNothingWritten();
    }

    @Test
    void rejectsUnknownId() {
        when(itemRepository.findByProductionOrderId(ORDER_ID)).thenReturn(List.of());
        when(itemRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.sync(ORDER_ID, "NORMAL", List.of(request(99L, 1L, 2L, null, 1))))
                .isInstanceOf(BusinessException.class)
                .hasMessage("La línea 99 no existe.");
        verifyNothingWritten();
    }

    @Test
    void reusesUniqueProductColorBrandMatch() throws Exception {
        ProductionOrderItemEntity levis = line(10L, 1L, 2L, "LEVIS", 4);
        ProductionOrderItemEntity nautica = line(11L, 1L, 2L, "NAUTICA", 2);
        when(itemRepository.findByProductionOrderId(ORDER_ID)).thenReturn(List.of(levis, nautica));
        givenNoReferences();

        service.sync(ORDER_ID, "MARCAS", List.of(request(null, 1L, 2L, "levis", 8)));

        ProductionOrderItemEntity saved = single(savedItems());
        assertThat(saved.getId()).isEqualTo(10L);
        assertThat(saved.getBrandName()).isEqualTo("LEVIS");
        assertThat(saved.getQuantity()).isEqualTo(8);
        assertThat(saved.getWarehouseReceivedQty()).isEqualTo(4);
        assertThat(deletedItems()).extracting(ProductionOrderItemEntity::getId).containsExactly(11L);
    }

    @Test
    void insertsSecondIdenticalLineBesideTheReusedOne() throws Exception {
        ProductionOrderItemEntity existing = line(10L, 1L, 2L, "LEVIS", 4);
        when(itemRepository.findByProductionOrderId(ORDER_ID)).thenReturn(List.of(existing));

        service.sync(ORDER_ID, "MARCAS", List.of(
                request(null, 1L, 2L, "LEVIS", 8),
                request(null, 1L, 2L, "LEVIS", 3)));

        List<ProductionOrderItemEntity> saved = savedItems();
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getId()).isEqualTo(10L);
        assertThat(saved.get(0).getQuantity()).isEqualTo(8);
        assertThat(saved.get(0).getWarehouseReceivedQty()).isEqualTo(4);
        assertThat(saved.get(1).getId()).isNull();
        assertThat(saved.get(1).getQuantity()).isEqualTo(3);
        assertThat(saved.get(1).getWarehouseReceivedQty()).isEqualTo(0);
        assertThat(saved.get(1).getBrandName()).isEqualTo("LEVIS");
        verify(itemRepository, never()).deleteAll(any());
    }

    @Test
    void rejectsProductChangeWhenTaskReferencesLineAndWritesNothing() {
        ProductionOrderItemEntity line = referencedLine();
        when(itemRepository.findByProductionOrderId(ORDER_ID)).thenReturn(List.of(line));
        givenReferences(List.of(line.getId()), List.of(), List.of());
        givenLineLabels();

        assertThatThrownBy(() -> service.sync(ORDER_ID, "NORMAL", List.of(request(10L, 9L, 3L, null, 4))))
                .isInstanceOf(ProductionOrderItemConflictException.class)
                .hasMessage("No se puede cambiar la línea B-7 / NEGRO porque tiene tareas.");
        assertThat(line.getProductId()).isEqualTo(1L);
        assertThat(line.getColorId()).isEqualTo(3L);
        assertThat(line.getQuantity()).isEqualTo(4);
        verifyNothingWritten();
    }

    @Test
    void rejectsColorChangeWhenTaskItemReferencesLineAndWritesNothing() {
        ProductionOrderItemEntity line = referencedLine();
        when(itemRepository.findByProductionOrderId(ORDER_ID)).thenReturn(List.of(line));
        givenReferences(List.of(), List.of(line.getId()), List.of());
        givenLineLabels();

        assertThatThrownBy(() -> service.sync(ORDER_ID, "NORMAL", List.of(request(10L, 1L, 8L, null, 4))))
                .isInstanceOf(ProductionOrderItemConflictException.class)
                .hasMessage("No se puede cambiar la línea B-7 / NEGRO porque tiene tareas.");
        assertThat(line.getColorId()).isEqualTo(3L);
        assertThat(line.getQuantity()).isEqualTo(4);
        verifyNothingWritten();
    }

    @Test
    void rejectsMarcasBrandChangeWhenWarehouseUnitReferencesLineAndWritesNothing() {
        ProductionOrderItemEntity line = referencedLine();
        line.setBrandName("LEVIS");
        when(itemRepository.findByProductionOrderId(ORDER_ID)).thenReturn(List.of(line));
        givenReferences(List.of(), List.of(), List.of(line.getId()));
        givenLineLabels();

        assertThatThrownBy(() -> service.sync(ORDER_ID, "MARCAS", List.of(request(10L, 1L, 3L, "NAUTICA", 4))))
                .isInstanceOf(ProductionOrderItemConflictException.class)
                .hasMessage("No se puede cambiar la línea B-7 / NEGRO porque tiene unidades de bodega.");
        assertThat(line.getBrandName()).isEqualTo("LEVIS");
        assertThat(line.getQuantity()).isEqualTo(4);
        verifyNothingWritten();
    }

    @Test
    void allowsProductAndColorChangeWhenLineIsUnreferenced() throws Exception {
        ProductionOrderItemEntity line = referencedLine();
        when(itemRepository.findByProductionOrderId(ORDER_ID)).thenReturn(List.of(line));
        givenNoReferences();
        when(taskItemRepository.assignedQuantityMap(any())).thenReturn(Map.of());

        service.sync(ORDER_ID, "NORMAL", List.of(request(10L, 9L, 8L, "OTRA", 4)));

        ProductionOrderItemEntity saved = single(savedItems());
        assertThat(saved.getId()).isEqualTo(10L);
        assertThat(saved.getProductId()).isEqualTo(9L);
        assertThat(saved.getColorId()).isEqualTo(8L);
        assertThat(saved.getBrandName()).isNull();
        assertThat(saved.getQuantity()).isEqualTo(4);
        assertThat(saved.getWarehouseReceivedQty()).isEqualTo(2);
        verify(itemRepository, never()).deleteAll(any());
    }

    @Test
    void allowsMarcasBrandCaseChangeWhenLineIsReferenced() throws Exception {
        ProductionOrderItemEntity line = referencedLine();
        line.setBrandName("LEVIS");
        when(itemRepository.findByProductionOrderId(ORDER_ID)).thenReturn(List.of(line));
        lenient().when(taskRepository.findNonCancelledItemIds(any())).thenReturn(List.of(line.getId()));
        lenient().when(taskItemRepository.findNonCancelledItemIds(any())).thenReturn(List.of());
        lenient().when(warehouseUnitRepository.findReferencedItemIds(any())).thenReturn(List.of());
        lenient().when(productRepository.findAllById(any())).thenReturn(List.of(
                ProductEntity.builder().id(1L).code("B-7").build()));
        lenient().when(colorRepository.findAllById(any())).thenReturn(List.of(
                ColorEntity.builder().id(3L).name("NEGRO").build()));
        when(taskItemRepository.assignedQuantityMap(any())).thenReturn(Map.of());

        service.sync(ORDER_ID, "MARCAS", List.of(request(10L, 1L, 3L, "levis", 4)));

        ProductionOrderItemEntity saved = single(savedItems());
        assertThat(saved.getId()).isEqualTo(10L);
        assertThat(saved.getBrandName()).isEqualTo("LEVIS");
        assertThat(saved.getWarehouseReceivedQty()).isEqualTo(2);
    }

    @Test
    void rejectsQuantityBelowAssignedOnFallbackMatchAndWritesNothing() {
        ProductionOrderItemEntity line = referencedLine();
        line.setQuantity(10);
        when(itemRepository.findByProductionOrderId(ORDER_ID)).thenReturn(List.of(line));
        when(taskItemRepository.assignedQuantityMap(any())).thenReturn(Map.of(10L, 8));
        givenLineLabels();

        assertThatThrownBy(() -> service.sync(ORDER_ID, "NORMAL", List.of(request(null, 1L, 3L, null, 7))))
                .isInstanceOf(ProductionOrderItemConflictException.class)
                .hasMessage("No se puede bajar la cantidad de la línea B-7 / NEGRO por debajo de 8.");
        assertThat(line.getQuantity()).isEqualTo(10);
        assertThat(line.getWarehouseReceivedQty()).isEqualTo(2);
        verifyNothingWritten();
    }

    @Test
    void rejectsQuantityBelowWarehouseReceivedAndWritesNothing() {
        ProductionOrderItemEntity line = referencedLine();
        line.setQuantity(10);
        line.setWarehouseReceivedQty(7);
        when(itemRepository.findByProductionOrderId(ORDER_ID)).thenReturn(List.of(line));
        when(taskItemRepository.assignedQuantityMap(any())).thenReturn(Map.of(10L, 2));
        givenLineLabels();

        assertThatThrownBy(() -> service.sync(ORDER_ID, "NORMAL", List.of(request(10L, 1L, 3L, null, 6))))
                .isInstanceOf(ProductionOrderItemConflictException.class)
                .hasMessage("No se puede bajar la cantidad de la línea B-7 / NEGRO por debajo de 7.");
        assertThat(line.getQuantity()).isEqualTo(10);
        assertThat(line.getWarehouseReceivedQty()).isEqualTo(7);
        verifyNothingWritten();
    }

    @Test
    void allowsQuantityEqualToTheHigherFloor() throws Exception {
        ProductionOrderItemEntity line = referencedLine();
        line.setQuantity(10);
        when(itemRepository.findByProductionOrderId(ORDER_ID)).thenReturn(List.of(line));
        when(taskItemRepository.assignedQuantityMap(any())).thenReturn(Map.of(10L, 5));

        service.sync(ORDER_ID, "NORMAL", List.of(request(10L, 1L, 3L, null, 5)));

        ProductionOrderItemEntity saved = single(savedItems());
        assertThat(saved.getId()).isEqualTo(10L);
        assertThat(saved.getQuantity()).isEqualTo(5);
        assertThat(saved.getWarehouseReceivedQty()).isEqualTo(2);
        verify(itemRepository, never()).deleteAll(any());
    }

    @Test
    void treatsAmbiguousProductColorBrandMatchAsNew() throws Exception {
        ProductionOrderItemEntity first = line(10L, 1L, 2L, "LEVIS", 4);
        ProductionOrderItemEntity second = line(11L, 1L, 2L, "LEVIS", 5);
        when(itemRepository.findByProductionOrderId(ORDER_ID)).thenReturn(List.of(first, second));
        givenNoReferences();

        service.sync(ORDER_ID, "MARCAS", List.of(request(null, 1L, 2L, "LEVIS", 8)));

        ProductionOrderItemEntity saved = single(savedItems());
        assertThat(saved.getId()).isNull();
        assertThat(saved.getWarehouseReceivedQty()).isEqualTo(0);
        assertThat(saved.getQuantity()).isEqualTo(8);
        assertThat(saved.getBrandName()).isEqualTo("LEVIS");
        assertThat(deletedItems()).extracting(ProductionOrderItemEntity::getId).containsExactly(10L, 11L);
        assertThat(first.getWarehouseReceivedQty()).isEqualTo(4);
        assertThat(second.getWarehouseReceivedQty()).isEqualTo(5);
    }

    private void assertRemovalRejected(List<Long> tasks, List<Long> taskItems, List<Long> units, String reason) {
        ProductionOrderItemEntity kept = line(10L, 9L, 4L, null, 6);
        ProductionOrderItemEntity removed = line(11L, 1L, 3L, null, 2);
        when(itemRepository.findByProductionOrderId(ORDER_ID)).thenReturn(List.of(kept, removed));
        when(taskRepository.findNonCancelledItemIds(any())).thenReturn(tasks);
        when(taskItemRepository.findNonCancelledItemIds(any())).thenReturn(taskItems);
        when(warehouseUnitRepository.findReferencedItemIds(any())).thenReturn(units);
        when(productRepository.findAllById(any())).thenReturn(List.of(
                ProductEntity.builder().id(1L).code("B-7").name("Billetera").build()));
        when(colorRepository.findAllById(any())).thenReturn(List.of(
                ColorEntity.builder().id(3L).name("NEGRO").build()));

        assertThatThrownBy(() -> service.sync(ORDER_ID, "NORMAL", List.of(request(10L, 9L, 4L, null, 9))))
                .isInstanceOf(ProductionOrderItemConflictException.class)
                .hasMessage("No se puede quitar la línea B-7 / NEGRO porque tiene " + reason + ".");
        assertThat(kept.getQuantity()).isEqualTo(1);
        assertThat(kept.getWarehouseReceivedQty()).isEqualTo(6);
        assertThat(removed.getWarehouseReceivedQty()).isEqualTo(2);
        verifyNothingWritten();
    }

    private void givenNoReferences() {
        when(taskRepository.findNonCancelledItemIds(any())).thenReturn(List.of());
        when(taskItemRepository.findNonCancelledItemIds(any())).thenReturn(List.of());
        when(warehouseUnitRepository.findReferencedItemIds(any())).thenReturn(List.of());
    }

    private void givenReferences(List<Long> tasks, List<Long> taskItems, List<Long> units) {
        when(taskRepository.findNonCancelledItemIds(any())).thenReturn(tasks);
        when(taskItemRepository.findNonCancelledItemIds(any())).thenReturn(taskItems);
        when(warehouseUnitRepository.findReferencedItemIds(any())).thenReturn(units);
        when(taskItemRepository.assignedQuantityMap(any())).thenReturn(Map.of());
    }

    /** Línea B-7 / NEGRO con 4 piezas y 2 ya recibidas. */
    private static ProductionOrderItemEntity referencedLine() {
        ProductionOrderItemEntity line = line(10L, 1L, 3L, null, 2);
        line.setQuantity(4);
        return line;
    }

    private void givenLineLabels() {
        when(productRepository.findAllById(any())).thenReturn(List.of(
                ProductEntity.builder().id(1L).code("B-7").name("Billetera").build()));
        when(colorRepository.findAllById(any())).thenReturn(List.of(
                ColorEntity.builder().id(3L).name("NEGRO").build()));
    }

    private void verifyNothingWritten() {
        verify(itemRepository, never()).save(any());
        verify(itemRepository, never()).saveAll(any());
        verify(itemRepository, never()).delete(any());
        verify(itemRepository, never()).deleteAll(any());
        verify(itemRepository, never()).deleteById(any());
        verify(itemRepository, never()).deleteAllById(any());
        verify(itemRepository, never()).deleteByProductionOrderId(any());
    }

    private List<ProductionOrderItemEntity> savedItems() {
        return captured(true);
    }

    private List<ProductionOrderItemEntity> deletedItems() {
        return captured(false);
    }

    @SuppressWarnings("unchecked")
    private List<ProductionOrderItemEntity> captured(boolean saved) {
        ArgumentCaptor<Iterable<ProductionOrderItemEntity>> captor = ArgumentCaptor.forClass(Iterable.class);
        if (saved) {
            verify(itemRepository).saveAll(captor.capture());
        } else {
            verify(itemRepository).deleteAll(captor.capture());
        }
        List<ProductionOrderItemEntity> out = new ArrayList<>();
        captor.getValue().forEach(out::add);
        return out;
    }

    private static ProductionOrderItemEntity single(List<ProductionOrderItemEntity> items) {
        assertThat(items).hasSize(1);
        return items.get(0);
    }

    private static ProductionOrderItemEntity line(
            Long id, Long productId, Long colorId, String brand, int received) {
        return ProductionOrderItemEntity.builder()
                .id(id)
                .productionOrderId(ORDER_ID)
                .productId(productId)
                .colorId(colorId)
                .brandName(brand)
                .quantity(1)
                .warehouseReceivedQty(received)
                .build();
    }

    private static ProductionOrderItemRequest request(
            Long id, Long productId, Long colorId, String brand, Integer quantity) {
        return ProductionOrderItemRequest.builder()
                .id(id)
                .productId(productId)
                .colorId(colorId)
                .brandName(brand)
                .quantity(quantity)
                .build();
    }
}
