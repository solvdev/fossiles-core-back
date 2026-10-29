package com.fossiles.fossilescorebackend.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Edita los ítems de una orden por diferencias y conserva sus ids. Un ítem con tareas, recepción en bodega,
 * liberaciones, estado de cincho o venta en línea conserva variante, cantidad y tallas y no se quita;
 * su precio sí cambia.
 */
@Service
@RequiredArgsConstructor
public class ProductionOrderItemUpdateService {
    private final ProductionOrderItemRepository items;
    private final TaskItemRepository taskItems;
    private final TaskRepository tasks;
    private final ProductionOrderWarehouseUnitRepository warehouseUnits;
    private final ProductionOrderPartialReleaseLineRepository releaseLines;
    private final ProductionCinchoDayStatusRepository cinchoStatuses;
    private final ObjectMapper mapper;

    // El plan se valida completo antes de escribir; un ítem usado conserva su identidad y cantidades.
    public UpdatePlan prepare(Long orderId, List<ProductionOrderItemEntity> proposed) throws BusinessException {
        List<ProductionOrderItemEntity> existing = items.findByProductionOrderId(orderId);
        Map<Long, ProductionOrderItemEntity> byId = new HashMap<>();
        existing.forEach(item -> byId.put(item.getId(), item));
        Set<Long> retained = new HashSet<>();
        List<ProductionOrderItemEntity> updates = new ArrayList<>();
        for (var incoming : proposed) {
            var original = incoming.getId() == null ? null : byId.get(incoming.getId());
            if (incoming.getId() != null && original == null) {
                throw new BusinessException("El ítem " + incoming.getId() + " no pertenece a esta orden.");
            }
            if (incoming.getId() == null) {
                var matches = existing.stream().filter(item -> !retained.contains(item.getId()))
                        .filter(item -> sameVariant(item, incoming)).toList();
                if (matches.size() > 1) {
                    throw new BusinessException("Indique el identificador del ítem para editar variantes repetidas.");
                }
                if (matches.size() == 1) original = matches.getFirst();
            }
            if (original == null) {
                incoming.setProductionOrderId(orderId);
                incoming.setWarehouseReceivedQty(0);
                updates.add(incoming);
                continue;
            }
            if (!retained.add(original.getId())) {
                throw new BusinessException("El ítem " + original.getId() + " está repetido en la solicitud.");
            }
            boolean used = isUsed(original);
            boolean sameQuantity = Objects.equals(original.getQuantity(), incoming.getQuantity())
                    || ((original.getQuantity() == null || original.getQuantity() == 0)
                    && (incoming.getQuantity() == null || incoming.getQuantity() == 0));
            if (used && (!sameVariant(original, incoming)
                    || !sameQuantity
                    || !sameSizes(original.getSizesData(), incoming.getSizesData()))) {
                throw new BusinessException("El ítem " + original.getId()
                        + " ya tiene producción o recepción; solamente puede modificar precios u observaciones.");
            }
            var update = new ProductionOrderItemEntity();
            BeanUtils.copyProperties(original, update);
            update.setProductId(incoming.getProductId());
            update.setColorId(incoming.getColorId());
            update.setBrandName(incoming.getBrandName());
            update.setQuantity(used ? original.getQuantity() : incoming.getQuantity());
            update.setSizesData(incoming.getSizesData());
            update.setObservations(incoming.getObservations());
            // Los precios omitidos por un formulario se conservan; cero sigue siendo un valor explícito.
            if (incoming.getUnitPrice() != null) update.setUnitPrice(incoming.getUnitPrice());
            if (incoming.getUnitPricesJson() != null) update.setUnitPricesJson(incoming.getUnitPricesJson());
            updates.add(update);
        }
        List<ProductionOrderItemEntity> removed = existing.stream()
                .filter(item -> !retained.contains(item.getId())).toList();
        for (var item : removed) {
            if (isUsed(item)) throw new BusinessException("No se puede eliminar el ítem " + item.getId()
                    + " porque tiene producción o recepción asociada.");
        }
        return new UpdatePlan(updates, removed);
    }

    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.MANDATORY, rollbackFor = Exception.class)
    public void apply(UpdatePlan plan) {
        items.saveAll(plan.updates());
        items.deleteAll(plan.removed());
    }

    private boolean isUsed(ProductionOrderItemEntity item) {
        Long id = item.getId();
        return (item.getWarehouseReceivedQty() != null && item.getWarehouseReceivedQty() > 0)
                || item.getOnlineSaleItemId() != null
                || taskItems.existsByProductionOrderItemId(id) || tasks.existsByProductionOrderItemId(id)
                || warehouseUnits.existsByProductionOrderItemId(id)
                || releaseLines.existsByProductionOrderItemId(id) || cinchoStatuses.existsByProductionOrderItemId(id);
    }

    public boolean hasUsedItems(Long orderId) {
        return items.findByProductionOrderId(orderId).stream().anyMatch(this::isUsed);
    }

    private static boolean sameVariant(ProductionOrderItemEntity left, ProductionOrderItemEntity right) {
        return Objects.equals(left.getProductId(), right.getProductId())
                && Objects.equals(left.getColorId(), right.getColorId())
                && Objects.equals(left.getBrandName(), right.getBrandName());
    }

    private boolean sameSizes(String left, String right) throws BusinessException {
        try {
            return Objects.equals(mapper.readTree(left == null ? "{}" : left),
                    mapper.readTree(right == null ? "{}" : right));
        } catch (Exception ex) {
            throw new BusinessException("Las tallas del ítem no tienen un formato válido.");
        }
    }

    public record UpdatePlan(List<ProductionOrderItemEntity> updates, List<ProductionOrderItemEntity> removed) {}
}
