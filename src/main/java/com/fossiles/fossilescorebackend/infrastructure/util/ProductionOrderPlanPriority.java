package com.fossiles.fossilescorebackend.infrastructure.util;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;

import java.util.Comparator;
import java.util.Locale;
import java.util.Map;

/**
 * Cola de auto-plan: OPL (0) → OPCK (1) → resto FIFO.
 */
public final class ProductionOrderPlanPriority {

    public static final int OPL = 0;
    public static final int OPCK = 1;
    public static final int DEFAULT = 100;

    private ProductionOrderPlanPriority() {
    }

    public static int resolve(String orderType, String code) {
        if (ProductionPlanningConstants.isOnlineSaleOrder(orderType, code)) {
            return OPL;
        }
        String t = orderType == null ? "" : orderType.trim().toUpperCase(Locale.ROOT);
        if ("CLIENTE_KIOSKO".equals(t)) {
            return OPCK;
        }
        return DEFAULT;
    }

    public static void applyDefault(ProductionOrderEntity entity, String orderType) {
        if (entity == null) {
            return;
        }
        if (entity.getSchedulingPriority() != null) {
            return;
        }
        entity.setSchedulingPriority(resolve(orderType, entity.getCode()));
    }

    /** Prioridad con la que se ordena la OP: la propuesta, la guardada o la de su tipo. */
    public static int effective(ProductionOrderEntity po, Map<Long, Integer> overrides) {
        Integer proposed = overrides != null && po.getId() != null ? overrides.get(po.getId()) : null;
        if (proposed != null) {
            return proposed;
        }
        return po.getSchedulingPriority() != null
                ? po.getSchedulingPriority()
                : resolve(po.getOrderType(), po.getCode());
    }

    public static Comparator<ProductionOrderEntity> comparator() {
        return comparator(Map.of());
    }

    /**
     * Igual que {@link #comparator()} pero con prioridades manuales aún sin guardar
     * (id de OP → prioridad), que mandan sobre la guardada. No toca las entidades: la vista
     * previa de «Armar el día» ordena con lo que el jefe propone sin persistirlo.
     */
    public static Comparator<ProductionOrderEntity> comparator(Map<Long, Integer> overrides) {
        return Comparator
                .comparingInt((ProductionOrderEntity po) -> effective(po, overrides))
                .thenComparing(ProductionOrderEntity::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ProductionOrderEntity::getId, Comparator.nullsLast(Comparator.naturalOrder()));
    }
}
