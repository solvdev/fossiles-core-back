package com.fossiles.fossilescorebackend.application.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Due date of a rent for month M is that month's payment day, clamped to the last day.
 * Baku's generate-rents test already covers February 2026 (28). This checks the clamp itself
 * for leap February, 30-day months, and a 31-day month that must not move.
 */
class KioskRentClampedPayDateQaTest {

    @Test
    void paymentDay31ClampsToLastDayOfShortMonths() {
        assertEquals(LocalDate.of(2026, 2, 28), clamp(2026, 2, 31));
        assertEquals(LocalDate.of(2028, 2, 29), clamp(2028, 2, 31));
        assertEquals(LocalDate.of(2026, 4, 30), clamp(2026, 4, 31));
        assertEquals(LocalDate.of(2026, 6, 30), clamp(2026, 6, 31));
        assertEquals(LocalDate.of(2026, 9, 30), clamp(2026, 9, 31));
        assertEquals(LocalDate.of(2026, 11, 30), clamp(2026, 11, 31));
        assertEquals(LocalDate.of(2026, 1, 31), clamp(2026, 1, 31));
        assertEquals(LocalDate.of(2026, 3, 31), clamp(2026, 3, 31));
        assertEquals(LocalDate.of(2028, 2, 15), clamp(2028, 2, 15));
        assertEquals(LocalDate.of(2026, 4, 30), clamp(2026, 4, 30));
    }

    private static LocalDate clamp(int year, int month, int paymentDay) {
        return KioskRentService.clampedPayDate(YearMonth.of(year, month), paymentDay);
    }
}
