package com.fossiles.fossilescorebackend.application.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Map;

/**
 * Formulas de P&amp;L por sitio-mes (paridad con los Excel de 2025). Util puro, sin estado.
 * Los valores devueltos NO estan redondeados (escala interna 10): redondear al presentar con
 * {@link #round2(BigDecimal)} (montos) y {@link #round4(BigDecimal)} (porcentajes).
 * Los porcentajes son decimales: 0.18 = 18 %.
 */
public final class KioskPnlCalculator {

    private static final BigDecimal IVA_DIVISOR = new BigDecimal("1.12");
    private static final int SCALE = 10;

    private KioskPnlCalculator() {
    }

    /** Tasas del mes; null se trata como 0. */
    public record Rates(
            BigDecimal productCostPct,
            BigDecimal salesCommissionPct,
            BigDecimal cardCommissionPct,
            BigDecimal taxPct) {

        public static Rates of(String productCost, String salesCommission, String card, String tax) {
            return new Rates(new BigDecimal(productCost), new BigDecimal(salesCommission),
                    new BigDecimal(card), new BigDecimal(tax));
        }

        public boolean complete() {
            return productCostPct != null && salesCommissionPct != null
                    && cardCommissionPct != null && taxPct != null;
        }
    }

    public record Result(
            BigDecimal sales,
            BigDecimal productCost,
            BigDecimal salesCommission,
            BigDecimal cardCommission,
            BigDecimal tax,
            BigDecimal variableTotal,
            BigDecimal fixedTotal,
            BigDecimal totalCost,
            BigDecimal difference,
            /** Diferencia / ventas; null si ventas = 0. */
            BigDecimal margin,
            /** Punto de equilibrio mensual; null si el denominador &lt;= 0. */
            BigDecimal breakEven,
            /** PE / dias del mes; null si PE nulo o dias &lt;= 0. */
            BigDecimal breakEvenDaily) {
    }

    /**
     * Tasa variable fija que usan los reportes de Excel desde abril 2026 en el punto de equilibrio:
     * {@code CF / (1 - 0.27)}, igual para todos los kioscos (≈ 18 % + 4 % + 2.87 % + 2.5 %).
     */
    public static final BigDecimal FLAT_VARIABLE_RATE = new BigDecimal("0.27");

    public static Result calculate(BigDecimal sales, Rates rates, Map<String, BigDecimal> fixedByCategory, int days) {
        return calculate(sales, rates, sumFixed(fixedByCategory), days, null);
    }

    public static Result calculate(BigDecimal sales, Rates rates, Map<String, BigDecimal> fixedByCategory, int days,
                                   BigDecimal flatVariableRate) {
        return calculate(sales, rates, sumFixed(fixedByCategory), days, flatVariableRate);
    }

    public static Result calculate(BigDecimal sales, Rates rates, BigDecimal fixedTotal, int days) {
        return calculate(sales, rates, fixedTotal, days, null);
    }

    /**
     * @param flatVariableRate null = punto de equilibrio con las tasas reales del kiosco (por defecto); con valor,
     *                         el denominador es {@code 1 - flatVariableRate} (p. ej. 0.27) sin importar las tasas.
     *                         Sólo afecta al punto de equilibrio: costos, utilidad y margen siempre usan las tasas reales.
     */
    public static Result calculate(BigDecimal sales, Rates rates, BigDecimal fixedTotal, int days,
                                   BigDecimal flatVariableRate) {
        BigDecimal v = nz(sales);
        BigDecimal cf = nz(fixedTotal);
        BigDecimal pc = rates == null ? BigDecimal.ZERO : nz(rates.productCostPct());
        BigDecimal sc = rates == null ? BigDecimal.ZERO : nz(rates.salesCommissionPct());
        BigDecimal tc = rates == null ? BigDecimal.ZERO : nz(rates.cardCommissionPct());
        BigDecimal tx = rates == null ? BigDecimal.ZERO : nz(rates.taxPct());

        BigDecimal productCost = v.multiply(pc);
        BigDecimal salesCommission = v.divide(IVA_DIVISOR, SCALE, RoundingMode.HALF_UP).multiply(sc);
        BigDecimal cardCommission = v.multiply(tc);
        BigDecimal tax = v.multiply(tx);
        BigDecimal variable = productCost.add(salesCommission).add(cardCommission).add(tax);
        BigDecimal totalCost = variable.add(cf);
        BigDecimal difference = v.subtract(totalCost);

        BigDecimal margin = v.signum() == 0
                ? null
                : difference.divide(v, SCALE, RoundingMode.HALF_UP);

        BigDecimal variableRate = flatVariableRate != null ? flatVariableRate : sc.add(pc).add(tc).add(tx);
        BigDecimal denominator = BigDecimal.ONE.subtract(variableRate);
        BigDecimal breakEven = denominator.signum() <= 0
                ? null
                : cf.divide(denominator, SCALE, RoundingMode.HALF_UP);
        BigDecimal breakEvenDaily = (breakEven == null || days <= 0)
                ? null
                : breakEven.divide(BigDecimal.valueOf(days), SCALE, RoundingMode.HALF_UP);

        return new Result(v, productCost, salesCommission, cardCommission, tax, variable,
                cf, totalCost, difference, margin, breakEven, breakEvenDaily);
    }

    public static BigDecimal sumFixed(Map<String, BigDecimal> fixedByCategory) {
        BigDecimal total = BigDecimal.ZERO;
        if (fixedByCategory != null) {
            for (BigDecimal value : fixedByCategory.values()) {
                total = total.add(nz(value));
            }
        }
        return total;
    }

    /**
     * Mes completo: meta no nula + 4 tasas no nulas + todas las categorias requeridas con valor
     * (0 cuenta como valor).
     */
    public static boolean isMonthComplete(
            BigDecimal goal,
            Rates rates,
            Map<String, BigDecimal> costs,
            Collection<String> requiredCategories) {
        if (goal == null || rates == null || !rates.complete()) {
            return false;
        }
        for (String code : requiredCategories) {
            if (costs == null || costs.get(code) == null) {
                return false;
            }
        }
        return true;
    }

    /** numerador / denominador a escala interna; null si denominador nulo o 0. */
    public static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() == 0) {
            return null;
        }
        return numerator.divide(denominator, SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal round2(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal round4(BigDecimal value) {
        return value == null ? null : value.setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
