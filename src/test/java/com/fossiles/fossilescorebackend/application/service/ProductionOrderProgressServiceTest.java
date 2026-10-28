package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.ProductionOrderProgressResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ProductionOrderProgressResponse.OrderProgress;
import com.fossiles.fossilescorebackend.application.dto.response.ProductionOrderProgressResponse.Stages;
import com.fossiles.fossilescorebackend.application.dto.response.ProductionOrderProgressResponse.TaskProgress;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionProgressRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductionOrderProgressServiceTest {

    @Mock private ProductionOrderRepository productionOrderRepository;
    @Mock private ProductionOrderItemRepository productionOrderItemRepository;
    @Mock private ProductionProgressRepository productionProgressRepository;
    @Mock private TaskItemRepository taskItemRepository;
    @Mock private ProductRepository productRepository;
    @Mock private ColorRepository colorRepository;

    private ProductionOrderProgressService service;

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);
    private final ProductEntity billetera = ProductEntity.builder().id(1L).code("B-7").name("Billetera").build();
    private final ProductEntity llavero = ProductEntity.builder().id(2L).code("LL-1").name("Llavero")
            .requiresMaterials(false).build();

    @BeforeEach
    void setUp() {
        service = new ProductionOrderProgressService(productionOrderRepository, productionOrderItemRepository,
                productionProgressRepository, taskItemRepository, productRepository, colorRepository);
        lenient().when(productRepository.findAllById(anyIterable())).thenReturn(List.of(billetera, llavero));
        lenient().when(colorRepository.findAllById(anyIterable()))
                .thenReturn(List.of(ColorEntity.builder().id(3L).name("NEGRO").build()));
    }

    @Test
    void splitsPiecesByStageAndStagesAddUpToOrdered() {
        ProductionOrderEntity op1 = order(10L, "OPK-1", LocalDate.of(2026, 11, 5));
        ProductionOrderEntity op2 = order(11L, "OPV-2", LocalDate.of(2026, 10, 25)); // atrasada
        ProductionOrderItemEntity a = item(100L, 10L, 1L, 20, null, 0);
        ProductionOrderItemEntity b = item(101L, 10L, 2L, null, "{\"16\":3,\"18\":2}", 0); // 5 por tallas
        ProductionOrderItemEntity c = item(102L, 11L, 1L, 4, null, 0);

        TaskEntity t1 = task(1L, "TK-1", 10L, "IN_PROGRESS");
        t1.setDesk(4);
        t1.setStartedAt(LocalDateTime.of(2026, 10, 28, 8, 0));
        t1.setPausedAt(LocalDateTime.of(2026, 10, 28, 10, 0));
        TaskEntity t2 = task(2L, "TK-2", 10L, "PENDING");
        TaskEntity t3 = task(3L, "TK-3", 10L, "AWAITING_WAREHOUSE");
        TaskEntity t4 = task(4L, "TK-4", 10L, "COMPLETED");
        // Tarea de la OPL-9 que lleva 2 piezas de OPV-2 como extra.
        TaskEntity t5 = task(5L, "TK-5", 99L, "IN_PROGRESS");
        t5.setProductionOrderCode("OPL-9");

        when(productionOrderRepository.findByStatusIn(ProductionOrderProgressService.ACTIVE_STATUSES))
                .thenReturn(List.of(op1, op2));
        when(productionOrderItemRepository.findByProductionOrderIdIn(anyCollection())).thenReturn(List.of(a, b, c));
        when(productionProgressRepository.findActiveTasksForOrders(anyCollection())).thenReturn(List.of(t1, t2, t3, t4, t5));
        when(taskItemRepository.findByTaskIdIn(anyList())).thenReturn(List.of(
                ti(1L, 100L, 6, false),
                ti(1L, 101L, 2, false),
                ti(2L, 100L, 4, false),
                ti(3L, 100L, 3, false),
                ti(4L, 100L, 2, false),
                ti(5L, 102L, 2, true),
                ti(5L, 777L, 9, false))); // línea de una OP que no está activa: no cuenta

        ProductionOrderProgressResponse res = service.report(TODAY);

        // Ordenadas por entrega: OPV-2 (25-oct) primero.
        assertThat(res.getOrders()).extracting(OrderProgress::getCode).containsExactly("OPV-2", "OPK-1");

        OrderProgress opk1 = res.getOrders().get(1);
        // 20 + 5 = 25: 2 terminadas, 3 en bodega, 8 en mesa, 4 por iniciar, 8 sin tarea.
        assertStages(opk1.getStages(), 25, 8, 4, 8, 3, 2);
        assertThat(opk1.getDaysToDelivery()).isEqualTo(8);
        assertThat(opk1.getLate()).isFalse();
        assertThat(opk1.getTasksInProgress()).isEqualTo(1);
        assertThat(opk1.getTasks()).extracting(TaskProgress::getCode).containsExactly("TK-1", "TK-2", "TK-3", "TK-4");
        TaskProgress tk1 = opk1.getTasks().get(0);
        assertThat(tk1.getUnits()).isEqualTo(8);
        assertThat(tk1.getDesk()).isEqualTo(4);
        assertThat(tk1.getPaused()).isTrue();
        assertThat(tk1.getWorkflowStatus()).isEqualTo("IN_PRODUCTION");
        assertThat(tk1.getLines()).hasSize(2);
        assertThat(opk1.getLines()).extracting(l -> l.getStages().getUnits()).containsExactly(20, 5);

        OrderProgress opv2 = res.getOrders().get(0);
        assertThat(opv2.getLate()).isTrue();
        assertThat(opv2.getDaysToDelivery()).isEqualTo(-3);
        assertStages(opv2.getStages(), 4, 2, 0, 2, 0, 0);
        assertThat(opv2.getLines().get(0).getExtraUnits()).isEqualTo(2);
        TaskProgress extra = opv2.getTasks().get(0);
        assertThat(extra.getExtra()).isTrue();
        assertThat(extra.getBaseOrderCode()).isEqualTo("OPL-9");
        assertThat(extra.getUnits()).isEqualTo(2);
        assertThat(extra.getTaskUnits()).isEqualTo(11);

        assertStages(res.getTotals().getStages(), 29, 10, 4, 10, 3, 2);
        assertThat(res.getTotals().getLateOrders()).isEqualTo(1);
        assertThat(res.getTotals().getOrdersWorkingNow()).isEqualTo(2);
        assertThat(res.getTotals().getTasksInProgress()).isEqualTo(2);
    }

    @Test
    void receivedInWarehouseCountsAsDoneAndOverAssignmentIsTrimmed() {
        ProductionOrderEntity op = order(10L, "OPK-28", null);
        ProductionOrderItemEntity line = item(100L, 10L, 1L, 16, null, 12);
        TaskEntity done1 = task(1L, "TK-1", 10L, "COMPLETED");
        TaskEntity done2 = task(2L, "TK-2", 10L, "COMPLETED");
        TaskEntity waiting = task(3L, "TK-3", 10L, "AWAITING_WAREHOUSE");
        TaskEntity pending = task(4L, "TK-4", 10L, "PENDING");

        when(productionOrderRepository.findByStatusIn(any())).thenReturn(List.of(op));
        when(productionOrderItemRepository.findByProductionOrderIdIn(anyCollection())).thenReturn(List.of(line));
        when(productionProgressRepository.findActiveTasksForOrders(anyCollection()))
                .thenReturn(List.of(done1, done2, waiting, pending));
        when(taskItemRepository.findByTaskIdIn(anyList())).thenReturn(List.of(
                ti(1L, 100L, 4, false), ti(2L, 100L, 4, false), ti(3L, 100L, 8, false), ti(4L, 100L, 8, false)));

        OrderProgress row = service.report(TODAY).getOrders().get(0);

        // 8 terminadas + 4 recibidas de las 8 en bodega = 12; quedan 4 en bodega; lo pedido (16)
        // ya está cubierto, así que las 8 por iniciar sobran.
        assertStages(row.getStages(), 16, 0, 0, 0, 4, 12);
        assertThat(row.getOverAssignedUnits()).isEqualTo(8);
        assertThat(row.getReceivedUnits()).isEqualTo(12);
        assertThat(row.getDaysToDelivery()).isNull();
        assertThat(row.getLate()).isFalse();
    }

    @Test
    void legacyTaskWithoutItemsUsesItsOwnLineAndWorkflowFollowsDeliveries() {
        ProductionOrderEntity op = order(10L, "OPK-1", TODAY);
        ProductionOrderItemEntity line = item(100L, 10L, 2L, 10, null, 0);
        TaskEntity legacy = task(1L, "TK-OLD", 10L, "COMPLETED");
        legacy.setProductionOrderItemId(100L);
        legacy.setQuantity(3);
        TaskEntity noLeather = task(2L, "TK-2", 10L, "PENDING");
        TaskEntity ready = task(3L, "TK-3", 10L, "PENDING");
        ready.setLeatherDelivered(true);
        ready.setDieCutReady(true);
        ready.setDesk(2);

        when(productionOrderRepository.findByStatusIn(any())).thenReturn(List.of(op));
        when(productionOrderItemRepository.findByProductionOrderIdIn(anyCollection())).thenReturn(List.of(line));
        when(productionProgressRepository.findActiveTasksForOrders(anyCollection()))
                .thenReturn(List.of(legacy, noLeather, ready));
        // El llavero no pide materiales: lista para iniciar aunque no se hayan entregado.
        when(taskItemRepository.findByTaskIdIn(anyList())).thenReturn(List.of(
                llavero(2L, 100L, 2), llavero(3L, 100L, 1)));

        OrderProgress row = service.report(TODAY).getOrders().get(0);

        assertStages(row.getStages(), 10, 4, 3, 0, 0, 3);
        assertThat(row.getDaysToDelivery()).isZero();
        assertThat(row.getTasks()).extracting(TaskProgress::getCode).containsExactly("TK-2", "TK-3", "TK-OLD");
        assertThat(row.getTasks().get(0).getWorkflowStatus()).isEqualTo("PENDING_LEATHER");
        assertThat(row.getTasks().get(1).getWorkflowStatus()).isEqualTo("READY_TO_START");
        assertThat(row.getTasks().get(1).getRequiresMaterials()).isFalse();
        assertThat(row.getTasks().get(2).getUnits()).isEqualTo(3);
    }

    @Test
    void noActiveOrdersReturnsEmptyReport() {
        when(productionOrderRepository.findByStatusIn(any())).thenReturn(List.of());

        ProductionOrderProgressResponse res = service.report(TODAY);

        assertThat(res.getOrders()).isEmpty();
        assertStages(res.getTotals().getStages(), 0, 0, 0, 0, 0, 0);
    }

    private static void assertStages(Stages s, int units, int noTask, int pending, int inProgress, int warehouse, int done) {
        assertThat(s.getUnits()).as("units").isEqualTo(units);
        assertThat(s.getNoTask()).as("sin tarea").isEqualTo(noTask);
        assertThat(s.getPending()).as("por iniciar").isEqualTo(pending);
        assertThat(s.getInProgress()).as("en mesa").isEqualTo(inProgress);
        assertThat(s.getWarehouse()).as("bodega/QA").isEqualTo(warehouse);
        assertThat(s.getDone()).as("terminadas").isEqualTo(done);
        assertThat(s.getNoTask() + s.getPending() + s.getInProgress() + s.getWarehouse() + s.getDone())
                .as("las etapas suman lo pedido").isEqualTo(units);
    }

    private static ProductionOrderEntity order(Long id, String code, LocalDate delivery) {
        return ProductionOrderEntity.builder().id(id).code(code).orderType("NORMAL").status("IN_PROGRESS")
                .customerName("Cliente " + code).deliveryDate(delivery).build();
    }

    private static ProductionOrderItemEntity item(Long id, Long orderId, Long productId, Integer qty, String sizes,
                                                  int received) {
        return ProductionOrderItemEntity.builder().id(id).productionOrderId(orderId).productId(productId)
                .colorId(3L).quantity(qty).sizesData(sizes).warehouseReceivedQty(received).build();
    }

    private static TaskEntity task(Long id, String code, Long orderId, String status) {
        return TaskEntity.builder().id(id).code(code).productionOrderId(orderId).status(status).build();
    }

    private static TaskItemEntity llavero(Long taskId, Long lineId, int qty) {
        return TaskItemEntity.builder().taskId(taskId).productionOrderItemId(lineId).productId(2L)
                .quantity(qty).build();
    }

    private static TaskItemEntity ti(Long taskId, Long lineId, int qty, boolean extra) {
        return TaskItemEntity.builder().taskId(taskId).productionOrderItemId(lineId).productId(1L)
                .quantity(qty).daySaleExtra(extra).build();
    }
}
