package com.fossiles.fossilescorebackend.infrastructure.util;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionOrderPlanPriorityTest {

    @Test
    void oplBeforeOpckBeforeRest() {
        ProductionOrderEntity opl = ProductionOrderEntity.builder()
                .id(3L)
                .code("OPL-1")
                .orderType("VENTA_EN_LINEA")
                .createdAt(LocalDateTime.of(2026, 8, 19, 10, 0))
                .build();
        ProductionOrderPlanPriority.applyDefault(opl, "VENTA_EN_LINEA");

        ProductionOrderEntity opck = ProductionOrderEntity.builder()
                .id(2L)
                .code("OPCK-1")
                .orderType("CLIENTE_KIOSKO")
                .createdAt(LocalDateTime.of(2026, 8, 19, 9, 0))
                .build();
        ProductionOrderPlanPriority.applyDefault(opck, "CLIENTE_KIOSKO");

        ProductionOrderEntity opk = ProductionOrderEntity.builder()
                .id(1L)
                .code("OPK-1")
                .orderType("NORMAL")
                .createdAt(LocalDateTime.of(2026, 8, 19, 8, 0))
                .build();
        ProductionOrderPlanPriority.applyDefault(opk, "NORMAL");

        List<ProductionOrderEntity> sorted = List.of(opk, opck, opl).stream()
                .sorted(ProductionOrderPlanPriority.comparator())
                .toList();

        assertThat(sorted).extracting(ProductionOrderEntity::getCode)
                .containsExactly("OPL-1", "OPCK-1", "OPK-1");
        assertThat(opl.getSchedulingPriority()).isEqualTo(0);
        assertThat(opck.getSchedulingPriority()).isEqualTo(1);
        assertThat(opk.getSchedulingPriority()).isEqualTo(100);
    }
}
