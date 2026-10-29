package com.fossiles.fossilescorebackend.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.*;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Entrega de cuero por producto: registra o revierte la salida del kardex según la receta, una sola por ítem, y
 * mantiene las banderas de cuero de la orden.
 */
@Service
@RequiredArgsConstructor
public class TaskLeatherDeliveryService {
    private final LeatherMovementRepository movements;
    private final LeatherInventoryRepository inventories;
    private final ProductRepository products;
    private final ProductionOrderItemRepository orderItems;
    private final TaskRepository tasks;
    private final TaskItemRepository items;
    private final LeatherRequirementService requirements;
    private final SecurityUtil security;
    private final ObjectMapper mapper;

    // Una entrega por producto descuenta cuero una sola vez y conserva la salida para su reversión.
    @Transactional(rollbackFor = Exception.class)
    public void setDelivered(TaskEntity task, TaskItemEntity item, boolean delivered)
            throws BusinessException, ResourceNotFoundException {
        var active = movements.findByTaskItemIdAndMovementType(item.getId(), "SALIDA").orElse(null);
        if (!delivered) {
            assertCanReverse(task, item);
            if (active != null) reverse(active);
            else if (Boolean.TRUE.equals(item.getLeatherDelivered()) && hasManualDelivery(task.getProductionOrderId())) {
                throw new BusinessException("Anule la entrega de cuero por orden desde su kardex antes de desmarcar el producto.");
            }
        } else if (active == null) {
            var product = products.findById(item.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product", item.getProductId()));
            var need = requirements.resolveNeed(product, item.getColorId(), item.getQuantity() == null ? 0 : item.getQuantity());
            if (need.blocked()) throw new BusinessException(need.reason());
            if (!need.noneRequired()) {
                var inventory = inventories.findByMaterialIdForUpdate(need.materialId())
                        .orElseThrow(() -> new BusinessException("No hay inventario para el cuero requerido."));
                var previous = movements.findByProductionOrderIdOrderByCreatedAtDesc(task.getProductionOrderId());
                boolean manual = previous.stream().anyMatch(m -> m.getTaskItemId() == null
                        && "SALIDA".equals(m.getMovementType()) && Objects.equals(m.getMaterialId(), need.materialId()));
                if (manual && !orderMaterialCovered(task.getProductionOrderId(), need.materialId(), previous)) {
                    throw new BusinessException("La orden tiene una entrega manual parcial de este cuero; complete o concilie esa entrega en el kardex para evitar duplicarla.");
                }
                if (!manual) {
                    if (inventory.getQuantityAvailable().compareTo(need.qtyFt2()) < 0) {
                        throw new BusinessException("Cuero insuficiente: se necesitan " + need.qtyFt2()
                                + " ft² y hay " + inventory.getQuantityAvailable() + " ft² disponibles.");
                    }
                    inventory.setQuantityAvailable(inventory.getQuantityAvailable().subtract(need.qtyFt2()));
                    inventory.setTotalDelivered(inventory.getTotalDelivered().add(need.qtyFt2()));
                    inventories.save(inventory);
                    movements.save(LeatherMovementEntity.builder().movementType("SALIDA")
                            .taskItemId(item.getId()).productionOrderId(task.getProductionOrderId())
                            .materialId(need.materialId()).quantity(need.qtyFt2())
                            .movementDate(GuatemalaDateTime.today()).balanceAfter(inventory.getQuantityAvailable())
                            .observations("Entrega a producción: tarea " + task.getCode() + ", producto " + item.getProductCode())
                            .createdBy(security.getCurrentUserId()).build());
                }
            }
        }
        item.setLeatherDelivered(delivered);
        item.setLeatherDeliveredAt(delivered ? (item.getLeatherDeliveredAt() == null ? GuatemalaDateTime.now() : item.getLeatherDeliveredAt()) : null);
        items.save(item);
    }

    private void reverse(LeatherMovementEntity active) throws BusinessException {
        // El bloqueo de la salida evita que la anulación desde el kardex y la del producto la reviertan dos veces.
        var original = movements.findByIdForUpdate(active.getId()).orElse(null);
        if (original == null || !"SALIDA".equals(original.getMovementType())) return;
        var inventory = inventories.findByMaterialIdForUpdate(original.getMaterialId())
                .orElseThrow(() -> new BusinessException("No hay inventario para revertir la entrega."));
        inventory.setQuantityAvailable(inventory.getQuantityAvailable().add(original.getQuantity()));
        inventory.setTotalDelivered(inventory.getTotalDelivered().subtract(original.getQuantity()));
        inventories.save(inventory);
        original.setMovementType("ANULADO");
        movements.saveAndFlush(original);
        movements.save(LeatherMovementEntity.builder().movementType("ANULADO")
                .taskItemId(original.getTaskItemId()).productionOrderId(original.getProductionOrderId())
                .materialId(original.getMaterialId()).quantity(original.getQuantity())
                .movementDate(GuatemalaDateTime.today()).balanceAfter(inventory.getQuantityAvailable())
                .observations("Reversión de entrega #" + original.getId())
                .createdBy(security.getCurrentUserId()).build());
    }

    public void assertCanReverse(TaskEntity task, TaskItemEntity item) throws BusinessException {
        if (Boolean.TRUE.equals(task.getDieCutReady()) || Boolean.TRUE.equals(item.getDieCutReady())
                || Boolean.TRUE.equals(task.getMaterialsDelivered()) || Boolean.TRUE.equals(item.getMaterialsDelivered())
                || !"PENDING".equals(task.getStatus())) {
            throw new BusinessException("No se puede revertir cuero de un producto que ya avanzó en producción.");
        }
    }

    public void assertMovementCanReverse(LeatherMovementEntity movement) throws BusinessException {
        if (!"SALIDA".equals(movement.getMovementType()) || movement.getProductionOrderId() == null) return;
        for (var task : tasks.findByProductionOrderId(movement.getProductionOrderId())) {
            if ("CANCELLED".equals(task.getStatus())) continue;
            var detail = items.findByTaskId(task.getId());
            if (detail.isEmpty() && movement.getTaskItemId() == null
                    && (!"PENDING".equals(task.getStatus()) || Boolean.TRUE.equals(task.getDieCutReady())
                    || Boolean.TRUE.equals(task.getMaterialsDelivered()))) {
                throw new BusinessException("No se puede revertir cuero de una tarea que ya avanzó en producción.");
            }
            for (var item : detail) {
                if (movement.getTaskItemId() == null || Objects.equals(movement.getTaskItemId(), item.getId())) {
                    assertCanReverse(task, item);
                }
            }
        }
    }

    // Solo abre la compuerta: una bandera sin salida propia puede venir de una entrega anterior al kardex por producto.
    @Transactional(rollbackFor = Exception.class)
    public void syncOrderDeliveries(Long orderId) throws BusinessException {
        if (orderId == null) return;
        var previous = movements.findByProductionOrderIdOrderByCreatedAtDesc(orderId);
        Map<Long, Boolean> coverage = new HashMap<>();
        for (var task : tasks.findByProductionOrderId(orderId)) {
            if (!"PENDING".equals(task.getStatus())) continue;
            var detail = items.findByTaskId(task.getId());
            for (var item : detail) {
                if (Boolean.TRUE.equals(item.getLeatherDelivered()) || !isCovered(orderId, item, previous, coverage)) continue;
                item.setLeatherDelivered(true);
                item.setLeatherDeliveredAt(GuatemalaDateTime.now());
                items.save(item);
            }
            if (!detail.isEmpty() && !Boolean.TRUE.equals(task.getLeatherDelivered())
                    && detail.stream().allMatch(item -> Boolean.TRUE.equals(item.getLeatherDelivered()))) {
                task.setLeatherDelivered(true);
                task.setLeatherDeliveredAt(GuatemalaDateTime.now());
                tasks.save(task);
            }
        }
    }

    // Al anular una salida se retiran solo las banderas que dependían de ella; las registradas antes se conservan.
    @Transactional(rollbackFor = Exception.class)
    public void revokeAfterCancellation(LeatherMovementEntity cancelled) throws BusinessException {
        if (cancelled == null || cancelled.getProductionOrderId() == null) return;
        Long orderId = cancelled.getProductionOrderId();
        var previous = movements.findByProductionOrderIdOrderByCreatedAtDesc(orderId);
        Map<Long, Boolean> coverage = new HashMap<>();
        for (var task : tasks.findByProductionOrderId(orderId)) {
            if (!"PENDING".equals(task.getStatus())) continue;
            boolean changed = false;
            for (var item : items.findByTaskId(task.getId())) {
                if (!Boolean.TRUE.equals(item.getLeatherDelivered())) continue;
                boolean dependsOnCancelled = cancelled.getTaskItemId() != null
                        ? Objects.equals(cancelled.getTaskItemId(), item.getId())
                        : item.getLeatherDeliveredAt() != null && cancelled.getCreatedAt() != null
                        && !item.getLeatherDeliveredAt().isBefore(cancelled.getCreatedAt());
                if (!dependsOnCancelled || isCovered(orderId, item, previous, coverage)) continue;
                item.setLeatherDelivered(false);
                item.setLeatherDeliveredAt(null);
                items.save(item);
                changed = true;
            }
            if (changed) {
                task.setLeatherDelivered(false);
                task.setLeatherDeliveredAt(null);
                tasks.save(task);
            }
        }
    }

    // Una entrega por orden no puede repetir el cuero que ya salió por producto.
    public void assertManualDeliveryFits(Long orderId, Long materialId, BigDecimal quantity) throws BusinessException {
        if (orderId == null || materialId == null || quantity == null) return;
        var previous = movements.findByProductionOrderIdOrderByCreatedAtDesc(orderId);
        BigDecimal byProduct = deliveredQuantity(previous, materialId, true);
        if (byProduct.signum() <= 0) return;
        BigDecimal remaining = orderMaterialNeed(orderId, materialId).subtract(deliveredQuantity(previous, materialId, false)).max(BigDecimal.ZERO);
        if (quantity.compareTo(remaining) > 0) {
            throw new BusinessException("La orden ya recibió " + byProduct + " ft² de este cuero por producto; la entrega por orden admite hasta "
                    + remaining + " ft².");
        }
    }

    private boolean isCovered(Long orderId, TaskItemEntity item, List<LeatherMovementEntity> previous, Map<Long, Boolean> coverage)
            throws BusinessException {
        var product = item.getProductId() == null ? null : products.findById(item.getProductId()).orElse(null);
        if (product == null) return false;
        var need = requirements.resolveNeed(product, item.getColorId(), item.getQuantity() == null ? 0 : item.getQuantity());
        if (need.blocked()) return false;
        if (need.noneRequired() || previous.stream().anyMatch(m -> Objects.equals(m.getTaskItemId(), item.getId())
                && "SALIDA".equals(m.getMovementType()))) return true;
        Boolean covered = coverage.get(need.materialId());
        if (covered == null) {
            covered = orderMaterialCovered(orderId, need.materialId(), previous);
            coverage.put(need.materialId(), covered);
        }
        return covered;
    }

    private static BigDecimal deliveredQuantity(List<LeatherMovementEntity> previous, Long materialId, boolean onlyByProduct) {
        return previous.stream().filter(m -> "SALIDA".equals(m.getMovementType()) && Objects.equals(m.getMaterialId(), materialId)
                        && (!onlyByProduct || m.getTaskItemId() != null))
                .map(LeatherMovementEntity::getQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private boolean hasManualDelivery(Long orderId) {
        return orderId != null && movements.findByProductionOrderIdOrderByCreatedAtDesc(orderId).stream()
                .anyMatch(m -> m.getTaskItemId() == null && "SALIDA".equals(m.getMovementType()));
    }

    private boolean orderMaterialCovered(Long orderId, Long materialId, List<LeatherMovementEntity> previous)
            throws BusinessException {
        BigDecimal delivered = deliveredQuantity(previous, materialId, false);
        if (delivered.signum() <= 0) return false;
        BigDecimal total = orderMaterialNeed(orderId, materialId);
        return total.signum() > 0 && delivered.compareTo(total) >= 0;
    }

    private BigDecimal orderMaterialNeed(Long orderId, Long materialId) throws BusinessException {
        BigDecimal total = BigDecimal.ZERO;
        for (var line : orderItems.findByProductionOrderId(orderId)) {
            var product = products.findById(line.getProductId()).orElse(null);
            if (product == null) continue;
            int quantity = line.getQuantity() == null ? 0 : line.getQuantity();
            if (quantity == 0 && line.getSizesData() != null) {
                try {
                    for (var value : mapper.readTree(line.getSizesData())) quantity += value.asInt();
                } catch (Exception ex) { throw new BusinessException("Tallas inválidas en la receta de cuero de la orden."); }
            }
            var need = requirements.resolveNeed(product, line.getColorId(), quantity);
            if (!need.blocked() && Objects.equals(need.materialId(), materialId)) total = total.add(need.qtyFt2());
        }
        return total;
    }
}
