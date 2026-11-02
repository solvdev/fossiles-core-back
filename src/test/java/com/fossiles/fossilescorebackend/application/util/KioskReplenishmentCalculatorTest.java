package com.fossiles.fossilescorebackend.application.util;

import com.fossiles.fossilescorebackend.application.util.KioskReplenishmentCalculator.AllocationRequest;
import com.fossiles.fossilescorebackend.application.util.KioskReplenishmentCalculator.Category;
import com.fossiles.fossilescorebackend.application.util.KioskReplenishmentCalculator.LineInput;
import com.fossiles.fossilescorebackend.application.util.KioskReplenishmentCalculator.LinePlan;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KioskReplenishmentCalculatorTest {

    private static LineInput line(int stock, int inTransit, int sold, boolean top, boolean base) {
        return new LineInput(stock, inTransit, BigDecimal.valueOf(sold), top, base);
    }

    @Test
    void categoryARefillsToMaxEvenWithStockAndCountsInTransit() {
        // 30 vendidos en 60 días = 0.5/día: máximo 21 días = 10.5 -> tope A 8; mínimo 7 días = 4.
        LinePlan p = KioskReplenishmentCalculator.plan(Category.A, 60, line(5, 1, 30, false, false));

        assertThat(p.max()).isEqualTo(8);
        assertThat(p.min()).isEqualTo(4);
        assertThat(p.need()).isEqualTo(2);
        assertThat(p.reason()).isEqualTo("Rellenar al máximo (A)");
    }

    @Test
    void categoryASingleSaleReplacesOneUnit() {
        // 1 venta en 60 días: mínimo y máximo 1; si ya no tiene, se repone esa unidad.
        LinePlan p = KioskReplenishmentCalculator.plan(Category.A, 60, line(0, 0, 1, false, false));

        assertThat(p.min()).isEqualTo(1);
        assertThat(p.max()).isEqualTo(1);
        assertThat(p.need()).isEqualTo(1);
        assertThat(p.reason()).isEqualTo("Debajo del mínimo");
        assertThat(KioskReplenishmentCalculator.plan(Category.A, 60, line(1, 0, 1, false, false)).need()).isZero();
    }

    @Test
    void categoryBOnlyRefillsBelowMinimumUnlessTopSeller() {
        // 12 en 60 días = 0.2/día: máximo ceil(2.8) = 3, mínimo ceil(1.4) = 2.
        assertThat(KioskReplenishmentCalculator.plan(Category.B, 60, line(2, 0, 12, false, false)).need())
                .isZero();

        LinePlan top = KioskReplenishmentCalculator.plan(Category.B, 60, line(2, 0, 12, true, false));
        assertThat(top.need()).isEqualTo(1);
        assertThat(top.reason()).isEqualTo("Alta venta");

        LinePlan base = KioskReplenishmentCalculator.plan(Category.B, 60, line(2, 0, 12, false, true));
        assertThat(base.need()).isEqualTo(1);
        assertThat(base.reason()).isEqualTo("Artículo base");

        LinePlan below = KioskReplenishmentCalculator.plan(Category.B, 60, line(1, 0, 12, false, false));
        assertThat(below.need()).isEqualTo(2);
        assertThat(below.reason()).isEqualTo("Debajo del mínimo");
    }

    @Test
    void categoryCOnlyReachesTheMinimumAndIsCapped() {
        assertThat(KioskReplenishmentCalculator.plan(Category.C, 60, line(1, 0, 12, true, true)).need())
                .isEqualTo(1);
        assertThat(KioskReplenishmentCalculator.plan(Category.C, 60, line(3, 0, 12, true, true)).need())
                .isZero();
        // 1/día pediría 7, pero C no pasa de 2 unidades por línea.
        assertThat(KioskReplenishmentCalculator.plan(Category.C, 60, line(0, 0, 60, false, false)).need())
                .isEqualTo(2);
    }

    @Test
    void negativeSalesAreTreatedAsZero() {
        LinePlan p = KioskReplenishmentCalculator.plan(Category.B, 60,
                new LineInput(0, 0, BigDecimal.valueOf(-3), false, false));

        assertThat(p.dailyRate()).isEqualByComparingTo("0");
        assertThat(p.need()).isEqualTo(1);
    }

    @Test
    void topSellerThresholdIsTheEightiethPercentileOfLinesWithSales() {
        BigDecimal threshold = KioskReplenishmentCalculator.topSellerThreshold(List.of(
                new BigDecimal("0.5"), new BigDecimal("0.1"), BigDecimal.ZERO,
                new BigDecimal("0.3"), new BigDecimal("0.2"), new BigDecimal("0.4")));

        assertThat(threshold).isEqualByComparingTo("0.4");
        assertThat(KioskReplenishmentCalculator.topSellerThreshold(List.of(BigDecimal.ZERO))).isNull();
    }

    @Test
    void ranksKiosksWithoutCategoryInThirds() {
        Map<Long, BigDecimal> units = new LinkedHashMap<>();
        units.put(1L, BigDecimal.valueOf(10));
        units.put(2L, BigDecimal.valueOf(60));
        units.put(3L, BigDecimal.valueOf(30));
        units.put(4L, BigDecimal.valueOf(50));
        units.put(5L, BigDecimal.valueOf(20));
        units.put(6L, BigDecimal.valueOf(40));

        Map<Long, Category> ranked = KioskReplenishmentCalculator.rankCategories(units);

        assertThat(ranked.get(2L)).isEqualTo(Category.A);
        assertThat(ranked.get(4L)).isEqualTo(Category.A);
        assertThat(ranked.get(6L)).isEqualTo(Category.B);
        assertThat(ranked.get(3L)).isEqualTo(Category.B);
        assertThat(ranked.get(5L)).isEqualTo(Category.C);
        assertThat(ranked.get(1L)).isEqualTo(Category.C);
    }

    @Test
    void allocatesScarceStockByCategoryThenBaseThenSales() {
        List<AllocationRequest> requests = List.of(
                new AllocationRequest("k", Category.C, false, new BigDecimal("2.0"), 2),
                new AllocationRequest("k", Category.B, false, new BigDecimal("0.5"), 2),
                new AllocationRequest("k", Category.A, false, new BigDecimal("0.1"), 2),
                new AllocationRequest("other", Category.C, false, BigDecimal.ONE, 3));

        int[] allocated = KioskReplenishmentCalculator.allocate(requests, Map.of("k", 3, "other", 10));

        assertThat(allocated).containsExactly(0, 1, 2, 3);
    }

    @Test
    void baseLinesGoFirstWithinTheSameCategory() {
        List<AllocationRequest> requests = List.of(
                new AllocationRequest("k", Category.B, false, new BigDecimal("3"), 1),
                new AllocationRequest("k", Category.B, true, new BigDecimal("0.1"), 1));

        int[] allocated = KioskReplenishmentCalculator.allocate(requests, Map.of("k", 1));

        assertThat(allocated).containsExactly(0, 1);
    }
}
