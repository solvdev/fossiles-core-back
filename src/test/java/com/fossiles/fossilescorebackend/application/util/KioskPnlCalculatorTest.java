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
        // Tasas reales 18 % + 4 % (comisión de venta, fija) + 2.87 % + 2.5 % = 27.37 %; el Excel reciente usa 27 % fijo.
        // (el segundo argumento, la comisión de venta, se ignora: siempre es 4 %)
        KioskPnlCalculator.Rates noCommission = KioskPnlCalculator.Rates.of("0.18", "0", "0.0287", "0.025");
        BigDecimal fixed = new BigDecimal("15183.78");
        KioskPnlCalculator.Result real = KioskPnlCalculator.calculate(new BigDecimal("24220.40"), noCommission, fixed, 30);
        KioskPnlCalculator.Result flat = KioskPnlCalculator.calculate(new BigDecimal("24220.40"), noCommission, fixed, 30,
                KioskPnlCalculator.FLAT_VARIABLE_RATE);

        assertThat(KioskPnlCalculator.round2(real.breakEven())).isEqualByComparingTo("20905.66"); // CF / 0.7263
        assertThat(KioskPnlCalculator.round2(flat.breakEven())).isEqualByComparingTo("20799.70"); // CF / 0.73
        assertThat(KioskPnlCalculator.round2(flat.breakEvenDaily())).isEqualByComparingTo("693.32");
        // costos, utilidad y margen no dependen del método
        assertThat(flat.variableTotal()).isEqualByComparingTo(real.variableTotal());
        assertThat(flat.difference()).isEqualByComparingTo(real.difference());
        assertThat(flat.margin()).isEqualByComparingTo(real.margin());
    }

    @Test
    void breakEvenIsNullWhenDenominatorNotPositive() {
        // 80 % + 4 % (comisión fija) + 10 % + 10 % = 104 % > 100 %: no hay punto de equilibrio
        KioskPnlCalculator.Rates heavy = KioskPnlCalculator.Rates.of("0.80", "0.04", "0.10", "0.10");
        KioskPnlCalculator.Result r = KioskPnlCalculator.calculate(new BigDecimal("1000"), heavy, new BigDecimal("500"), 30);

        assertThat(r.breakEven()).isNull();
        assertThat(r.breakEvenDaily()).isNull();
    }

    @Test
    void nullRatesAreTreatedAsZero() {
        KioskPnlCalculator.Result r = KioskPnlCalculator.calculate(
                new BigDecimal("100"), new KioskPnlCalculator.Rates(null, null, null, null), new BigDecimal("10"), 30);

        // las tasas nulas cuentan como 0, salvo la comisión de venta, que es fija (4 % de ventas / 1.12)
        assertThat(KioskPnlCalculator.round2(r.variableTotal())).isEqualByComparingTo("3.57");
        assertThat(KioskPnlCalculator.round2(r.difference())).isEqualByComparingTo("86.43");
        assertThat(KioskPnlCalculator.round2(r.breakEven())).isEqualByComparingTo("10.42"); // 10 / 0.96
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
        // tasa faltante de costo del producto => incompleto
        assertThat(KioskPnlCalculator.isMonthComplete(new BigDecimal("1"),
                new KioskPnlCalculator.Rates(null, new BigDecimal("0.04"), BigDecimal.ONE, BigDecimal.ONE), full, required)).isFalse();
        // la comisión de venta es fija: que venga vacía NO hace incompleto el mes
        assertThat(KioskPnlCalculator.isMonthComplete(new BigDecimal("1"),
                new KioskPnlCalculator.Rates(new BigDecimal("0.18"), null, BigDecimal.ONE, BigDecimal.ONE), full, required)).isTrue();
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
    void from2026AppliesRateOverSalesWithoutIvaWhenGoalReachedAtLeast70Percent() {
        // 100,000 / 130,000 = 76.9 % -> comision = (ventas / 1.12) x 4 %
        assertThat(commission("100000", "130000", KioskPnlCalculator.CommissionPolicy.FROM_2026))
                .isEqualByComparingTo("3571.43");
    }

    @Test
    void from2026ChargesNoCommissionBelow70Percent() {
        // 90,000 / 130,000 = 69.2 % -> 0
        assertThat(commission("90000", "130000", KioskPnlCalculator.CommissionPolicy.FROM_2026))
                .isEqualByComparingTo("0.00");
    }

    @Test
    void from2026Exactly70PercentStillCharges() {
        // 91,000 / 130,000 = exactamente 70 % -> aplica (>= 0.7): 91000 / 1.12 x 4 % = 3250
        assertThat(commission("91000", "130000", KioskPnlCalculator.CommissionPolicy.FROM_2026))
                .isEqualByComparingTo("3250.00");
        assertThat(commission("90999.99", "130000", KioskPnlCalculator.CommissionPolicy.FROM_2026))
                .isEqualByComparingTo("0.00");
    }

    @Test
    void from2026WithoutGoalCannotVerify70PercentSoItCharges() {
        assertThat(commission("100000", null, KioskPnlCalculator.CommissionPolicy.FROM_2026))
                .isEqualByComparingTo("3571.43");
        assertThat(commission("100000", "0", KioskPnlCalculator.CommissionPolicy.FROM_2026))
                .isEqualByComparingTo("3571.43");
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
        // variable = costo 18 % + tarjeta 2.5 % + IVA 2.5 % (+ comision (ventas / 1.12) x 4 % solo arriba del 70 %)
        assertThat(KioskPnlCalculator.round2(below.variableTotal())).isEqualByComparingTo("20700.00"); // 90000 x 23 %
        assertThat(KioskPnlCalculator.round2(above.variableTotal())).isEqualByComparingTo("26571.43"); // 23000 + 3571.43
    }

    // ------------------------------------------------------------------ comision de venta: tasa FIJA 4 %

    @Test
    void salesCommissionRateIsFixedAt4PercentAndStoredValueIsIgnored() {
        assertThat(KioskPnlCalculator.SALES_COMMISSION_RATE).isEqualByComparingTo("0.04");
        // en la base quedo 0.31 % (el caso de Miraflores II, septiembre 2026): no debe usarse
        KioskPnlCalculator.Rates stored = KioskPnlCalculator.Rates.of("0.18", "0.0031", "0.025", "0.025");
        KioskPnlCalculator.Result r = KioskPnlCalculator.calculate(new BigDecimal("84226.40"), stored,
                new LinkedHashMap<String, BigDecimal>(), 30, null, KioskPnlCalculator.CommissionPolicy.FROM_2026,
                new BigDecimal("120000"));
        // 84,226.40 / 120,000 = 70.2 % >= 70 %  ->  (84,226.40 / 1.12) x 4 % = 3,008.09
        assertThat(KioskPnlCalculator.round2(r.salesCommission())).isEqualByComparingTo("3008.09");
    }

    @Test
    void zeroOrMissingStoredCommissionRateStillChargesFourPercent() {
        for (String stored : new String[]{"0", "0.0000"}) {
            KioskPnlCalculator.Rates rates = KioskPnlCalculator.Rates.of("0.18", stored, "0.025", "0.025");
            assertThat(KioskPnlCalculator.round2(KioskPnlCalculator.calculate(new BigDecimal("100000"), rates,
                    new LinkedHashMap<String, BigDecimal>(), 30).salesCommission())).isEqualByComparingTo("3571.43");
        }
        KioskPnlCalculator.Rates none = new KioskPnlCalculator.Rates(new BigDecimal("0.18"), null,
                new BigDecimal("0.025"), new BigDecimal("0.025"));
        assertThat(KioskPnlCalculator.round2(KioskPnlCalculator.calculate(new BigDecimal("100000"), none,
                new LinkedHashMap<String, BigDecimal>(), 30).salesCommission())).isEqualByComparingTo("3571.43");
    }

    // ------------------------------------------------------------------ bono por meta (fijo, desde septiembre 2026)

    private static BigDecimal bonus(String sales, String goal, KioskPnlCalculator.CommissionPolicy policy) {
        return KioskPnlCalculator.calculate(new BigDecimal(sales), RATES, new LinkedHashMap<String, BigDecimal>(), 30,
                null, policy, goal == null ? null : new BigDecimal(goal)).bonus();
    }

    @Test
    void policyForPeriodAddsTheBonusOnlyFromSeptember2026() {
        assertThat(KioskPnlCalculator.CommissionPolicy.forPeriod(2025, 12)).isSameAs(KioskPnlCalculator.CommissionPolicy.LEGACY);
        assertThat(KioskPnlCalculator.CommissionPolicy.forPeriod(2026, 1)).isSameAs(KioskPnlCalculator.CommissionPolicy.FROM_2026);
        assertThat(KioskPnlCalculator.CommissionPolicy.forPeriod(2026, 8)).isSameAs(KioskPnlCalculator.CommissionPolicy.FROM_2026);
        assertThat(KioskPnlCalculator.CommissionPolicy.forPeriod(2026, 9))
                .isSameAs(KioskPnlCalculator.CommissionPolicy.FROM_2026_WITH_BONUS);
        assertThat(KioskPnlCalculator.CommissionPolicy.forPeriod(2027, 1))
                .isSameAs(KioskPnlCalculator.CommissionPolicy.FROM_2026_WITH_BONUS);
    }

    @Test
    void bonusIs500From90PercentAnd800From100PercentOfGoal() {
        KioskPnlCalculator.CommissionPolicy p = KioskPnlCalculator.CommissionPolicy.FROM_2026_WITH_BONUS;
        assertThat(bonus("89999.99", "100000", p)).isEqualByComparingTo("0");
        assertThat(bonus("90000", "100000", p)).isEqualByComparingTo("500");   // exactamente 90 %
        assertThat(bonus("99999.99", "100000", p)).isEqualByComparingTo("500");
        assertThat(bonus("100000", "100000", p)).isEqualByComparingTo("800");  // exactamente 100 %
        assertThat(bonus("150000", "100000", p)).isEqualByComparingTo("800");
    }

    @Test
    void bonusNeedsAVerifiableGoalAndAnEnabledPolicy() {
        assertThat(bonus("500000", null, KioskPnlCalculator.CommissionPolicy.FROM_2026_WITH_BONUS)).isEqualByComparingTo("0");
        assertThat(bonus("500000", "0", KioskPnlCalculator.CommissionPolicy.FROM_2026_WITH_BONUS)).isEqualByComparingTo("0");
        // antes de septiembre 2026 y en 2025 no hay bono aunque se cumpla la meta
        assertThat(bonus("150000", "100000", KioskPnlCalculator.CommissionPolicy.FROM_2026)).isEqualByComparingTo("0");
        assertThat(bonus("150000", "100000", KioskPnlCalculator.CommissionPolicy.LEGACY)).isEqualByComparingTo("0");
    }

    @Test
    void bonusIsPartOfTheVariableTotalButNotOfTheBreakEvenRate() {
        BigDecimal fixed = new BigDecimal("10000");
        KioskPnlCalculator.Result with = KioskPnlCalculator.calculate(new BigDecimal("100000"), RATES, fixed, 30, null,
                KioskPnlCalculator.CommissionPolicy.FROM_2026_WITH_BONUS, new BigDecimal("100000"));
        KioskPnlCalculator.Result without = KioskPnlCalculator.calculate(new BigDecimal("100000"), RATES, fixed, 30, null,
                KioskPnlCalculator.CommissionPolicy.FROM_2026, new BigDecimal("100000"));

        assertThat(with.variableTotal().subtract(without.variableTotal())).isEqualByComparingTo("800");
        assertThat(with.totalCost().subtract(without.totalCost())).isEqualByComparingTo("800");
        assertThat(with.difference().add(new BigDecimal("800"))).isEqualByComparingTo(without.difference());
        assertThat(with.breakEven()).isEqualByComparingTo(without.breakEven());
    }

    @Test
    void bonusIsProratedForPartialPeriods() {
        KioskPnlCalculator.Result half = KioskPnlCalculator.calculate(new BigDecimal("50000"), RATES,
                new LinkedHashMap<String, BigDecimal>(), 30, null, KioskPnlCalculator.CommissionPolicy.FROM_2026_WITH_BONUS,
                new BigDecimal("50000"), new BigDecimal("0.5"));
        assertThat(half.bonus()).isEqualByComparingTo("400"); // Q800 x 0.5
    }
}
