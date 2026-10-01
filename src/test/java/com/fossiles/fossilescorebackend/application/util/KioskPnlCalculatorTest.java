package com.fossiles.fossilescorebackend.application.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KioskPnlCalculatorTest {

    private static final KioskPnlCalculator.Rates RATES =
            KioskPnlCalculator.Rates.of("0.18", "0.04", "0.025", "0.025");

    @Test
    void calculatesMirafloresJanuary2025() {
        // Excel VENTAS ENERO 2025, columna MIRAFLORES.
        BigDecimal fixed = new BigDecimal("20934.42");
        KioskPnlCalculator.Result r = KioskPnlCalculator.calculate(new BigDecimal("74038.8"), RATES, fixed, 31);

        assertThat(KioskPnlCalculator.round2(r.productCost())).isEqualByComparingTo("13326.98");
        assertThat(KioskPnlCalculator.round2(r.salesCommission())).isEqualByComparingTo("2644.24");
        assertThat(KioskPnlCalculator.round2(r.cardCommission())).isEqualByComparingTo("1850.97");
        assertThat(KioskPnlCalculator.round2(r.tax())).isEqualByComparingTo("1850.97");
        assertThat(KioskPnlCalculator.round2(r.variableTotal())).isEqualByComparingTo("19673.17");
        assertThat(KioskPnlCalculator.round2(r.fixedTotal())).isEqualByComparingTo("20934.42");
        assertThat(KioskPnlCalculator.round2(r.totalCost())).isEqualByComparingTo("40607.59");
        assertThat(KioskPnlCalculator.round2(r.difference())).isEqualByComparingTo("33431.21");
        assertThat(KioskPnlCalculator.round4(r.margin())).isEqualByComparingTo("0.4515");
        // PE = CF / (1 - (0.04+0.18+0.025+0.025)) = 20934.42 / 0.73
        assertThat(KioskPnlCalculator.round2(r.breakEven())).isEqualByComparingTo("28677.29");
        assertThat(KioskPnlCalculator.round2(r.breakEvenDaily())).isEqualByComparingTo("925.07");
    }

    @Test
    void fixedCostsAreSummedFromCategories() {
        Map<String, BigDecimal> costs = new LinkedHashMap<>();
        costs.put("ALQUILER", new BigDecimal("14674.89"));
        costs.put("LUZ", BigDecimal.ZERO);
        costs.put("BONO_14", new BigDecimal("263.86"));
        costs.put("AGUINALDO", null);

        assertThat(KioskPnlCalculator.sumFixed(costs)).isEqualByComparingTo("14938.75");
        assertThat(KioskPnlCalculator.sumFixed(null)).isEqualByComparingTo("0");
    }

    @Test
    void marginIsNullWhenNoSales() {
        KioskPnlCalculator.Result r = KioskPnlCalculator.calculate(BigDecimal.ZERO, RATES, new BigDecimal("1000"), 30);

        assertThat(r.margin()).isNull();
        assertThat(r.difference()).isEqualByComparingTo("-1000");
        assertThat(r.totalCost()).isEqualByComparingTo("1000");
    }

    @Test
    void flatVariableRateOnlyChangesTheBreakEven() {
        // Kiosco sin comisión de venta: tasas reales 18 % + 2.87 % + 2.5 % = 23.37 %; el Excel reciente usa 27 % fijo.
        KioskPnlCalculator.Rates noCommission = KioskPnlCalculator.Rates.of("0.18", "0", "0.0287", "0.025");
        BigDecimal fixed = new BigDecimal("15183.78");
        KioskPnlCalculator.Result real = KioskPnlCalculator.calculate(new BigDecimal("24220.40"), noCommission, fixed, 30);
        KioskPnlCalculator.Result flat = KioskPnlCalculator.calculate(new BigDecimal("24220.40"), noCommission, fixed, 30,
                KioskPnlCalculator.FLAT_VARIABLE_RATE);

        assertThat(KioskPnlCalculator.round2(real.breakEven())).isEqualByComparingTo("19814.41"); // CF / 0.7663
        assertThat(KioskPnlCalculator.round2(flat.breakEven())).isEqualByComparingTo("20799.70"); // CF / 0.73
        assertThat(KioskPnlCalculator.round2(flat.breakEvenDaily())).isEqualByComparingTo("693.32");
        // costos, utilidad y margen no dependen del método
        assertThat(flat.variableTotal()).isEqualByComparingTo(real.variableTotal());
        assertThat(flat.difference()).isEqualByComparingTo(real.difference());
        assertThat(flat.margin()).isEqualByComparingTo(real.margin());
    }

    @Test
    void breakEvenIsNullWhenDenominatorNotPositive() {
        KioskPnlCalculator.Rates heavy = KioskPnlCalculator.Rates.of("0.50", "0.30", "0.10", "0.10");
        KioskPnlCalculator.Result r = KioskPnlCalculator.calculate(new BigDecimal("1000"), heavy, new BigDecimal("500"), 30);

        assertThat(r.breakEven()).isNull();
        assertThat(r.breakEvenDaily()).isNull();
    }

    @Test
    void nullRatesAreTreatedAsZero() {
        KioskPnlCalculator.Result r = KioskPnlCalculator.calculate(
                new BigDecimal("100"), new KioskPnlCalculator.Rates(null, null, null, null), new BigDecimal("10"), 30);

        assertThat(r.variableTotal()).isEqualByComparingTo("0");
        assertThat(r.difference()).isEqualByComparingTo("90");
        assertThat(r.breakEven()).isEqualByComparingTo("10");
    }

    @Test
    void breakEvenDailyUsesRealDaysOfMonth() {
        KioskPnlCalculator.Result feb = KioskPnlCalculator.calculate(new BigDecimal("1000"), RATES, new BigDecimal("7300"), 28);
        // PE = 7300 / 0.73 = 10000 -> 10000 / 28
        assertThat(KioskPnlCalculator.round2(feb.breakEven())).isEqualByComparingTo("10000.00");
        assertThat(KioskPnlCalculator.round2(feb.breakEvenDaily())).isEqualByComparingTo("357.14");
    }

    @Test
    void ratioAndRounding() {
        assertThat(KioskPnlCalculator.ratio(new BigDecimal("50"), BigDecimal.ZERO)).isNull();
        assertThat(KioskPnlCalculator.ratio(null, BigDecimal.ONE)).isNull();
        assertThat(KioskPnlCalculator.round4(KioskPnlCalculator.ratio(new BigDecimal("1"), new BigDecimal("3"))))
                .isEqualByComparingTo("0.3333");
        assertThat(KioskPnlCalculator.round2(new BigDecimal("2.345"))).isEqualByComparingTo("2.35");
        assertThat(KioskPnlCalculator.round2(null)).isNull();
    }

    @Test
    void completenessRequiresGoalRatesAndAllCategories() {
        List<String> required = List.of("ALQUILER", "LUZ");
        Map<String, BigDecimal> full = new LinkedHashMap<>();
        full.put("ALQUILER", new BigDecimal("100"));
        full.put("LUZ", BigDecimal.ZERO); // 0 cuenta como valor

        assertThat(KioskPnlCalculator.isMonthComplete(new BigDecimal("1"), RATES, full, required)).isTrue();
        assertThat(KioskPnlCalculator.isMonthComplete(null, RATES, full, required)).isFalse();
        assertThat(KioskPnlCalculator.isMonthComplete(new BigDecimal("1"),
                new KioskPnlCalculator.Rates(new BigDecimal("0.18"), null, BigDecimal.ONE, BigDecimal.ONE), full, required)).isFalse();
        Map<String, BigDecimal> missing = new LinkedHashMap<>();
        missing.put("ALQUILER", new BigDecimal("100"));
        assertThat(KioskPnlCalculator.isMonthComplete(new BigDecimal("1"), RATES, missing, required)).isFalse();
        assertThat(KioskPnlCalculator.isMonthComplete(new BigDecimal("1"), RATES, null, required)).isFalse();
        assertThat(KioskPnlCalculator.isMonthComplete(new BigDecimal("1"), null, full, required)).isFalse();
    }

    // ------------------------------------------------------------------ comision de venta: regla por anio

    private static BigDecimal commission(String sales, String goal, KioskPnlCalculator.CommissionPolicy policy) {
        KioskPnlCalculator.Result r = KioskPnlCalculator.calculate(new BigDecimal(sales), RATES,
                new LinkedHashMap<String, BigDecimal>(), 30, null, policy, goal == null ? null : new BigDecimal(goal));
        return KioskPnlCalculator.round2(r.salesCommission());
    }

    @Test
    void policyIsChosenByYear() {
        assertThat(KioskPnlCalculator.CommissionPolicy.forYear(2025)).isSameAs(KioskPnlCalculator.CommissionPolicy.LEGACY);
        assertThat(KioskPnlCalculator.CommissionPolicy.forYear(2026)).isSameAs(KioskPnlCalculator.CommissionPolicy.FROM_2026);
        assertThat(KioskPnlCalculator.CommissionPolicy.forYear(2027)).isSameAs(KioskPnlCalculator.CommissionPolicy.FROM_2026);
    }

    @Test
    void from2026AppliesRateOverGrossSalesWhenGoalReachedAtLeast70Percent() {
        // 100,000 / 130,000 = 76.9 % -> comision = ventas x 4 % (SIN dividir entre 1.12)
        assertThat(commission("100000", "130000", KioskPnlCalculator.CommissionPolicy.FROM_2026))
                .isEqualByComparingTo("4000.00");
    }

    @Test
    void from2026ChargesNoCommissionBelow70Percent() {
        // 90,000 / 130,000 = 69.2 % -> 0
        assertThat(commission("90000", "130000", KioskPnlCalculator.CommissionPolicy.FROM_2026))
                .isEqualByComparingTo("0.00");
    }

    @Test
    void from2026Exactly70PercentStillCharges() {
        // 91,000 / 130,000 = exactamente 70 % -> aplica (>= 0.7)
        assertThat(commission("91000", "130000", KioskPnlCalculator.CommissionPolicy.FROM_2026))
                .isEqualByComparingTo("3640.00");
        assertThat(commission("90999.99", "130000", KioskPnlCalculator.CommissionPolicy.FROM_2026))
                .isEqualByComparingTo("0.00");
    }

    @Test
    void from2026WithoutGoalCannotVerify70PercentSoItCharges() {
        assertThat(commission("100000", null, KioskPnlCalculator.CommissionPolicy.FROM_2026))
                .isEqualByComparingTo("4000.00");
        assertThat(commission("100000", "0", KioskPnlCalculator.CommissionPolicy.FROM_2026))
                .isEqualByComparingTo("4000.00");
    }

    @Test
    void legacyKeepsNetOfIvaAndNoConditionEvenBelow70Percent() {
        // Excel 2025: (ventas / 1.12) x 4 %, siempre
        assertThat(commission("90000", "130000", KioskPnlCalculator.CommissionPolicy.LEGACY))
                .isEqualByComparingTo("3214.29");
    }

    @Test
    void totalCostExcludesCommissionWhenBelow70PercentIn2026() {
        KioskPnlCalculator.Result below = KioskPnlCalculator.calculate(new BigDecimal("90000"), RATES,
                new BigDecimal("10000"), 30, null, KioskPnlCalculator.CommissionPolicy.FROM_2026, new BigDecimal("130000"));
        KioskPnlCalculator.Result above = KioskPnlCalculator.calculate(new BigDecimal("100000"), RATES,
                new BigDecimal("10000"), 30, null, KioskPnlCalculator.CommissionPolicy.FROM_2026, new BigDecimal("130000"));
        // variable = costo 18 % + tarjeta 2.5 % + IVA 2.5 % (+ comision 4 % solo arriba del 70 %)
        assertThat(KioskPnlCalculator.round2(below.variableTotal())).isEqualByComparingTo("20700.00"); // 90000 x 23 %
        assertThat(KioskPnlCalculator.round2(above.variableTotal())).isEqualByComparingTo("27000.00"); // 100000 x 27 %
    }
}
