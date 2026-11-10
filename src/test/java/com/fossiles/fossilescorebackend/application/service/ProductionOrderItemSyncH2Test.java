package com.fossiles.fossilescorebackend.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fossiles.fossilescorebackend.application.dto.request.ProductionOrderItemRequest;
import com.fossiles.fossilescorebackend.application.dto.request.ProductionOrderRequest;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderWarehouseUnitEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderWarehouseUnitRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PUT /api/production-orders/{id} against real repositories on in-memory H2.
 * The test method is not transactional: each request commits or rolls back on its own.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:op_item_sync;MODE=PostgreSQL;NON_KEYWORDS=YEAR,MONTH;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "sentry.enabled=false",
        "sentry.dsn=",
        "spring.task.scheduling.enabled=false"
})
@AutoConfigureMockMvc(addFilters = false)
class ProductionOrderItemSyncH2Test {

    private static final LocalDate ORIGINAL_DATE = LocalDate.of(2026, 1, 15);
    private static final LocalDate CHANGED_DATE = LocalDate.of(2026, 12, 1);
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired ProductionOrderRepository orders;
    @Autowired ProductionOrderItemRepository items;
    @Autowired ProductRepository products;
    @Autowired ColorRepository colors;
    @Autowired TaskRepository tasks;
    @Autowired TaskItemRepository taskItems;
    @Autowired ProductionOrderWarehouseUnitRepository units;

