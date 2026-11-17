package com.fossiles.fossilescorebackend.application.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Reglas del reporte de rotación y cobertura: con el stock actual y lo que se vendió (o consumió) en la ventana
 * se calcula el ritmo diario, los días de cobertura (cuántos días alcanza lo que hay) y un estado de una palabra
 * para que el reporte se entienda sin leer números.
 */
public final class InventoryRotationClassifier {

    private InventoryRotationClassifier() {
    }

    public enum Status {
        /** No hay existencia y en la ventana sí se movió: se está perdiendo venta o producción. */
        SIN_STOCK,
        /** Alcanza para menos días que el umbral "corto". */
        SE_AGOTA,
        NORMAL,
        /** Alcanza para más días que el umbral "lento". */
        LENTO,
        /** Hay existencia y no se movió nada en la ventana. */
        SIN_MOVIMIENTO;

        /** Orden de atención: lo urgente primero, lo parado al final. */
        public int severity() {
            return ordinal();
        }
    }

    /** Días de cobertura que separan "se agota" de "normal" y "normal" de "lento". */
    public record Profile(int shortDays, int slowDays) {
    }

    /** Los kioscos reciben envíos casi a diario, así que sus umbrales son más cortos. */
    public static final Profile KIOSK = new Profile(5, 45);
    public static final Profile MATERIAL = new Profile(7, 90);

    public static BigDecimal dailyRate(BigDecimal moved, int windowDays) {
        if (moved == null || moved.signum() <= 0 || windowDays <= 0) {
            return BigDecimal.ZERO;
        }
        return moved.divide(BigDecimal.valueOf(windowDays), 3, RoundingMode.HALF_UP);
    }

    /** Días que alcanza el stock al ritmo actual; {@code null} cuando no hay ritmo (no se puede estimar). */
    public static BigDecimal coverageDays(BigDecimal stock, BigDecimal dailyRate) {
        if (stock == null || dailyRate == null || dailyRate.signum() <= 0) {
            return null;
        }
        return stock.max(BigDecimal.ZERO).divide(dailyRate, 1, RoundingMode.HALF_UP);
    }

    /**
     * @return el estado, o {@code null} si no hay existencia ni movimiento (la fila no aporta al reporte).
     */
    public static Status classify(BigDecimal stock, BigDecimal moved, int windowDays, Profile profile) {
        boolean hasStock = stock != null && stock.signum() > 0;
        boolean hasMovement = moved != null && moved.signum() > 0;
        if (!hasStock && !hasMovement) {
            return null;
        }
        if (!hasStock) {
            return Status.SIN_STOCK;
        }
        if (!hasMovement) {
            return Status.SIN_MOVIMIENTO;
        }
        BigDecimal coverage = coverageDays(stock, dailyRate(moved, windowDays));
        if (coverage == null) {
            return Status.SIN_MOVIMIENTO;
        }
        if (coverage.compareTo(BigDecimal.valueOf(profile.shortDays())) < 0) {
            return Status.SE_AGOTA;
        }
        if (coverage.compareTo(BigDecimal.valueOf(profile.slowDays())) > 0) {
            return Status.LENTO;
        }
        return Status.NORMAL;
    }
}
