package com.fossiles.fossilescorebackend.application.util;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;

/**
 * Pronósticos sencillos y explicables para Finanzas por kiosco (sin librerías estadísticas):
 * <ul>
 *   <li>Cierre proyectado del mes en curso: ventas acumuladas + días restantes según el promedio por día de la semana
 *   de las últimas 8 semanas.</li>
 *   <li>Proyección del año siguiente: mismo mes del año anterior × factor de crecimiento (comparación del mismo
 *   período contra el año anterior); donde falta historia se usa el nivel del kiosco × el índice estacional de los
 *   kioscos con 12 meses de historia.</li>
 * </ul>
 * Todo es determinista y se prueba contra el pasado (ver docs/KIOSK-FINANCIALS-CONTRACT.md).
 */
public final class KioskForecastMath {

    /** Ventana para el patrón por día de la semana: 8 semanas. */
    public static final int WINDOW_DAYS = 56;
    /** Con menos días de historia en la ventana no se usa el patrón semanal. */
    public static final int MIN_WINDOW_DAYS = 14;
    /** Con menos días operados en el mes no se proyecta. */
    public static final int MIN_RUN_RATE_DAYS = 3;
    /** Intervalo del 80 %. */
    private static final double Z_80 = 1.2816;

    public static final double MIN_GROWTH = 0.5;
    public static final double MAX_GROWTH = 2.0;
    /** Para usar el crecimiento propio de un kiosco: días comparables y días con venta en el año base. */
    public static final int MIN_COMPARABLE_DAYS = 90;
    public static final int MIN_BASE_DAYS_WITH_SALES = 60;

    public static final String METHOD_WEEKDAY = "WEEKDAY";
    public static final String METHOD_RUN_RATE = "RUN_RATE";
    public static final String METHOD_INSUFFICIENT = "INSUFFICIENT";

    private KioskForecastMath() {
    }

    // ------------------------------------------------------------------ cierre del mes

    /**
     * @param projected/low/high null cuando {@code method} es INSUFFICIENT
     */
    public record MonthEnd(double mtd, double actualToDate, Double projected, Double low, Double high,
                           int daysElapsed, int daysRemaining, int daysInMonth, int sampleDays, String method) {
    }

