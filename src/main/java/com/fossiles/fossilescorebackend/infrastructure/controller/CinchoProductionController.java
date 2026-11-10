package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.response.CinchoProductionResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.service.CinchoProductionService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Lecturas de cinchos en producción. Las escrituras siguen donde estaban: el estado por línea y
 * día en {@code /api/production/cincho-day-status} y el de la OP en {@code /api/production-orders}.
 */
@RestController
@RequestMapping("/api/production/cinchos")
@RequiredArgsConstructor
public class CinchoProductionController {

    private final CinchoProductionService cinchoProductionService;

    /** Cuadro de cinchos de un día del Tablero, con el estado de cada línea ese día. */
    @GetMapping("/day")
    public ResponseEntity<CinchoProductionResponse.Day> day(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) throws BusinessException {
        return ResponseEntity.ok(cinchoProductionService.day(date));
    }

    /** Listado de «Cinchos en producción»: órdenes con sus líneas, avance y resumen de las activas. */
    @GetMapping("/orders")
    public ResponseEntity<CinchoProductionResponse.Listing> orders(
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String search) throws BusinessException {
        return ResponseEntity.ok(cinchoProductionService.list(scope, source, search));
    }
}
