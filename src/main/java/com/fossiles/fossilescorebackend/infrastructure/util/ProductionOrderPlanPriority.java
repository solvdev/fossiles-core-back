package com.fossiles.fossilescorebackend.infrastructure.util;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;

import java.util.Comparator;
import java.util.Locale;

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

    public static Comparator<ProductionOrderEntity> comparator() {
        return Comparator
                .comparingInt((ProductionOrderEntity po) -> po.getSchedulingPriority() != null
                        ? po.getSchedulingPriority()
                        : resolve(po.getOrderType(), po.getCode()))
                .thenComparing(ProductionOrderEntity::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ProductionOrderEntity::getId, Comparator.nullsLast(Comparator.naturalOrder()));
    }
}