    @Test
    @DisplayName("1. PUT that would remove a live-task line returns 409 and writes nothing")
    void putRemovingLiveTaskLineReturns409AndWritesNothing() throws Exception {
        ProductEntity keptProduct = product("K");
        ProductEntity blockedProduct = product("B");
        ColorEntity color = color("NEGRO");
        ProductionOrderEntity order = order();
        ProductionOrderItemEntity kept = line(order, keptProduct, color, 4, 0);
        ProductionOrderItemEntity blocked = line(order, blockedProduct, color, 5, 1);
        liveTask(order, blocked, "PENDING", 2);
        List<LineSnap> before = snaps(order);

        putOrder(order, "CHANGED", CHANGED_DATE, List.of(req(kept)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(containsString(
                        "No se puede quitar la línea " + blockedProduct.getCode() + " / NEGRO porque tiene tareas.")));

        assertUntouched(order, before);
    }

    @Test
    @DisplayName("2. Line sent with id keeps id, warehouseReceivedQty and online-sale ids")
    void putWithLineIdKeepsStableFieldsAndUpdatesQuantityAndObservations() throws Exception {
        ProductEntity product = product("P");
        ColorEntity color = color("NEGRO");
        ProductionOrderEntity order = order();
        ProductionOrderItemEntity line = line(order, product, color, 7, 4);
        line.setOnlineSaleId(55L);
        line.setOnlineSaleItemId(66L);
        line.setObservations("vieja");
        items.save(line);

        putOrder(order, "ORIGINAL", ORIGINAL_DATE, List.of(req(line.getId(), product, color, 9, "nota")))
                .andExpect(status().isOk());

        ProductionOrderItemEntity saved = items.findById(line.getId()).orElseThrow();
        assertThat(saved.getId()).isEqualTo(line.getId());
        assertThat(saved.getWarehouseReceivedQty()).isEqualTo(4);
        assertThat(saved.getOnlineSaleId()).isEqualTo(55L);
        assertThat(saved.getOnlineSaleItemId()).isEqualTo(66L);
        assertThat(saved.getQuantity()).isEqualTo(9);
        assertThat(saved.getObservations()).isEqualTo("nota");
        assertThat(saved.getProductId()).isEqualTo(product.getId());
        assertThat(saved.getColorId()).isEqualTo(color.getId());
        assertThat(items.findByProductionOrderId(order.getId())).hasSize(1);
    }

    @Test
    @DisplayName("3. Identity change on a live task_item line is 409; the same change on an unreferenced line succeeds")
    void putIdentityChangeBlockedOnLiveTaskItemAndAllowedWhenUnreferenced() throws Exception {
        ProductEntity original = product("O");
        ProductEntity next = product("N");
        ColorEntity negro = color("NEGRO");
        ColorEntity cafe = color("CAFE");
        ProductionOrderEntity blockedOrder = order();
        ProductionOrderItemEntity blocked = line(blockedOrder, original, negro, 4, 0);
        liveTask(blockedOrder, blocked, "PENDING", 1);
        List<LineSnap> beforeBlocked = snaps(blockedOrder);

        putOrder(blockedOrder, "CHANGED", CHANGED_DATE, List.of(req(blocked.getId(), next, cafe, 4, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(containsString(
                        "No se puede cambiar la línea " + original.getCode() + " / NEGRO porque tiene tareas.")));
        assertUntouched(blockedOrder, beforeBlocked);

        ProductionOrderEntity freeOrder = order();
        ProductionOrderItemEntity free = line(freeOrder, original, negro, 4, 2);

        putOrder(freeOrder, "ORIGINAL", ORIGINAL_DATE, List.of(req(free.getId(), next, cafe, 4, null)))
                .andExpect(status().isOk());

        ProductionOrderItemEntity saved = items.findById(free.getId()).orElseThrow();
        assertThat(saved.getId()).isEqualTo(free.getId());
        assertThat(saved.getProductId()).isEqualTo(next.getId());
        assertThat(saved.getColorId()).isEqualTo(cafe.getId());
        assertThat(saved.getWarehouseReceivedQty()).isEqualTo(2);
        assertThat(saved.getQuantity()).isEqualTo(4);
    }

    @Test
    @DisplayName("4. A line referenced only by a CANCELLED task can be removed or changed")
    void putCanRemoveOrChangeLineReferencedOnlyByCancelledTask() throws Exception {
        ProductEntity original = product("C");
        ProductEntity next = product("D");
        ColorEntity negro = color("NEGRO");
        ColorEntity cafe = color("CAFE");

        ProductionOrderEntity changeOrder = order();
        ProductionOrderItemEntity changeLine = line(changeOrder, original, negro, 4, 1);
        liveTask(changeOrder, changeLine, "CANCELLED", 4);

        putOrder(changeOrder, "ORIGINAL", ORIGINAL_DATE, List.of(req(changeLine.getId(), next, cafe, 4, null)))
                .andExpect(status().isOk());
        ProductionOrderItemEntity changed = items.findById(changeLine.getId()).orElseThrow();
        assertThat(changed.getProductId()).isEqualTo(next.getId());
        assertThat(changed.getColorId()).isEqualTo(cafe.getId());
        assertThat(changed.getWarehouseReceivedQty()).isEqualTo(1);

        ProductionOrderEntity removeOrder = order();
        ProductionOrderItemEntity kept = line(removeOrder, next, cafe, 3, 0);
        ProductionOrderItemEntity removed = line(removeOrder, original, negro, 4, 0);
        liveTask(removeOrder, removed, "CANCELLED", 4);

        putOrder(removeOrder, "ORIGINAL", ORIGINAL_DATE, List.of(req(kept)))
                .andExpect(status().isOk());
        assertThat(items.findById(removed.getId())).isEmpty();
        ProductionOrderItemEntity stillKept = items.findById(kept.getId()).orElseThrow();
        assertThat(stillKept.getQuantity()).isEqualTo(3);
        assertThat(items.findByProductionOrderId(removeOrder.getId())).hasSize(1);
    }

    @Test
    @DisplayName("5a. With 8 assigned to live tasks, saving 7 is 409; saving 8 succeeds")
    void putQuantityBelowAssignedFloorReturns409AndEqualSucceeds() throws Exception {
        ProductEntity product = product("Q");
        ColorEntity color = color("NEGRO");
        ProductionOrderEntity order = order();
        ProductionOrderItemEntity line = line(order, product, color, 10, 0);
        liveTask(order, line, "PENDING", 8);
        List<LineSnap> before = snaps(order);

        putOrder(order, "CHANGED", CHANGED_DATE, List.of(req(line.getId(), product, color, 7, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(containsString("por debajo de 8")));
        assertUntouched(order, before);

        putOrder(order, "ORIGINAL", ORIGINAL_DATE, List.of(req(line.getId(), product, color, 8, null)))
                .andExpect(status().isOk());
        assertThat(items.findById(line.getId()).orElseThrow().getQuantity()).isEqualTo(8);
        assertThat(items.findById(line.getId()).orElseThrow().getWarehouseReceivedQty()).isZero();
    }

    @Test
    @DisplayName("5b. With warehouseReceivedQty 10 and 3 assigned, the floor is 10")
    void putQuantityFloorIsMaxOfAssignedAndWarehouseReceived() throws Exception {
        ProductEntity product = product("W");
        ColorEntity color = color("NEGRO");
        ProductionOrderEntity order = order();
        ProductionOrderItemEntity line = line(order, product, color, 12, 10);
        liveTask(order, line, "PENDING", 3);
        List<LineSnap> before = snaps(order);

        putOrder(order, "CHANGED", CHANGED_DATE, List.of(req(line.getId(), product, color, 9, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(containsString("por debajo de 10")));
        assertUntouched(order, before);

        putOrder(order, "ORIGINAL", ORIGINAL_DATE, List.of(req(line.getId(), product, color, 10, null)))
                .andExpect(status().isOk());
        ProductionOrderItemEntity saved = items.findById(line.getId()).orElseThrow();
        assertThat(saved.getQuantity()).isEqualTo(10);
        assertThat(saved.getWarehouseReceivedQty()).isEqualTo(10);
    }

    @Test
    @DisplayName("6. Null id with a unique product+color match reuses the existing line id")
    void putNullIdReusesUniqueProductColorLine() throws Exception {
        ProductEntity product = product("F");
        ColorEntity color = color("NEGRO");
        ProductionOrderEntity order = order();
        ProductionOrderItemEntity line = line(order, product, color, 4, 6);
        line.setOnlineSaleId(21L);
        line.setOnlineSaleItemId(22L);
        items.save(line);

        putOrder(order, "ORIGINAL", ORIGINAL_DATE, List.of(req(null, product, color, 9, "respaldo")))
                .andExpect(status().isOk());

        ProductionOrderItemEntity saved = items.findById(line.getId()).orElseThrow();
        assertThat(saved.getId()).isEqualTo(line.getId());
        assertThat(saved.getQuantity()).isEqualTo(9);
        assertThat(saved.getObservations()).isEqualTo("respaldo");
        assertThat(saved.getWarehouseReceivedQty()).isEqualTo(6);
        assertThat(saved.getOnlineSaleId()).isEqualTo(21L);
        assertThat(saved.getOnlineSaleItemId()).isEqualTo(22L);
        assertThat(items.findByProductionOrderId(order.getId())).hasSize(1);
    }

    @Test
    @DisplayName("7. A warehouse unit referencing a line blocks its removal (409)")
    void putRemovingWarehouseUnitLineReturns409() throws Exception {
        ProductEntity keptProduct = product("U");
        ProductEntity blockedProduct = product("V");
        ColorEntity color = color("NEGRO");
        ProductionOrderEntity order = order();
        ProductionOrderItemEntity kept = line(order, keptProduct, color, 3, 0);
        ProductionOrderItemEntity blocked = line(order, blockedProduct, color, 2, 0);
        units.save(ProductionOrderWarehouseUnitEntity.builder()
                .productionOrderId(order.getId())
                .productionOrderItemId(blocked.getId())
                .sizeKey("")
                .unitSeq(1)
                .receiptStatus("PENDING")
                .build());
        List<LineSnap> before = snaps(order);

        putOrder(order, "CHANGED", CHANGED_DATE, List.of(req(kept)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(containsString(
                        "No se puede quitar la línea " + blockedProduct.getCode()
                                + " / NEGRO porque tiene unidades de bodega.")));
        assertUntouched(order, before);
    }

    private ResultActions putOrder(
            ProductionOrderEntity order,
            String observations,
            LocalDate deliveryDate,
            List<ProductionOrderItemRequest> lines) throws Exception {
        ProductionOrderRequest body = ProductionOrderRequest.builder()
                .code(order.getCode())
                .orderType(order.getOrderType())
                .observations(observations)
                .deliveryDate(deliveryDate)
                .items(lines)
                .build();
        return mvc.perform(put("/api/production-orders/{id}", order.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .characterEncoding(StandardCharsets.UTF_8)
                .content(objectMapper.writeValueAsString(body)));
    }

    private void assertUntouched(ProductionOrderEntity order, List<LineSnap> before) {
        ProductionOrderEntity after = orders.findById(order.getId()).orElseThrow();
        assertThat(after.getObservations()).isEqualTo("ORIGINAL");
        assertThat(after.getDeliveryDate()).isEqualTo(ORIGINAL_DATE);
        assertThat(snaps(order)).containsExactlyInAnyOrderElementsOf(before);
    }

    private List<LineSnap> snaps(ProductionOrderEntity order) {
        return items.findByProductionOrderId(order.getId()).stream().map(LineSnap::of).toList();
    }

    private ProductionOrderEntity order() {
        return orders.save(ProductionOrderEntity.builder()
                .code(code("OP"))
                .orderType("NORMAL")
                .status("PENDING")
                .observations("ORIGINAL")
                .startDate(ORIGINAL_DATE)
                .deliveryDate(ORIGINAL_DATE)
                .build());
    }

    private ProductionOrderItemEntity line(
            ProductionOrderEntity order, ProductEntity product, ColorEntity color, int qty, int received) {
        return items.save(ProductionOrderItemEntity.builder()
                .productionOrderId(order.getId())
                .productId(product.getId())
                .colorId(color.getId())
                .quantity(qty)
                .warehouseReceivedQty(received)
                .build());
    }

    private TaskEntity liveTask(
            ProductionOrderEntity order, ProductionOrderItemEntity line, String status, int assignedQty) {
        TaskEntity task = tasks.save(TaskEntity.builder()
                .code(code("TK"))
                .productionOrderId(order.getId())
                .productionOrderCode(order.getCode())
                .productionOrderItemId(line.getId())
                .productId(line.getProductId())
                .colorId(line.getColorId())
                .quantity(assignedQty)
                .status(status)
                .delayed(false)
                .commissionEligible(true)
                .dieCutReady(false)
                .leatherDelivered(false)
                .materialsDelivered(false)
                .build());
        taskItems.save(TaskItemEntity.builder()
                .taskId(task.getId())
                .productionOrderItemId(line.getId())
                .productId(line.getProductId())
                .colorId(line.getColorId())
                .quantity(assignedQty)
                .daySaleExtra(false)
                .dieCutReady(false)
                .build());
        return task;
    }

    private ProductEntity product(String prefix) {
        return products.save(ProductEntity.builder()
                .code(code(prefix))
                .name("Producto " + prefix)
                .requiresMaterials(false)
                .build());
    }

    private ColorEntity color(String name) {
        return colors.save(ColorEntity.builder().name(name).build());
    }

    private static ProductionOrderItemRequest req(ProductionOrderItemEntity line) {
        return req(line.getId(), line.getProductId(), line.getColorId(), line.getQuantity(), line.getObservations());
    }

    private static ProductionOrderItemRequest req(
            Long id, ProductEntity product, ColorEntity color, Integer quantity, String observations) {
        return req(id, product.getId(), color.getId(), quantity, observations);
    }

    private static ProductionOrderItemRequest req(
            Long id, Long productId, Long colorId, Integer quantity, String observations) {
        return ProductionOrderItemRequest.builder()
                .id(id)
                .productId(productId)
                .colorId(colorId)
                .quantity(quantity)
                .observations(observations)
                .build();
    }

    private static String code(String prefix) {
        return prefix + SEQ.incrementAndGet();
    }

    private record LineSnap(
            Long id,
            Long productId,
            Long colorId,
            Integer quantity,
            Integer warehouseReceivedQty,
            Long onlineSaleId,
            Long onlineSaleItemId,
            String observations) {
        static LineSnap of(ProductionOrderItemEntity line) {
            return new LineSnap(
                    line.getId(),
                    line.getProductId(),
                    line.getColorId(),
                    line.getQuantity(),
                    line.getWarehouseReceivedQty(),
                    line.getOnlineSaleId(),
                    line.getOnlineSaleItemId(),
                    line.getObservations());
        }
    }
}