    /**
     * Cierre proyectado del mes de {@code today}.
     * <ul>
     *   <li>mtd = ventas de los días completos del mes (antes de hoy); hoy cuenta como día por proyectar
     *   (se toma el mayor entre lo vendido hasta ahora y lo esperado).</li>
     *   <li>Esperado de un día = promedio de ese día de la semana en las últimas 8 semanas (los ceros cuentan: un
     *   kiosco que no abre los domingos tiene esperado 0 los domingos).</li>
     *   <li>Rango (80 %) = ± 1.28 × desviación de los residuos × √(días por proyectar), sin bajar de lo ya vendido.</li>
     * </ul>
     *
     * @param firstOperationalDate primera fecha con ventas del kiosco (go-live); los días anteriores no cuentan en la
     *                             ventana. null = sin recorte.
     */
    public static MonthEnd monthEnd(Map<LocalDate, BigDecimal> daily, LocalDate today, LocalDate firstOperationalDate) {
        YearMonth ym = YearMonth.from(today);
        LocalDate monthStart = ym.atDay(1);
        int daysInMonth = ym.lengthOfMonth();
        int daysElapsed = today.getDayOfMonth() - 1;
        int daysRemaining = daysInMonth - daysElapsed; // incluye hoy

        double mtd = 0;
        for (LocalDate d = monthStart; d.isBefore(today); d = d.plusDays(1)) {
            mtd += value(daily, d);
        }
        double todayActual = value(daily, today);
        double actualToDate = mtd + todayActual;

        LocalDate windowStart = today.minusDays(WINDOW_DAYS);
        if (firstOperationalDate != null && firstOperationalDate.isAfter(windowStart)) {
            windowStart = firstOperationalDate;
        }
        LocalDate windowEnd = today.minusDays(1);
        int sampleDays = windowEnd.isBefore(windowStart) ? 0 : (int) (windowEnd.toEpochDay() - windowStart.toEpochDay() + 1);

        double[] expectedByDow = new double[8];
        boolean[] hasDow = new boolean[8];
        double sigma;
        String method;
        double overallMean = 0;

        if (sampleDays >= MIN_WINDOW_DAYS) {
            method = METHOD_WEEKDAY;
            double[] sum = new double[8];
            int[] count = new int[8];
            double total = 0;
            for (LocalDate d = windowStart; !d.isAfter(windowEnd); d = d.plusDays(1)) {
                int dow = d.getDayOfWeek().getValue();
                double v = value(daily, d);
                sum[dow] += v;
                count[dow]++;
                total += v;
            }
            overallMean = total / sampleDays;
            for (int dow = 1; dow <= 7; dow++) {
                hasDow[dow] = count[dow] > 0;
                expectedByDow[dow] = hasDow[dow] ? sum[dow] / count[dow] : overallMean;
            }
            double sq = 0;
            for (LocalDate d = windowStart; !d.isAfter(windowEnd); d = d.plusDays(1)) {
                double r = value(daily, d) - expectedByDow[d.getDayOfWeek().getValue()];
                sq += r * r;
            }
            sigma = Math.sqrt(sq / sampleDays);
        } else {
            // Kiosco nuevo o con poca historia: promedio de los días operados del mes
            LocalDate opStart = monthStart;
            if (firstOperationalDate != null && firstOperationalDate.isAfter(opStart)) {
                opStart = firstOperationalDate;
            }
            int opDays = opStart.isBefore(today) ? (int) (today.toEpochDay() - opStart.toEpochDay()) : 0;
            if (opDays < MIN_RUN_RATE_DAYS) {
                return new MonthEnd(mtd, actualToDate, null, null, null, daysElapsed, daysRemaining, daysInMonth,
                        sampleDays, METHOD_INSUFFICIENT);
            }
            method = METHOD_RUN_RATE;
            double total = 0;
            for (LocalDate d = opStart; d.isBefore(today); d = d.plusDays(1)) {
                total += value(daily, d);
            }
            overallMean = total / opDays;
            double sq = 0;
            for (LocalDate d = opStart; d.isBefore(today); d = d.plusDays(1)) {
                double r = value(daily, d) - overallMean;
                sq += r * r;
            }
            sigma = Math.sqrt(sq / opDays);
            for (int dow = 1; dow <= 7; dow++) {
                expectedByDow[dow] = overallMean;
            }
        }

        double projected = mtd + Math.max(todayActual, expectedByDow[today.getDayOfWeek().getValue()]);
        for (LocalDate d = today.plusDays(1); !d.isAfter(ym.atEndOfMonth()); d = d.plusDays(1)) {
            projected += expectedByDow[d.getDayOfWeek().getValue()];
        }
        double half = Z_80 * sigma * Math.sqrt(daysRemaining);
        double low = Math.max(actualToDate, projected - half);
        double high = projected + half;
        return new MonthEnd(mtd, actualToDate, projected, low, high, daysElapsed, daysRemaining, daysInMonth,
                sampleDays, method);
    }

    private static double value(Map<LocalDate, BigDecimal> daily, LocalDate d) {
        BigDecimal v = daily == null ? null : daily.get(d);
        return v == null ? 0 : v.doubleValue();
    }

    // ------------------------------------------------------------------ crecimiento

    public record Growth(double factor, boolean capped) {
    }

    /**
     * Factor de crecimiento de un kiosco = ventas del período actual / ventas de las mismas fechas del año anterior.
     * null si no hay base comparable (sin ventas el año anterior, o menos de 90 días comparables / 60 días con venta en
     * la base): ahí se usa el crecimiento global. Se limita a [0.5, 2.0].
     */
    public static Growth siteGrowth(double currentSum, double baseSum, int comparableDays, int baseDaysWithSales) {
        if (baseSum <= 0 || comparableDays < MIN_COMPARABLE_DAYS || baseDaysWithSales < MIN_BASE_DAYS_WITH_SALES) {
            return null;
        }
        return clamp(currentSum / baseSum);
    }

