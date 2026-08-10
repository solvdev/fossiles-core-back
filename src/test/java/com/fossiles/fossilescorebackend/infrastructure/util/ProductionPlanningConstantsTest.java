package com.fossiles.fossilescorebackend.infrastructure.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Cupo OPL / prioridad de distribución (Agente A).
 */
class ProductionPlanningConstantsTest {

    @Test
    void isOnlineSaleOrder_byTypeAndCode() {
        assertThat(ProductionPlanningConstants.isOnlineSaleOrder("VENTA_EN_LINEA", "OPL-12")).isTrue();
        assertThat(ProductionPlanningConstants.isOnlineSaleOrder("venta_en_linea", null)).isTrue();
        assertThat(ProductionPlanningConstants.isOnlineSaleOrder(null, "OPL-99")).isTrue();
        assertThat(ProductionPlanningConstants.isOnlineSaleOrder(null, "OPL")).isTrue();
        assertThat(ProductionPlanningConstants.isOnlineSaleOrder("NORMAL", "OPK-1")).isFalse();
        assertThat(ProductionPlanningConstants.isOnlineSaleOrder("CLIENTE_KIOSKO", "OPCK-1")).isFalse();
        assertThat(ProductionPlanningConstants.isOnlineSaleOrder(null, "OPV-3")).isFalse();
    }

    @Test
    void deskCupoBaseHours_oplTaskAlwaysZero() {
        assertThat(ProductionPlanningConstants.deskCupoBaseHours(3.5, "OPL-10", 0.0))
                .isCloseTo(0.0, within(1e-9));
        assertThat(ProductionPlanningConstants.deskCupoBaseHours(5.0, "OPL-10", 5.0))
                .isCloseTo(0.0, within(1e-9));
    }

    @Test
    void deskCupoBaseHours_mixedOpPlusOplExtras_onlyOpCounts() {
        // OP base 3.2h + OPL extras 2.5h (daySaleExtra) → cupo = 3.2
        double opHours = 3.2;
        double oplExtraHours = 2.5;
        double totalEstimated = opHours + oplExtraHours;
        double base = ProductionPlanningConstants.deskCupoBaseHours(totalEstimated, "OPK-44", oplExtraHours);
        assertThat(base).isCloseTo(opHours, within(1e-9));
        assertThat(base).isLessThanOrEqualTo(ProductionPlanningConstants.MAX_HOURS_PER_DESK_PER_DAY);
    }

    @Test
    void deskCupoBaseHours_opNearHardCap_oplDoesNotBlock() {
        // OP at 4.8h (under 5h hard cap) + large OPL block must not push cupo over the cap.
        double opHours = 4.8;
        double oplExtraHours = 6.0;
        double base = ProductionPlanningConstants.deskCupoBaseHours(opHours + oplExtraHours, "OPV-7", oplExtraHours);
        assertThat(base).isCloseTo(opHours, within(1e-9));
        assertThat(base).isLessThanOrEqualTo(ProductionPlanningConstants.MAX_HOURS_PER_TASK_HARD_CAP);
    }

    @Test
    void distributionFamilyLabel_includesOpl() {
        assertThat(ProductionPlanningConstants.distributionFamilyLabel("VENTA_EN_LINEA", "OPL-1"))
                .isEqualTo("OPL");
        assertThat(ProductionPlanningConstants.distributionFamilyLabel(null, "OPL-22"))
                .isEqualTo("OPL");
        assertThat(ProductionPlanningConstants.distributionFamilyLabel("NORMAL", "OPK-1"))
                .isEqualTo("OPK");
        assertThat(ProductionPlanningConstants.distributionFamilyLabel("DISTRIBUTION", "OPD-1"))
                .isNull();
    }
}
