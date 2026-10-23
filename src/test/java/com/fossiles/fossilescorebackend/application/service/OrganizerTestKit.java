package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderGroupEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderGroupMemberRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderGroupRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.StreamSupport;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Un {@link TaskOrganizerService} real sobre repositorios simulados con datos en memoria, para
 * probar las reglas de creación de tareas sin base de datos.
 */
final class OrganizerTestKit {

    final TaskRepository taskRepository = mock(TaskRepository.class);
    final TaskItemRepository taskItemRepository = mock(TaskItemRepository.class);
    final ProductionOrderRepository productionOrderRepository = mock(ProductionOrderRepository.class);
    final ProductionOrderItemRepository productionOrderItemRepository = mock(ProductionOrderItemRepository.class);
    final ProductRepository productRepository = mock(ProductRepository.class);
    final ColorRepository colorRepository = mock(ColorRepository.class);
    final TaskCodeGenerator taskCodeGenerator = mock(TaskCodeGenerator.class);
    final ProductionOrderGroupRepository groupRepository = mock(ProductionOrderGroupRepository.class);
    final ProductionOrderGroupMemberRepository groupMemberRepository = mock(ProductionOrderGroupMemberRepository.class);
    final TaskOrganizerService service;

    /** OP consolidadas en memoria y la consolidada activa de cada OP. */
    final Map<Long, ProductionOrderGroupEntity> groups = new LinkedHashMap<>();
    final Map<Long, Long> groupOfOrder = new HashMap<>();

    final Map<Long, ProductionOrderEntity> orders = new LinkedHashMap<>();
    final Map<Long, ProductionOrderItemEntity> items = new LinkedHashMap<>();
    final Map<Long, ProductEntity> products = new LinkedHashMap<>();
    /** Unidades que ya cubren tareas existentes, por ítem. */
    final Map<Long, Integer> assigned = new HashMap<>();
    final List<TaskEntity> savedTasks = new ArrayList<>();
    final List<TaskItemEntity> savedTaskItems = new ArrayList<>();
    private final AtomicLong taskIds = new AtomicLong(900);

    OrganizerTestKit() {
        service = new TaskOrganizerService(taskRepository, taskItemRepository, productionOrderRepository,
                productionOrderItemRepository, productRepository, colorRepository, taskCodeGenerator,
                groupRepository, groupMemberRepository);
        try {
            when(groupRepository.findById(anyLong()))
                    .thenAnswer(inv -> Optional.ofNullable(groups.get(inv.<Long>getArgument(0))));
            when(groupMemberRepository.groupIdByOrderId(anyCollection())).thenAnswer(inv -> {
                Map<Long, Long> out = new HashMap<>();
                for (Object id : (java.util.Collection<?>) inv.getArgument(0)) {
                    if (groupOfOrder.containsKey(id)) {
                        out.put((Long) id, groupOfOrder.get(id));
                    }
                }
                return out;
            });
            when(productionOrderRepository.findById(anyLong()))
                    .thenAnswer(inv -> Optional.ofNullable(orders.get(inv.<Long>getArgument(0))));
            when(productionOrderRepository.findAllById(any())).thenAnswer(inv -> byIds(inv.getArgument(0), orders));
            when(productionOrderRepository.save(any(ProductionOrderEntity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(productionOrderItemRepository.findAllById(any())).thenAnswer(inv -> byIds(inv.getArgument(0), items));
            when(productionOrderItemRepository.findAllByIdForUpdate(any()))
                    .thenAnswer(inv -> byIds(inv.getArgument(0), items));
            when(productRepository.findById(anyLong()))
                    .thenAnswer(inv -> Optional.ofNullable(products.get(inv.<Long>getArgument(0))));
            when(productRepository.findAllById(any())).thenAnswer(inv -> byIds(inv.getArgument(0), products));
            when(taskItemRepository.assignedQuantityMap(anyCollection())).thenAnswer(inv -> {
                Map<Long, Integer> out = new HashMap<>();
                for (Object id : (java.util.Collection<?>) inv.getArgument(0)) {
                    if (assigned.containsKey(id)) {
                        out.put((Long) id, assigned.get(id));
                    }
                }
                return out;
            });
            when(taskRepository.save(any(TaskEntity.class))).thenAnswer(inv -> {
                TaskEntity t = inv.getArgument(0);
                if (t.getId() == null) {
                    t.setId(taskIds.incrementAndGet());
                }
                savedTasks.add(t);
                return t;
            });
            when(taskItemRepository.save(any(TaskItemEntity.class))).thenAnswer(inv -> {
                TaskItemEntity i = inv.getArgument(0);
                savedTaskItems.add(i);
                return i;
            });
            when(taskCodeGenerator.generateTaskCode())
                    .thenAnswer(inv -> String.format("TK-%05d", taskIds.get() + 1));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static <T> List<T> byIds(Iterable<?> ids, Map<Long, T> source) {
        return StreamSupport.stream(ids.spliterator(), false)
                .map(source::get).filter(java.util.Objects::nonNull).toList();
    }

    ProductionOrderEntity order(long id, String code, String type, String status) {
        ProductionOrderEntity po = ProductionOrderEntity.builder()
                .id(id).code(code).orderType(type).status(status)
                .createdAt(java.time.LocalDateTime.of(2026, 10, 1, 8, 0).plusMinutes(id))
                .build();
        orders.put(id, po);
        return po;
    }

    /** OP normal (OPK) pendiente. */
    ProductionOrderEntity order(long id) {
        return order(id, "OPK-" + id, "NORMAL", "PENDING");
    }

    /** OP consolidada activa con esas órdenes. */
    ProductionOrderGroupEntity group(long id, String code, long... orderIds) {
        ProductionOrderGroupEntity g = ProductionOrderGroupEntity.builder()
                .id(id).code(code).family("OPV").status(ProductionOrderGroupEntity.ACTIVE).build();
        groups.put(id, g);
        for (long orderId : orderIds) {
            groupOfOrder.put(orderId, id);
        }
        return g;
    }

    ProductionOrderEntity opl(long id) {
        return order(id, "OPL-" + id, "VENTA_EN_LINEA", "PENDING");
    }

    ProductEntity product(long id, double hoursPerUnit) {
        ProductEntity p = ProductEntity.builder().id(id).code("P-" + id).name("Producto " + id)
                .prdTime(hoursPerUnit).build();
        products.put(id, p);
        return p;
    }

    ProductionOrderItemEntity item(long id, long orderId, long productId, int quantity) {
        ProductionOrderItemEntity item = ProductionOrderItemEntity.builder()
                .id(id).productionOrderId(orderId).productId(productId).quantity(quantity).build();
        items.put(id, item);
        return item;
    }
}
