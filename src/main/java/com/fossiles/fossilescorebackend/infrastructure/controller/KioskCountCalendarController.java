package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.response.KioskCountCalendarDtos.MyWeekResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskCountCalendarDtos.CalendarResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskCountCalendarDtos.GenerateResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.service.KioskCountCalendarService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** Contabilidad > Calendario: visitas mensuales de conteo físico a kioscos. */
@RestController
@RequestMapping("/api/kiosk-count-calendar")
@RequiredArgsConstructor
public class KioskCountCalendarController {

    private final KioskCountCalendarService service;

    /** {@code weekStart}: cualquier día de la semana a generar; {@code month}: mes que se está viendo. */
    public record GenerateRequest(@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart,
                                  List<Long> supervisorUserIds, Boolean redo, String month) {
    }

    public record UpdateVisitRequest(@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate visitDate,
                                     String status, String note, Long locationId, String month) {
    }

    public record AddVisitRequest(Long locationId, @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate visitDate,
                                  String note, String month) {
    }

    public record BlockedDayRequest(@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, String reason) {
    }

    @GetMapping
    public ResponseEntity<CalendarResponse> get(@RequestParam(required = false) String month,
                                                @RequestParam(required = false) Long supervisorUserId)
            throws BusinessException {
        return ResponseEntity.ok(service.getCalendar(month, supervisorUserId));
    }

    @PostMapping("/generate")
    public ResponseEntity<GenerateResponse> generate(@RequestBody GenerateRequest request) throws BusinessException {
        return ResponseEntity.ok(service.generateWeek(request.weekStart(), request.supervisorUserIds(),
                Boolean.TRUE.equals(request.redo()), request.month()));
    }

    /** Aprueba el borrador de la semana: el calendario de esa semana queda listo. */
    @PostMapping("/approve")
    public ResponseEntity<GenerateResponse> approve(@RequestBody GenerateRequest request) throws BusinessException {
        return ResponseEntity.ok(service.approveWeek(request.weekStart(), request.supervisorUserIds(), request.month()));
    }

    /** Descarta el borrador de la semana. */
    @PostMapping("/discard")
    public ResponseEntity<GenerateResponse> discard(@RequestBody GenerateRequest request) throws BusinessException {
        return ResponseEntity.ok(service.discardDraft(request.weekStart(), request.supervisorUserIds(), request.month()));
    }

    @PostMapping("/visits")
    public ResponseEntity<CalendarResponse> addVisit(@RequestBody AddVisitRequest request) throws BusinessException {
        return ResponseEntity.ok(service.addVisit(request.locationId(), request.visitDate(), request.note(), request.month()));
    }

    @PatchMapping("/visits/{id}")
    public ResponseEntity<CalendarResponse> updateVisit(@PathVariable Long id, @RequestBody UpdateVisitRequest request)
            throws BusinessException {
        return ResponseEntity.ok(service.updateVisit(id, request.visitDate(), request.status(), request.note(),
                request.locationId(), request.month()));
    }

    public record ChangeMyDateRequest(@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, String reason) {
    }

    /** «Tu calendario de la semana»: solo las visitas de quien está conectada. */
    @GetMapping("/my-week")
    public ResponseEntity<MyWeekResponse> myWeek(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate week)
            throws BusinessException {
        return ResponseEntity.ok(service.getMyWeek(week));
    }

    @PostMapping("/my-week/visits/{id}/date")
    public ResponseEntity<MyWeekResponse> changeMyDate(@PathVariable Long id, @RequestBody ChangeMyDateRequest request)
            throws BusinessException {
        return ResponseEntity.ok(service.changeMyVisitDate(id, request.date(), request.reason()));
    }

    @PostMapping("/blocked-days")
    public ResponseEntity<CalendarResponse> addBlockedDay(@RequestBody BlockedDayRequest request)
            throws BusinessException {
        return ResponseEntity.ok(service.addBlockedDay(request.date(), request.reason()));
    }

    @DeleteMapping("/blocked-days")
    public ResponseEntity<CalendarResponse> removeBlockedDay(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) throws BusinessException {
        return ResponseEntity.ok(service.removeBlockedDay(date));
    }
}
