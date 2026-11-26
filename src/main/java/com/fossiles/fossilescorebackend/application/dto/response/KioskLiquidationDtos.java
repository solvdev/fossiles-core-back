package com.fossiles.fossilescorebackend.application.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;
import java.util.List;

/** Carga del Excel «Liquidaciones por semana» y la pestaña Liquidación del calendario de conteos. */
public final class KioskLiquidationDtos {

    private KioskLiquidationDtos() {
    }

    /** Una fila del Excel ya leída por el front: kiosco, período del inventario y datos de la liquidación. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ImportRow(String sheetSupervisor, String kioskName, LocalDate periodFrom, LocalDate periodTo,
                            String accountant, LocalDate correctionsDue, List<LocalDate> liquidationDates,
                            String greenNote, Integer sourceRow) {
    }

    public record UnmatchedKiosk(String name, int rows) {
    }

    /** El Excel y el sistema tienen el conteo de ese kiosco en fechas distintas (el Excel manda). */
    public record DateConflict(String kiosk, LocalDate excelPeriodTo, LocalDate systemPeriodTo) {
    }

    public record ImportPreview(int totalRows, int matchedRows, int kiosks, LocalDate firstPeriodTo,
                                LocalDate lastPeriodTo, int withLiquidation, int alreadyImported,
                                List<UnmatchedKiosk> unmatched, List<DateConflict> conflicts, List<String> warnings,
                                List<String> unmatchedAccountants) {
    }

    public record ImportResult(int history, int liquidations, ImportPreview preview) {
    }

    /** Usuario con rol CONTABILIDAD. */
    public record Person(Long userId, String name) {
    }

    /** {@code countNotes}: «Notas / áreas para corregir» que contabilidad anota al revisar el conteo físico. */
    public record LiquidationRow(Long id, Long locationId, String locationName, Long supervisorUserId,
                                 String supervisorName, LocalDate periodFrom, LocalDate periodTo, String accountant,
                                 LocalDate correctionsDue, LocalDate liquidationDate, int liquidationDays,
                                 Integer areasToFix, String greenNote, String status, String note, String source,
                                 List<Person> accountants, Long countId, String countStatus, String reviewedBy,
                                 LocalDate reviewedAt, String closedBy, LocalDate closedAt, String countObservations,
                                 String countNotes) {
    }

    public record LiquidationGroup(Long supervisorUserId, String supervisorName, List<LiquidationRow> rows) {
    }

    /** Conteo ya hecho que todavía no tiene fecha de liquidación. */
    public record PendingCount(Long locationId, String locationName, Long supervisorUserId, LocalDate periodTo,
                               String source) {
    }

    public record LiquidationWeekResponse(LocalDate weekStart, LocalDate weekEnd, List<LiquidationGroup> groups,
                                          List<PendingCount> pending, List<Person> accountants, boolean canGenerate) {
    }

    public record NonWorkingDay(LocalDate date, String reason) {
    }

    /** El año completo, para el Excel «Liquidaciones por semana». */
    public record LiquidationYearResponse(int year, List<LiquidationGroup> groups, List<NonWorkingDay> nonWorkingDays) {
    }

    public record LiquidationGenerateResponse(LiquidationWeekResponse week, List<String> warnings) {
    }
}
