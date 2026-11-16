package com.fossiles.fossilescorebackend.application.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

/** Cálculos puros de Rentas (sin base de datos): estado del contrato, próximo pago y avance de la vigencia. */
public final class KioskRentCalculator {

    public static final String SIN_CARGAR = "SIN_CARGAR";
    public static final String INCOMPLETO = "INCOMPLETO";
    public static final String VENCIDO = "VENCIDO";
    public static final String POR_VENCER = "POR_VENCER";
    public static final String VIGENTE = "VIGENTE";

    /** Un contrato que vence dentro de estos días aparece como «por vencer». */
    public static final int EXPIRING_DAYS = 60;

    private KioskRentCalculator() {
    }

    /**
     * Sin datos → SIN_CARGAR; falta alguno de arrendador, día, inicio o fin → INCOMPLETO; si no, según el fin del
     * contrato: ya pasó (VENCIDO), vence en {@link #EXPIRING_DAYS} días o menos (POR_VENCER) o VIGENTE.
     */
    public static String status(String landlord, Integer paymentDay, LocalDate start, LocalDate end, LocalDate today) {
        boolean hasLandlord = landlord != null && !landlord.isBlank();
        boolean any = hasLandlord || paymentDay != null || start != null || end != null;
        if (!any) {
            return SIN_CARGAR;
        }
        if (!hasLandlord || paymentDay == null || start == null || end == null) {
            return INCOMPLETO;
        }
        long days = ChronoUnit.DAYS.between(today, end);
        if (days < 0) {
            return VENCIDO;
        }
        return days <= EXPIRING_DAYS ? POR_VENCER : VIGENTE;
    }

    /**
     * Próxima fecha de pago desde hoy (hoy cuenta). Si el mes es más corto que el día, se toma su último día. Si el
     * contrato aún no empieza se cuenta desde su inicio; si el próximo pago cae después del fin, ya no hay pagos (null).
     */
    public static LocalDate nextPaymentDate(Integer paymentDay, LocalDate start, LocalDate end, LocalDate today) {
        if (paymentDay == null) {
            return null;
        }
        LocalDate from = start != null && start.isAfter(today) ? start : today;
        YearMonth month = YearMonth.from(from);
        LocalDate candidate = inMonth(month, paymentDay);
        if (candidate.isBefore(from)) {
            candidate = inMonth(month.plusMonths(1), paymentDay);
        }
        return end != null && candidate.isAfter(end) ? null : candidate;
    }

    /** Porcentaje (0-100) de la vigencia que ya pasó; null si faltan las fechas. */
    public static Integer progressPct(LocalDate start, LocalDate end, LocalDate today) {
        if (start == null || end == null || end.isBefore(start)) {
            return null;
        }
        long total = ChronoUnit.DAYS.between(start, end) + 1;
        long elapsed = ChronoUnit.DAYS.between(start, today) + 1;
        long pct = Math.round(elapsed * 100.0 / total);
        return (int) Math.max(0, Math.min(100, pct));
    }

    private static LocalDate inMonth(YearMonth month, int day) {
        return month.atDay(Math.min(day, month.lengthOfMonth()));
    }
}
