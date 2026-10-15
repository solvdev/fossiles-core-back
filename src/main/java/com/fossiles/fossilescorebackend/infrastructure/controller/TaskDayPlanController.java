package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.DayPlanRequest;
import com.fossiles.fossilescorebackend.application.dto.response.DayPlanConfirmResponse;
import com.fossiles.fossilescorebackend.application.dto.response.DayPlanOrderItemsResponse;
import com.fossiles.fossilescorebackend.application.dto.response.DayPlanOrderSummaryResponse;
import com.fossiles.fossilescorebackend.application.dto.response.DayPlanPreviewResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.DayPlanConflictException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.service.DayPlanOrderQueryService;
import com.fossiles.fossilescorebackend.application.service.DayPlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final DayPlanOrderQueryService orderQueryService;

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

    /** Paso 1: OPs con restante, solo resumen (sin ítems), en el orden del planificador. */
    @GetMapping("/orders")
    public ResponseEntity<DayPlanOrderSummaryResponse.Page> orders(
            @RequestParam(defaultValue = "ALL") String type,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size) throws BusinessException {
        return ResponseEntity.ok(orderQueryService.listOrders(type, search, page, size));
    }

    /** Ítems de UNA OP (con restante), paginados, para cargarlos al abrirla. */
    @GetMapping("/orders/{orderId}/items")
    public ResponseEntity<DayPlanOrderItemsResponse> orderItems(
            @PathVariable Long orderId,
            @RequestParam(defaultValue = "false") boolean includeCovered,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(orderQueryService.listItems(orderId, includeCovered, page, size));
    }

    /**
     * 409 con el código que el front distingue (PLAN_CHANGED, QTY_UNAVAILABLE, LOCK_TIMEOUT,
     * CAPACITY_EXCEEDED con el detalle de capacidad/planificado/exceso por día);
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
        if (!e.getCapacity().isEmpty()) {
            body.put("capacity", e.getCapacity());
        }
        body.put("preview", e.getPreview());
        body.put("status", status.value());
        return ResponseEntity.status(status).body(body);
    }
}
