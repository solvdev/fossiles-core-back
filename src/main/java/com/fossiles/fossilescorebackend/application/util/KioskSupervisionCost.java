package com.fossiles.fossilescorebackend.application.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Costo fijo "Supervisión" por kiosco y mes:
 * {@code ((((Salarios MO indirecta + Bonificación) × 2) × 14) / 12) / kioscos activos}.
 * "Salarios MO indirecta" es la fila de salario de encargadas (en el Excel 2026 se llama
 * "Salarios encargadas"); ver docs/KIOSK-FINANCIALS-CONTRACT.md.
 */
public final class KioskSupervisionCost {

    private static final BigDecimal TWO = BigDecimal.valueOf(2);
    private static final BigDecimal FOURTEEN = BigDecimal.valueOf(14);
    private static final long TWELVE = 12L;

    private KioskSupervisionCost() {
    }

    /** @return monto a 2 decimales (HALF_UP) o null si falta algún dato o no hay kioscos activos. */
    public static BigDecimal compute(BigDecimal salarioMoIndirecta, BigDecimal bonificacion, int activeKiosks) {
        if (salarioMoIndirecta == null || bonificacion == null || activeKiosks <= 0) {
            return null;
        }
        return salarioMoIndirecta.add(bonificacion)
                .multiply(TWO)
                .multiply(FOURTEEN)
                .divide(BigDecimal.valueOf(TWELVE * activeKiosks), 2, RoundingMode.HALF_UP);
    }
}
