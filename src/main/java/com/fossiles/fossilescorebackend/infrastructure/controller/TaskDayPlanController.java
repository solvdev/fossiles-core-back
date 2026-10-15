package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.DayPlanRequest;
import com.fossiles.fossilescorebackend.application.dto.response.DayPlanConfirmResponse;
import com.fossiles.fossilescorebackend.application.dto.response.DayPlanPreviewResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.DayPlanConflictException;
import com.fossiles.fossilescorebackend.application.service.DayPlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * «Armar el día» del Centro de Producción: vista previa y confirmación de las tareas del día.
 * Controlador aparte para no engordar {@link TaskController}.
 */
@RestController
@RequestMapping("/api/tasks/organizer/day-plan")
@RequiredArgsConstructor
public class TaskDayPlanController {

    private final DayPlanService dayPlanService;

    /** Solo lectura: mide las tareas borrador y propone día y mesa orientativa. No guarda nada. */
    @PostMapping("/preview")
    public ResponseEntity<DayPlanPreviewResponse> preview(@RequestBody DayPlanRequest request)
            throws BusinessException {
        return ResponseEntity.ok(dayPlanService.preview(request));
    }

    /** Crea todas las tareas de la propuesta o ninguna (409 si la carga cambió). */
    @PostMapping("/confirm")
    public ResponseEntity<DayPlanConfirmResponse> confirm(@RequestBody DayPlanRequest request)
            throws BusinessException {
        return ResponseEntity.ok(dayPlanService.confirm(request));
    }

    /**
     * 409 con el código que el front distingue (PLAN_CHANGED, QTY_UNAVAILABLE, LOCK_TIMEOUT);
     * 422 PLAN_BLOCKED cuando la propuesta tiene tareas que no se pueden crear por otra razón
     * (tope de 5 h, fin de semana, OP cincho o en borrador…).
     */
    @ExceptionHandler(DayPlanConflictException.class)
    public ResponseEntity<Map<String, Object>> conflict(DayPlanConflictException e) {
        HttpStatus status = DayPlanConflictException.PLAN_BLOCKED.equals(e.getCode())
                ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.CONFLICT;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", e.getCode());
        body.put("message", e.getMessage());
        body.put("failures", e.getFailures().stream()
                .map(f -> Map.of("clientId", f.clientId(), "message", f.message()))
                .toList());
        body.put("preview", e.getPreview());
        body.put("status", status.value());
        return ResponseEntity.status(status).body(body);
    }
}
