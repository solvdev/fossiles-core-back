package com.fossiles.fossilescorebackend.application.dto.response;

import java.time.LocalDate;
import java.util.List;

/** Respuestas del calendario de visitas de conteo físico a kioscos (Contabilidad > Calendario). */
public final class KioskCountCalendarDtos {

    private KioskCountCalendarDtos() {
    }

    /**
     * {@code kind}: REGULAR (la visita del mes) o REVISIT (volver a ir). {@code countStatus}: estado del conteo físico
     * de esta visita (DRAFT, CONTADO, REVISADO, CERRADO); {@code countedOn} es su fecha de inventario («al»).
     */
    public record VisitResponse(Long id, LocalDate visitDate, Long locationId, String locationCode,
                                String locationName, Long supervisorUserId, String supervisorName,
                                String region, String regionLabel, String status, String kind, boolean manual,
                                String note, int daySeq, int dayTotal, LocalDate lastCountDate, Integer gapDays,
                                LocalDate countedOn, Long countId, String countStatus) {
    }

    public record KioskRuleResponse(Long locationId, String code, String name, Long supervisorUserId,
                                    String region, String regionLabel, LocalDate lastCountDate,
                                    int visitsPerMonth, int visitDays, String allowedWeekdays) {
    }

    public record SupervisorResponse(Long userId, String name, int kioskCount) {
    }

    public record NonWorkingDayResponse(LocalDate date, String reason, String type) {
    }

    /**
     * Una semana (lunes a viernes): las supervisoras con calendario aprobado (listo) y las que tienen un BORRADOR por
     * aprobar.
     */
    public record WeekResponse(LocalDate weekStart, LocalDate weekEnd, List<Long> generatedSupervisorIds,
                               List<Long> draftSupervisorIds, boolean allGenerated, boolean started) {
    }

    /** La próxima semana que le toca generar a una supervisora (se generan en orden). */
    public record NextWeekResponse(Long supervisorUserId, LocalDate weekStart, LocalDate weekEnd, boolean draft) {
    }

    public record CalendarResponse(String month, LocalDate today, List<SupervisorResponse> supervisors,
                                   List<KioskRuleResponse> kiosks, List<VisitResponse> visits,
                                   List<NonWorkingDayResponse> nonWorkingDays, List<WeekResponse> weeks,
                                   List<NextWeekResponse> nextWeeks, List<String> unassignedKiosks,
                                   boolean canGenerate) {
    }

    public record GenerateResponse(CalendarResponse calendar, List<String> warnings) {
    }
}
