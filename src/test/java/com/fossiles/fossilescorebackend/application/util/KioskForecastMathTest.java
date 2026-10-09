package com.fossiles.fossilescorebackend.application.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class KioskForecastMathTest {

    /** Kiosco que vende 100 de lunes a sábado y 0 los domingos, durante todo el rango. */
    private static Map<LocalDate, BigDecimal> weeklyPattern(LocalDate from, LocalDate to) {
        Map<LocalDate, BigDecimal> m = new TreeMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            m.put(d, d.getDayOfWeek().getValue() == 7 ? BigDecimal.ZERO : new BigDecimal("100"));
        }
        return m;
    }

    @Test
    void monthEndUsesTheWeekdayPatternAndDoesNotChargeSundays() {
        // septiembre 2026: 1-sep es martes; hoy = 16-sep (miércoles). Un patrón perfecto => sin incertidumbre.
        LocalDate today = LocalDate.of(2026, 9, 16);
        Map<LocalDate, BigDecimal> daily = weeklyPattern(LocalDate.of(2026, 6, 1), today.minusDays(1));

        KioskForecastMath.MonthEnd r = KioskForecastMath.monthEnd(daily, today, null);

        assertThat(r.method()).isEqualTo(KioskForecastMath.METHOD_WEEKDAY);
        // días 1..15 (completos): 15 días con 2 domingos (6 y 13) => 13 x 100
        assertThat(r.mtd()).isCloseTo(1300.0, within(0.001));
        assertThat(r.daysElapsed()).isEqualTo(15);
        assertThat(r.daysRemaining()).isEqualTo(15); // 16..30
        // 16..30: 15 días con 2 domingos (20 y 27) => 13 x 100
        assertThat(r.projected()).isCloseTo(2600.0, within(0.001));
        assertThat(r.low()).isCloseTo(2600.0, within(0.001));
        assertThat(r.high()).isCloseTo(2600.0, within(0.001));
    }

    @Test
    void todayCountsAtLeastWhatWasAlreadySold() {
        LocalDate today = LocalDate.of(2026, 9, 16);
        Map<LocalDate, BigDecimal> daily = weeklyPattern(LocalDate.of(2026, 6, 1), today.minusDays(1));
        daily.put(today, new BigDecimal("500")); // hoy ya lleva más de lo esperado (100)

        KioskForecastMath.MonthEnd r = KioskForecastMath.monthEnd(daily, today, null);

        assertThat(r.actualToDate()).isCloseTo(1800.0, within(0.001));
        assertThat(r.projected()).isCloseTo(2600.0 + 400.0, within(0.001));
    }

    @Test
    void rangeWidensWithNoisyDataAndNeverDropsBelowWhatIsAlreadySold() {
        LocalDate today = LocalDate.of(2026, 9, 10);
        Map<LocalDate, BigDecimal> daily = new TreeMap<>();
        for (LocalDate d = LocalDate.of(2026, 7, 1); d.isBefore(today); d = d.plusDays(1)) {
            daily.put(d, new BigDecimal(d.getDayOfMonth() % 2 == 0 ? "50" : "150")); // ruido
        }

        KioskForecastMath.MonthEnd r = KioskForecastMath.monthEnd(daily, today, null);

        assertThat(r.high()).isGreaterThan(r.projected());
        assertThat(r.low()).isLessThan(r.projected());
        assertThat(r.low()).isGreaterThanOrEqualTo(r.actualToDate());
    }

    @Test
    void newKioskWithoutEnoughHistoryUsesTheRunRateOfItsOperatedDays() {
        LocalDate today = LocalDate.of(2026, 9, 11);
        LocalDate goLive = LocalDate.of(2026, 9, 4); // 7 días operados (4..10)
        Map<LocalDate, BigDecimal> daily = new TreeMap<>();
        for (LocalDate d = goLive; d.isBefore(today); d = d.plusDays(1)) {
            daily.put(d, new BigDecimal("200"));
        }

        KioskForecastMath.MonthEnd r = KioskForecastMath.monthEnd(daily, today, goLive);

        assertThat(r.method()).isEqualTo(KioskForecastMath.METHOD_RUN_RATE);
        assertThat(r.mtd()).isCloseTo(1400.0, within(0.001));
        // hoy + 19 días restantes (11..30) = 20 días x 200
        assertThat(r.projected()).isCloseTo(1400.0 + 20 * 200.0, within(0.001));
    }

    @Test
    void tooFewOperatedDaysIsInsufficientAndGivesNoProjection() {
        LocalDate today = LocalDate.of(2026, 9, 6);
        LocalDate goLive = LocalDate.of(2026, 9, 5);
        Map<LocalDate, BigDecimal> daily = Map.of(LocalDate.of(2026, 9, 5), new BigDecimal("300"));

        KioskForecastMath.MonthEnd r = KioskForecastMath.monthEnd(daily, today, goLive);

        assertThat(r.method()).isEqualTo(KioskForecastMath.METHOD_INSUFFICIENT);
        assertThat(r.projected()).isNull();
        assertThat(r.mtd()).isCloseTo(300.0, within(0.001));
    }

    @Test
    void siteGrowthNeedsComparableHistoryAndIsCapped() {
        assertThat(KioskForecastMath.siteGrowth(1100, 1000, 200, 150).factor()).isCloseTo(1.1, within(1e-9));
        assertThat(KioskForecastMath.siteGrowth(1100, 0, 200, 150)).isNull();          // sin base
        assertThat(KioskForecastMath.siteGrowth(1100, 1000, 60, 150)).isNull();        // pocos días comparables
        assertThat(KioskForecastMath.siteGrowth(1100, 1000, 200, 30)).isNull();        // pocos días con venta en la base
        KioskForecastMath.Growth high = KioskForecastMath.siteGrowth(5000, 1000, 200, 150);
        assertThat(high.factor()).isEqualTo(2.0);
        assertThat(high.capped()).isTrue();
        assertThat(KioskForecastMath.clamp(0.1).factor()).isEqualTo(0.5);
    }

    @Test
    void seasonalIndexUsesOnlySitesWithTwelveMonthsAndAveragesToOne() {
        Double[] mature = new Double[12];
        Arrays.fill(mature, 100.0);
        mature[11] = 200.0; // diciembre fuerte
        Double[] partial = new Double[12];
        partial[11] = 9999.0; // kiosco nuevo: no cuenta
        double[] idx = KioskForecastMath.seasonalIndex(List.of(mature, partial));

        assertThat(Arrays.stream(idx).average().orElse(0)).isCloseTo(1.0, within(1e-9));
        assertThat(idx[11] / idx[0]).isCloseTo(2.0, within(1e-9));

        double[] none = KioskForecastMath.seasonalIndex(List.<Double[]>of(partial));
        assertThat(none).containsOnly(1.0);
    }

    @Test
    void nextYearIsSameMonthOfThisYearTimesGrowthAndFutureMonthsChainFromLastYear() {
        double[] flat = new double[12];
        Arrays.fill(flat, 1.0);
        Double[] previous = new Double[12];
        Double[] current = new Double[12];
        Arrays.fill(previous, 1000.0);
        // año en curso: enero-agosto reales (1100), septiembre = cierre proyectado (1150), resto sin dato
        for (int m = 0; m < 8; m++) {
            current[m] = 1100.0;
        }
        current[8] = 1150.0;

        KioskForecastMath.YearProjection p = KioskForecastMath.projectNextYear(previous, current, 9, 1.1, flat);

        assertThat(p.baseYear()[0]).isEqualTo(1100.0);                     // mes cerrado: real
        assertThat(p.baseYear()[8]).isEqualTo(1150.0);                     // mes en curso: cierre proyectado
        assertThat(p.baseYear()[9]).isCloseTo(1000.0 * 1.1, within(1e-9)); // oct: año anterior x crecimiento
        assertThat(p.target()[0]).isCloseTo(1100.0 * 1.1, within(1e-9));   // año siguiente = año actual x crecimiento
        assertThat(p.target()[9]).isCloseTo(1000.0 * 1.1 * 1.1, within(1e-9));
        assertThat(p.estimated()).containsOnly(false);
    }

    @Test
    void monthsWithoutHistoryUseTheSiteLevelTimesTheSeasonalIndex() {
        // kiosco abierto en julio: sin año anterior. Índice: diciembre vale el doble que el resto.
        double[] idx = new double[12];
        Arrays.fill(idx, 0.9);
        idx[11] = 1.9;
        Double[] previous = new Double[12];
        Double[] current = new Double[12];
        current[6] = 900.0; // jul
        current[7] = 900.0; // ago
        current[8] = 900.0; // sep (proyectado)

        KioskForecastMath.YearProjection p = KioskForecastMath.projectNextYear(previous, current, 9, 1.0, idx);

        // nivel = 900 / 0.9 = 1000; enero (sin dato) = 1000 x 0.9; diciembre = 1000 x 1.9
        assertThat(p.baseYear()[0]).isCloseTo(900.0, within(1e-9));
        assertThat(p.baseYear()[11]).isCloseTo(1900.0, within(1e-9));
        assertThat(p.estimated()[0]).isTrue();
        assertThat(p.estimated()[6]).isFalse();
    }

    @Test
    void notEnoughMonthsToEstimateLevelLeavesGapsEmpty() {
        double[] flat = new double[12];
        Arrays.fill(flat, 1.0);
        Double[] current = new Double[12];
        current[8] = 500.0; // un solo mes de datos
        KioskForecastMath.YearProjection p = KioskForecastMath.projectNextYear(new Double[12], current, 9, 1.0, flat);

        assertThat(p.baseYear()[0]).isNull();
        assertThat(p.target()[0]).isNull();
        assertThat(p.target()[8]).isEqualTo(500.0);
    }
}
