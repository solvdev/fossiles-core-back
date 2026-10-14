package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.service.*;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.dto.request.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:production_integrity;MODE=PostgreSQL;NON_KEYWORDS=YEAR,MONTH;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class TaskProductionIntegrityTest {
    @Autowired TaskController controller;
    @Autowired ProductionOrderController orderController;
    @Autowired TaskLeatherDeliveryService leather;
    @Autowired TaskRepository tasks;
    @Autowired TaskItemRepository items;
    @Autowired ProductRepository products;
    @Autowired MaterialRepository materials;
    @Autowired ProductionOrderRepository orders;
    @Autowired ProductionOrderItemRepository orderItems;
    @Autowired ProductVariantLeatherRepository recipes;
    @Autowired LeatherInventoryRepository inventory;
    @Autowired LeatherMovementRepository movements;
    @Autowired LeatherInventoryService leatherInventory;
    @Autowired ProductionTaskGenerationService generation;
    @MockitoBean TaskDeskAssignerService assigner;
    @MockitoSpyBean ProductionOrderItemUpdateService itemUpdates;
    ProductionOrderEntity order;
    ProductionOrderItemEntity orderItem;
    ProductEntity product;
    MaterialEntity material;
    TaskEntity task;
    TaskItemEntity first;
    TaskItemEntity second;

    @BeforeEach void fixture() {
        when(assigner.assignIfReady(anyLong())).thenReturn(Optional.empty());
        String code = UUID.randomUUID().toString().substring(0, 8);
        product = products.save(ProductEntity.builder().code("P" + code).name("Producto").build());
        material = materials.save(MaterialEntity.builder().sku("L" + code).name("Cuero").build());
        order = orders.save(ProductionOrderEntity.builder().code("OP" + code).orderType("NORMAL").status("PENDING").observations("Original").build());
        orderItem = orderItems.save(ProductionOrderItemEntity.builder().productionOrderId(order.getId()).productId(product.getId())
                .quantity(2).warehouseReceivedQty(1).build());
        recipes.save(ProductVariantLeatherEntity.builder().productId(product.getId()).leatherMaterialId(material.getId()).qtyPerUnit(new BigDecimal("2")).build());
        inventory.save(LeatherInventoryEntity.builder().materialId(material.getId()).quantityAvailable(new BigDecimal("100")).build());
        task = createTask("T" + code, null);
        first = createItem(task, false, false);
        second = createItem(task, false, false);
    }

    @Test void itemDeliveryRecordsAndReversesStockExactlyOnce() throws Exception {
        leather.setDelivered(task, first, true);
        leather.setDelivered(task, first, true);
        assertThat(inventory.findByMaterialId(material.getId()).orElseThrow().getQuantityAvailable()).isEqualByComparingTo("98");
        assertThat(movements.findByTaskItemIdAndMovementType(first.getId(), "SALIDA")).isPresent();
        assertThat(movements.findByProductionOrderIdOrderByCreatedAtDesc(order.getId())).hasSize(1);
        leather.setDelivered(task, first, false);
        leather.setDelivered(task, first, false);
        assertThat(inventory.findByMaterialId(material.getId()).orElseThrow().getQuantityAvailable()).isEqualByComparingTo("100");
        assertThat(movements.findByTaskItemIdAndMovementType(first.getId(), "SALIDA")).isEmpty();
    }

    @Test void completedManualDeliveryIsReusedWithoutAnotherStockExit() throws Exception {
        manual(new BigDecimal("4"));
        leather.setDelivered(task, first, true);
        assertThat(movements.findByProductionOrderIdOrderByCreatedAtDesc(order.getId())).hasSize(1);
        assertThat(inventory.findByMaterialId(material.getId()).orElseThrow().getQuantityAvailable()).isEqualByComparingTo("96");
        assertThat(first.getLeatherDelivered()).isTrue();
    }

    @Test void partialManualDeliveryCannotBeDuplicatedByAnItemFlag() {
        manual(new BigDecimal("1"));
        assertThatThrownBy(() -> leather.setDelivered(task, first, true)).isInstanceOf(BusinessException.class).hasMessageContaining("parcial");
        assertThat(movements.findByProductionOrderIdOrderByCreatedAtDesc(order.getId())).hasSize(1);
        assertThat(first.getLeatherDelivered()).isFalse();
    }

    @Test void shortageAndAdvancedProductionBlockDeliveryOrReversal() throws Exception {
        var stock = inventory.findByMaterialId(material.getId()).orElseThrow();
        stock.setQuantityAvailable(BigDecimal.ONE);
        inventory.save(stock);
        assertThatThrownBy(() -> leather.setDelivered(task, first, true)).isInstanceOf(BusinessException.class).hasMessageContaining("insuficiente");
        assertThat(movements.findByProductionOrderIdOrderByCreatedAtDesc(order.getId())).isEmpty();
    }

    @Test void dieCutPreventsLeatherReversal() throws Exception {
        leather.setDelivered(task, first, true);
        first.setDieCutReady(true);
        items.save(first);
        assertThatThrownBy(() -> leather.setDelivered(task, first, false)).isInstanceOf(BusinessException.class).hasMessageContaining("avanzó");
        assertThat(inventory.findByMaterialId(material.getId()).orElseThrow().getQuantityAvailable()).isEqualByComparingTo("98");
    }

    @Test void taskWithDeliveryCannotBeDeletedEvenAfterReversal() throws Exception {
        leather.setDelivered(task, first, true);
        leather.setDelivered(task, first, false);
        assertThatThrownBy(() -> controller.delete(task.getId())).isInstanceOf(BusinessException.class).hasMessageContaining("historial");
        assertThat(items.findById(first.getId())).isPresent();
    }

    @Test void wholeTaskDieCutUpdatesAllItemsAndCanBeUnmarked() throws Exception {
        delivered(task, List.of(first, second));
        controller.toggleDieCut(task.getId(), Map.of("dieCutReady", true));
        assertThat(items.findByTaskId(task.getId())).allMatch(i -> Boolean.TRUE.equals(i.getDieCutReady()) && i.getDieCutDate() != null);
        controller.toggleDieCut(task.getId(), Map.of("dieCutReady", false));
        assertThat(items.findByTaskId(task.getId())).allMatch(i -> !Boolean.TRUE.equals(i.getDieCutReady()) && i.getDieCutDate() == null);
    }

    @Test void bulkDieCutValidatesEveryTaskBeforeChangingAnyItem() {
        delivered(task, List.of(first, second));
        var other = createTask("OTHER" + UUID.randomUUID().toString().substring(0, 8), null);
        createItem(other, false, false);
        assertThatThrownBy(() -> controller.bulkDieCut(order.getId(), Map.of("dieCutReady", true))).isInstanceOf(BusinessException.class);
        assertThat(first.getDieCutReady()).isFalse();
        assertThat(second.getDieCutReady()).isFalse();
        assertThat(task.getDieCutReady()).isFalse();
    }

    @Test void movingAnItemPreservesItsIdAndRecomputesBothTaskGates() throws Exception {
        first.setDieCutReady(true);
        first.setLeatherDelivered(true);
        items.save(first);
        task.setLeatherDelivered(true);
        tasks.save(task);
        var target = createTask("DEST" + UUID.randomUUID().toString().substring(0, 8), 2);
        createItem(target, false, false);
        controller.moveTaskItem(new TaskController.MoveTaskItemRequest(first.getId(), 2, "2026-10-13"));
        assertThat(items.findById(first.getId()).orElseThrow().getTaskId()).isEqualTo(target.getId());
        assertThat(tasks.findById(target.getId()).orElseThrow().getDieCutReady()).isFalse();
        assertThat(tasks.findById(target.getId()).orElseThrow().getLeatherDelivered()).isFalse();
        assertThat(tasks.findById(task.getId()).orElseThrow().getLeatherDelivered()).isFalse();
        assertThat(second.getTaskId()).isEqualTo(task.getId());
    }

    @Test void partialManualMovementDoesNotOpenWholeTaskGate() throws Exception {
        manual(BigDecimal.ONE);
        leather.syncOrderDeliveries(order.getId());
        assertThat(task.getLeatherDelivered()).isFalse();
        assertThat(first.getLeatherDelivered()).isFalse();
    }

    @Test void editingOrderKeepsReceivedItemAndRejectsItsRemoval() throws Exception {
        var request = orderRequest();
        orderController.update(order.getId(), request);
        assertThat(orderItems.findByProductionOrderId(order.getId())).extracting(ProductionOrderItemEntity::getId).containsExactly(orderItem.getId());
        assertThat(orderItems.findById(orderItem.getId()).orElseThrow().getWarehouseReceivedQty()).isEqualTo(1);
        request.setItems(List.of());
        assertThatThrownBy(() -> orderController.update(order.getId(), request)).isInstanceOf(BusinessException.class);
    }

    @Test @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void failureAfterSavingOrderHeaderRollsBackEntireEdit() throws Exception {
        ProductionOrderItemUpdateService target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(itemUpdates);
        doThrow(new IllegalStateException("Error de persistencia verificado")).when(target).apply(any());
        assertThatThrownBy(() -> orderController.update(order.getId(), orderRequest())).isInstanceOf(IllegalStateException.class);
        assertThat(orders.findById(order.getId()).orElseThrow().getObservations()).isEqualTo("Original");
        assertThat(orderItems.findById(orderItem.getId()).orElseThrow().getWarehouseReceivedQty()).isEqualTo(1);
    }

    @Test void orderDeliveryOpensGatesWithoutClearingEarlierFlags() throws Exception {
        legacyFlag(second);
        manual(BigDecimal.ONE);
        leather.syncOrderDeliveries(order.getId());
        assertThat(items.findById(second.getId()).orElseThrow().getLeatherDelivered()).isTrue();
        assertThat(items.findById(first.getId()).orElseThrow().getLeatherDelivered()).isFalse();
    }

    @Test void manualDeliveryCannotRepeatLeatherAlreadyDeliveredByProduct() throws Exception {
        leather.setDelivered(task, first, true);
        assertThatThrownBy(() -> leatherInventory.createDelivery(delivery("3"))).isInstanceOf(BusinessException.class)
                .hasMessageContaining("hasta 2");
        assertThat(inventory.findByMaterialId(material.getId()).orElseThrow().getQuantityAvailable()).isEqualByComparingTo("98");
        leatherInventory.createDelivery(delivery("2"));
        assertThat(inventory.findByMaterialId(material.getId()).orElseThrow().getQuantityAvailable()).isEqualByComparingTo("96");
    }

    @Test void cancellingOrderDeliveryRevokesOnlyTheFlagsItOpened() throws Exception {
        legacyFlag(second);
        var movement = leatherInventory.createDelivery(delivery("4"));
        assertThat(items.findById(first.getId()).orElseThrow().getLeatherDelivered()).isTrue();
        leatherInventory.cancelMovement(movement.getId(), "Registro duplicado");
        assertThat(items.findById(first.getId()).orElseThrow().getLeatherDelivered()).isFalse();
        assertThat(items.findById(second.getId()).orElseThrow().getLeatherDelivered()).isTrue();
        assertThat(inventory.findByMaterialId(material.getId()).orElseThrow().getQuantityAvailable()).isEqualByComparingTo("100");
        assertThatThrownBy(() -> leatherInventory.cancelMovement(movement.getId(), "Reintento")).isInstanceOf(BusinessException.class);
    }

    @Test void regenerationKeepsTasksWithLeatherHistory() throws Exception {
        leather.setDelivered(task, first, true);
        leather.setDelivered(task, first, false);
        assertThatThrownBy(() -> generation.generateTasks(order.getId(), true)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("historial");
        assertThat(items.findById(first.getId())).isPresent();
    }

    @Test void cinchoTaskBornWithGatesCanBeDeleted() throws Exception {
        var cincho = tasks.save(TaskEntity.builder().code("CIN" + UUID.randomUUID().toString().substring(0, 8))
                .productionOrderId(order.getId()).productionOrderCode(order.getCode()).status("PENDING").quantity(1)
                .estimatedHours(0.1).observations("Materiales cincho (bodega)").leatherDelivered(true).dieCutReady(true).build());
        items.save(TaskItemEntity.builder().taskId(cincho.getId()).productionOrderItemId(orderItem.getId()).productId(product.getId())
                .quantity(1).estimatedHours(0.1).leatherDelivered(true).materialsDelivered(true).build());
        controller.delete(cincho.getId());
        assertThat(tasks.findById(cincho.getId())).isEmpty();
    }

    @Test void markingDieCutAgainKeepsOriginalDate() throws Exception {
        delivered(task, List.of(first, second));
        controller.toggleDieCut(task.getId(), Map.of("dieCutReady", true));
        LocalDate original = LocalDate.of(2026, 9, 1);
        task.setDieCutDate(original);
        tasks.save(task);
        first.setDieCutDate(original);
        items.save(first);
        controller.toggleDieCut(task.getId(), Map.of("dieCutReady", true));
        assertThat(tasks.findById(task.getId()).orElseThrow().getDieCutDate()).isEqualTo(original);
        assertThat(items.findById(first.getId()).orElseThrow().getDieCutDate()).isEqualTo(original);
    }

    private void legacyFlag(TaskItemEntity item) {
        item.setLeatherDelivered(true);
        item.setLeatherDeliveredAt(java.time.LocalDateTime.of(2026, 8, 1, 9, 0));
        items.save(item);
    }
    private LeatherMovementRequest delivery(String quantity) {
        var request = new LeatherMovementRequest();
        request.setMaterialId(material.getId());
        request.setQuantity(new BigDecimal(quantity));
        request.setProductionOrderId(order.getId());
        request.setDeliveredBy("Bodega");
        request.setReceivedBy("Corte");
        return request;
    }
    private TaskEntity createTask(String code, Integer desk) {
        return tasks.save(TaskEntity.builder().code(code).productionOrderId(order.getId()).productionOrderCode(order.getCode())
                .status("PENDING").quantity(2).estimatedHours(1.0).desk(desk).scheduledDate(LocalDate.of(2026, 10, 13)).build());
    }
    private TaskItemEntity createItem(TaskEntity owner, boolean leatherReady, boolean cut) {
        return items.save(TaskItemEntity.builder().taskId(owner.getId()).productionOrderItemId(orderItem.getId())
                .productId(product.getId()).productCode(product.getCode()).productName(product.getName()).quantity(1)
                .estimatedHours(0.5).leatherDelivered(leatherReady).dieCutReady(cut).build());
    }
    private void delivered(TaskEntity owner, List<TaskItemEntity> detail) {
        owner.setLeatherDelivered(true);
        tasks.save(owner);
        detail.forEach(i -> { i.setLeatherDelivered(true); items.save(i); });
    }
    private void manual(BigDecimal quantity) {
        var stock = inventory.findByMaterialId(material.getId()).orElseThrow();
        stock.setQuantityAvailable(stock.getQuantityAvailable().subtract(quantity));
        stock.setTotalDelivered(stock.getTotalDelivered().add(quantity));
        inventory.save(stock);
        movements.save(LeatherMovementEntity.builder().materialId(material.getId()).productionOrderId(order.getId())
                .movementType("SALIDA").quantity(quantity).movementDate(LocalDate.now()).balanceAfter(stock.getQuantityAvailable()).build());
    }
    private ProductionOrderRequest orderRequest() {
        return ProductionOrderRequest.builder().code(order.getCode()).orderType("NORMAL").observations("Editada")
                .items(List.of(ProductionOrderItemRequest.builder().id(orderItem.getId()).productId(product.getId()).quantity(2).build())).build();
    }
}
