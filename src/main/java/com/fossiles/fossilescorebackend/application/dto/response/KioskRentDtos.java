package com.fossiles.fossilescorebackend.application.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Respuestas de Contabilidad > Rentas (arrendador, día de pago y contrato de cada kiosco). */
public final class KioskRentDtos {

    private KioskRentDtos() {
    }

    /**
     * Un kiosco. {@code monthlyRent} sale de Finanzas (costo fijo ALQUILER, el último mes cargado = {@code rentPeriod}
     * "YYYY-MM"); null si Finanzas no lo tiene. {@code status}: SIN_CARGAR, INCOMPLETO, VENCIDO, POR_VENCER o VIGENTE.
     */
    public record RentRow(Long locationId, String code, String name, String landlordName, BigDecimal monthlyRent,
                          String rentPeriod, Integer paymentDay, LocalDate contractStart, LocalDate contractEnd,
                          String note, String status, LocalDate nextPaymentDate, Integer daysToNextPayment,
                          Integer daysToContractEnd, Integer contractProgressPct, LocalDateTime updatedAt) {
    }

    public record Summary(int kiosksTotal, int kiosksWithRent, BigDecimal totalMonthlyRent, BigDecimal totalAnnualRent,
                          int paymentsNext7Days, BigDecimal paymentsNext7DaysAmount, Map<String, Integer> countByStatus) {
    }

    public record ListResponse(LocalDate today, int expiringDays, boolean canEdit, Summary summary, List<RentRow> rows,
                               List<String> landlords) {
    }

    /** {@code kind}: NEW (el kiosco no tenía datos) o UPDATE. */
    public record Change(Long locationId, String code, String name, String landlordName, Integer paymentDay,
                         LocalDate contractStart, LocalDate contractEnd, LocalDate previousContractEnd, String kind) {
    }

    public record RowError(int row, Long locationId, String code, String name, String message) {
    }

    public record ImportResult(boolean applied, int readRows, int blankRows, int unchangedRows, List<Change> changes,
                               List<RowError> errors) {
    }
}