    public static Growth clamp(double raw) {
        if (raw < MIN_GROWTH) {
            return new Growth(MIN_GROWTH, true);
        }
        if (raw > MAX_GROWTH) {
            return new Growth(MAX_GROWTH, true);
        }
        return new Growth(raw, false);
    }

    // ------------------------------------------------------------------ estacionalidad

    /**
     * Índice estacional por mes (promedio = 1) con los kioscos que tienen ventas los 12 meses del año base
     * ("mismos kioscos": no mezcla el efecto de abrir kioscos nuevos). Sin kioscos así, todos 1.
     */
    public static double[] seasonalIndex(Iterable<Double[]> matureBaseMonths) {
        double[] sum = new double[12];
        int sites = 0;
        for (Double[] months : matureBaseMonths) {
            boolean complete = months != null && months.length == 12;
            for (int m = 0; complete && m < 12; m++) {
                complete = months[m] != null && months[m] > 0;
            }
            if (!complete) {
                continue;
            }
            sites++;
            for (int m = 0; m < 12; m++) {
                sum[m] += months[m];
            }
        }
        double[] idx = new double[12];
        double total = 0;
        for (double s : sum) {
            total += s;
        }
        if (sites == 0 || total <= 0) {
            java.util.Arrays.fill(idx, 1.0);
            return idx;
        }
        double mean = total / 12.0;
        for (int m = 0; m < 12; m++) {
            idx[m] = sum[m] / mean;
        }
        return idx;
    }

    // ------------------------------------------------------------------ proyección del año siguiente

    /**
     * @param baseYear  año actual completo estimado (real + cierre proyectado + resto proyectado)
     * @param target    año siguiente
     * @param estimated meses cuyo valor salió del nivel del kiosco × índice estacional (no del mismo mes del año anterior)
     */
    public record YearProjection(Double[] baseYear, Double[] target, boolean[] estimated) {
    }

    /**
     * @param previousYear     ventas por mes del año anterior (null = sin datos ese mes)
     * @param currentYear      ventas del año en curso: real en los meses cerrados, cierre proyectado en el mes en curso y
     *                         null en los futuros (12 posiciones)
     * @param currentMonth     1..12, mes en curso
     * @param growth           factor aplicado (año sobre año)
     * @param seasonalIndex    de {@link #seasonalIndex}
     */
    public static YearProjection projectNextYear(Double[] previousYear, Double[] currentYear, int currentMonth,
                                                 double growth, double[] seasonalIndex) {
        Double[] base = new Double[12];
        boolean[] estimated = new boolean[12];
        for (int m = 0; m < 12; m++) {
            int month = m + 1;
            if (month <= currentMonth && currentYear[m] != null) {
                base[m] = currentYear[m];
            } else if (month > currentMonth && previousYear[m] != null) {
                // meses que aún no ocurren: mismo mes del año anterior × crecimiento
                base[m] = previousYear[m] * growth;
            }
        }
        // Huecos (kiosco que aún no existía o sin dato): nivel desestacionalizado del kiosco × índice del mes
        double levelSum = 0;
        int levelCount = 0;
        for (int m = 0; m < 12; m++) {
            if (base[m] != null && seasonalIndex[m] > 0) {
                levelSum += base[m] / seasonalIndex[m];
                levelCount++;
            }
        }
        if (levelCount >= 2) {
            double level = levelSum / levelCount;
            for (int m = 0; m < 12; m++) {
                if (base[m] == null) {
                    base[m] = level * seasonalIndex[m];
                    estimated[m] = true;
                }
            }
        }
        Double[] target = new Double[12];
        for (int m = 0; m < 12; m++) {
            target[m] = base[m] == null ? null : base[m] * growth;
        }
        return new YearProjection(base, target, estimated);
    }
}
