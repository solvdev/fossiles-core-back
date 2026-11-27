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
                                LocalDate countedOn, Long countId, String countStatus, LocalDate actualDate,
                                String changeReason) {
    }

    /**
     * Una visita en «Tu calendario de la semana». {@code actualDate}: fecha en que llegó (o llegará), si la cambió;
     * {@code periodFrom}/{@code periodTo}: período del conteo que le toca.
     */
    public record MyVisit(Long id, LocalDate visitDate, LocalDate actualDate, String changeReason, Long locationId,
                          String locationName, String regionLabel, String kind, int daySeq, int dayTotal,
                          LocalDate periodFrom, LocalDate periodTo, Long countId, String countStatus) {
    }

    /**
     * {@code supervisor}: hay un calendario que mostrar (si no, no se muestra nada). Quien tiene permiso del
     * calendario y no es supervisora ve el de la que elija ({@code supervisors}) con {@code readOnly}.
     */
    public record MyWeekResponse(LocalDate weekStart, LocalDate weekEnd, LocalDate today, boolean supervisor,
                                 List<MyVisit> visits, Long supervisorUserId, boolean readOnly,
                                 List<SupervisorResponse> supervisors) {
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
