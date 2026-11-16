package com.fossiles.fossilescorebackend.application.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class KioskRentCalculatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 16);

    @Test
    void statusDependsOnWhatIsLoadedAndOnTheContractEnd() {
        assertEquals("SIN_CARGAR", KioskRentCalculator.status(null, null, null, null, TODAY));
        assertEquals("SIN_CARGAR", KioskRentCalculator.status("  ", null, null, null, TODAY));
        assertEquals("INCOMPLETO", KioskRentCalculator.status("Arrendador", 5, LocalDate.of(2026, 1, 1), null, TODAY));
        LocalDate start = LocalDate.of(2025, 1, 1);
        assertEquals("VENCIDO", KioskRentCalculator.status("A", 5, start, LocalDate.of(2026, 11, 15), TODAY));
        assertEquals("POR_VENCER", KioskRentCalculator.status("A", 5, start, TODAY, TODAY));
        assertEquals("POR_VENCER", KioskRentCalculator.status("A", 5, start, TODAY.plusDays(60), TODAY));
        assertEquals("VIGENTE", KioskRentCalculator.status("A", 5, start, TODAY.plusDays(61), TODAY));
    }

    @Test
    void nextPaymentIsThisMonthOrNextAndClampsShortMonths() {
        LocalDate start = LocalDate.of(2025, 1, 1);
        LocalDate end = LocalDate.of(2027, 12, 31);
        assertEquals(LocalDate.of(2026, 11, 20), KioskRentCalculator.nextPaymentDate(20, start, end, TODAY));
        assertEquals(LocalDate.of(2026, 12, 5), KioskRentCalculator.nextPaymentDate(5, start, end, TODAY));
        assertEquals(LocalDate.of(2026, 11, 16), KioskRentCalculator.nextPaymentDate(16, start, end, TODAY));
        assertEquals(LocalDate.of(2027, 2, 28), KioskRentCalculator.nextPaymentDate(30, start, end, LocalDate.of(2027, 2, 1)));
        assertEquals(LocalDate.of(2027, 2, 28), KioskRentCalculator.nextPaymentDate(31, start, end, LocalDate.of(2027, 2, 1)));
        assertEquals(LocalDate.of(2026, 4, 30), KioskRentCalculator.nextPaymentDate(31, start, end, LocalDate.of(2026, 4, 1)));
        assertNull(KioskRentCalculator.nextPaymentDate(null, start, end, TODAY));
    }

    @Test
    void nextPaymentRespectsTheContractWindow() {
        // Aún no empieza: el primer pago es el del mes de inicio.
        assertEquals(LocalDate.of(2027, 1, 10),
                KioskRentCalculator.nextPaymentDate(10, LocalDate.of(2027, 1, 1), LocalDate.of(2027, 12, 31), TODAY));
        // Ya no quedan pagos antes del fin del contrato.
        assertNull(KioskRentCalculator.nextPaymentDate(5, LocalDate.of(2025, 1, 1), LocalDate.of(2026, 11, 30), TODAY));
    }

    @Test
    void progressIsClampedBetweenZeroAndOneHundred() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 12, 31);
        assertEquals(0, KioskRentCalculator.progressPct(start, end, LocalDate.of(2025, 6, 1)));
        assertEquals(100, KioskRentCalculator.progressPct(start, end, LocalDate.of(2027, 6, 1)));
        assertEquals(88,KioskRentCalculator.progressPct(start, end, TODAY));
        assertNull(KioskRentCalculator.progressPct(null, end, TODAY));
    }
}
