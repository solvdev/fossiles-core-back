package com.fossiles.fossilescorebackend.application.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
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

    /**
     * Tasa de la comision de venta: FIJA en 4 % para todos los kioscos y todos los anios (asi fue en los 12 Excel
     * de 2025 y asi se confirmo para 2026). No se lee de la base ni se configura; el 70 % de la meta que la
     * condiciona desde 2026 esta en {@link CommissionPolicy}.
     */
    public static final BigDecimal SALES_COMMISSION_RATE = new BigDecimal("0.04");

    /**
     * Bono de la encargada por meta cumplida (FIJO, no configurable; mismos montos y niveles del modulo Metas de
     * Kioskos): desde 90 % de la meta Q500 y desde 100 % Q800. Va aparte de la comision de venta (4 % = 2 % encargada
     * + 2 % supervisora). Solo se carga en el P&L a partir de {@link CommissionPolicy#BONUS_FROM}.
     */
    public static final BigDecimal BONUS_TIER2_MIN_GOAL = new BigDecimal("0.90");
    public static final BigDecimal BONUS_TIER3_MIN_GOAL = new BigDecimal("1.00");
    public static final BigDecimal BONUS_TIER2_AMOUNT = new BigDecimal("500");
    public static final BigDecimal BONUS_TIER3_AMOUNT = new BigDecimal("800");
    private static final int SCALE = 10;

    private KioskPnlCalculator() {
    }

    /**
     * Regla de la comision de venta (cambia entre los Excel de 2025 y los de 2026).
     * <ul>
     *   <li>{@link #LEGACY} (2025 y anteriores): {@code (ventas / 1.12) x tasa}, siempre.</li>
     *   <li>{@link #FROM_2026}: {@code (ventas / 1.12) x tasa}, y solo si el kiosco llega al 70 % de su meta
     *       ({@code =IF(% de meta >= 0.7, (ventas / 1.12) x tasa, 0)}); si no, 0.</li>
     * </ul>
     * La base es siempre la venta sin IVA; lo que cambia en 2026 es la condicion del 70 %.
     *
     * @param grossBase   true = la tasa se aplica sobre ventas con IVA; false = sobre ventas / 1.12 (ambas reglas usan false)
     * @param minGoalPct  cumplimiento de meta minimo (0.70) para que la comision aplique; null = sin condicion
     */
    public record CommissionPolicy(boolean grossBase, BigDecimal minGoalPct, boolean bonusEnabled) {
        public static final int POLICY_CHANGE_YEAR = 2026;
        /** Desde este mes (septiembre 2026, cuando ya hay datos del sistema) el P&L incluye el bono por meta. */
        public static final YearMonth BONUS_FROM = YearMonth.of(2026, 9);
        public static final CommissionPolicy LEGACY = new CommissionPolicy(false, null, false);
        public static final CommissionPolicy FROM_2026 = new CommissionPolicy(false, new BigDecimal("0.70"), false);
        public static final CommissionPolicy FROM_2026_WITH_BONUS =
                new CommissionPolicy(false, new BigDecimal("0.70"), true);

        public static CommissionPolicy forYear(int year) {
            return year >= POLICY_CHANGE_YEAR ? FROM_2026 : LEGACY;
        }

        /** Regla de un mes concreto: la del anio, mas el bono por meta desde {@link #BONUS_FROM}. */
        public static CommissionPolicy forPeriod(int year, int month) {
            if (!YearMonth.of(year, month).isBefore(BONUS_FROM)) {
                return FROM_2026_WITH_BONUS;
            }
            return forYear(year);
        }

        /**
         * Bono por meta: Q500 desde 90 % de la meta, Q800 desde 100 %. Necesita una meta verificable (sin meta no
         * hay bono: se desconoce si la alcanzo). Comparacion exacta contra meta x nivel.
         */
        BigDecimal bonus(BigDecimal sales, BigDecimal goal) {
            if (!bonusEnabled || goal == null || goal.signum() <= 0) {
                return BigDecimal.ZERO;
            }
            if (sales.compareTo(goal.multiply(BONUS_TIER3_MIN_GOAL)) >= 0) {
                return BONUS_TIER3_AMOUNT;
            }
            if (sales.compareTo(goal.multiply(BONUS_TIER2_MIN_GOAL)) >= 0) {
                return BONUS_TIER2_AMOUNT;
            }
            return BigDecimal.ZERO;
        }

        /**
         * Si la comision aplica. Sin meta definida (null o 0) no se puede verificar el 70 %: se aplica,
         * para no subestimar costos. Comparacion exacta: ventas >= meta x minimo.
         */
        boolean applies(BigDecimal sales, BigDecimal goal) {
            if (minGoalPct == null || goal == null || goal.signum() <= 0) {
                return true;
            }
            return sales.compareTo(goal.multiply(minGoalPct)) >= 0;
        }
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
            // la comision de venta es fija (SALES_COMMISSION_RATE): no cuenta para saber si el mes esta completo
            return productCostPct != null && cardCommissionPct != null && taxPct != null;
        }
    }

    public record Result(
            BigDecimal sales,
            BigDecimal productCost,
            BigDecimal salesCommission,
            BigDecimal cardCommission,
            BigDecimal tax,
            /** Bono de la encargada por meta cumplida (0 antes de septiembre 2026 o sin meta). */
            BigDecimal bonus,
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
        return calculate(sales, rates, fixedTotal, days, flatVariableRate, CommissionPolicy.LEGACY, null);
    }

    /** Igual que la anterior pero con la regla de comision del anio y la meta del mes (para el 70 %). */
    public static Result calculate(BigDecimal sales, Rates rates, Map<String, BigDecimal> fixedByCategory, int days,
                                   BigDecimal flatVariableRate, CommissionPolicy policy, BigDecimal goal) {
        return calculate(sales, rates, sumFixed(fixedByCategory), days, flatVariableRate, policy, goal);
    }

    /**
     * @param policy regla de la comision de venta (null = {@link CommissionPolicy#LEGACY})
     * @param goal   meta de ventas del mismo periodo que {@code sales}; solo se usa si la regla tiene minimo
     */
    public static Result calculate(BigDecimal sales, Rates rates, BigDecimal fixedTotal, int days,
                                   BigDecimal flatVariableRate, CommissionPolicy policy, BigDecimal goal) {
        return calculate(sales, rates, fixedTotal, days, flatVariableRate, policy, goal, BigDecimal.ONE);
    }

    /** Igual que la anterior; {@code bonusFactor} prorratea el bono en periodos parciales (1 = mes completo). */
    public static Result calculate(BigDecimal sales, Rates rates, Map<String, BigDecimal> fixedByCategory, int days,
                                   BigDecimal flatVariableRate, CommissionPolicy policy, BigDecimal goal,
                                   BigDecimal bonusFactor) {
        return calculate(sales, rates, sumFixed(fixedByCategory), days, flatVariableRate, policy, goal, bonusFactor);
    }

    public static Result calculate(BigDecimal sales, Rates rates, BigDecimal fixedTotal, int days,
                                   BigDecimal flatVariableRate, CommissionPolicy policy, BigDecimal goal,
                                   BigDecimal bonusFactor) {
        CommissionPolicy rule = policy == null ? CommissionPolicy.LEGACY : policy;
        BigDecimal v = nz(sales);
        BigDecimal cf = nz(fixedTotal);
        BigDecimal pc = rates == null ? BigDecimal.ZERO : nz(rates.productCostPct());
        BigDecimal sc = SALES_COMMISSION_RATE; // fija: se ignora lo que traiga rates.salesCommissionPct()
        BigDecimal tc = rates == null ? BigDecimal.ZERO : nz(rates.cardCommissionPct());
        BigDecimal tx = rates == null ? BigDecimal.ZERO : nz(rates.taxPct());

        BigDecimal productCost = v.multiply(pc);
        BigDecimal commissionBase = rule.grossBase() ? v : v.divide(IVA_DIVISOR, SCALE, RoundingMode.HALF_UP);
        BigDecimal salesCommission = rule.applies(v, goal) ? commissionBase.multiply(sc) : BigDecimal.ZERO;
        BigDecimal cardCommission = v.multiply(tc);
        BigDecimal tax = v.multiply(tx);
        BigDecimal bonus = rule.bonus(v, goal).multiply(bonusFactor == null ? BigDecimal.ONE : bonusFactor);
        BigDecimal variable = productCost.add(salesCommission).add(cardCommission).add(tax).add(bonus);
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

        return new Result(v, productCost, salesCommission, cardCommission, tax, bonus, variable,
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
