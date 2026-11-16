package com.fossiles.fossilescorebackend.application.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Respuestas de Contabilidad > Rentas: kioscos y otras rentas (oficina, bodega u otra). */
public final class KioskRentDtos {

    private KioskRentDtos() {
    }

    /**
     * Una renta. {@code rentType} es KIOSK u OTHER. En un kiosco {@code rentId}, {@code category} y
     * {@code description} van null, y {@code monthlyRent} sale de Finanzas (costo fijo ALQUILER, el último mes
     * cargado = {@code rentPeriod} "YYYY-MM"). En otra renta {@code locationId} y {@code code} van null,
     * {@code name} es el nombre de la renta y {@code monthlyRent} es el monto guardado ({@code rentPeriod} null).
     * {@code status}: SIN_CARGAR, INCOMPLETO, VENCIDO, POR_VENCER o VIGENTE.
     */
    public record RentRow(Long locationId, String code, String name, String landlordName, BigDecimal monthlyRent,
                          String rentPeriod, Integer paymentDay, LocalDate contractStart, LocalDate contractEnd,
                          String note, String status, LocalDate nextPaymentDate, Integer daysToNextPayment,
                          Integer daysToContractEnd, Integer contractProgressPct, LocalDateTime updatedAt,
                          String rentType, Long rentId, String category, String description) {
    }

    /**
     * {@code kiosksTotal} y {@code kiosksWithRent} cuentan solo kioscos. {@code otherRentsTotal} es el conteo de otras
     * rentas activas y {@code otherMonthlyRent} la suma de su monto. El mensual, el anual, los próximos 7 días y
     * {@code countByStatus} incluyen kioscos y otras rentas.
     */
    public record Summary(int kiosksTotal, int kiosksWithRent, BigDecimal totalMonthlyRent, BigDecimal totalAnnualRent,
                          int paymentsNext7Days, BigDecimal paymentsNext7DaysAmount, Map<String, Integer> countByStatus,
                          int otherRentsTotal, BigDecimal otherMonthlyRent) {
    }

    public record ListResponse(LocalDate today, int expiringDays, boolean canEdit, Summary summary, List<RentRow> rows,
                               List<String> landlords) {
    }

    /** {@code kind}: NEW (no tenía datos) o UPDATE. {@code rentType} KIOSK u OTHER; {@code rentId} solo en OTHER. */
    public record Change(Long locationId, String code, String name, String landlordName, Integer paymentDay,
                         LocalDate contractStart, LocalDate contractEnd, LocalDate previousContractEnd, String kind,
                         String rentType, Long rentId) {
    }

    public record RowError(int row, Long locationId, String code, String name, String message, String rentType,
                           Long rentId) {
    }

    public record ImportResult(boolean applied, int readRows, int blankRows, int unchangedRows, List<Change> changes,
                               List<RowError> errors) {
    }
}
