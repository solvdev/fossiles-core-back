package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.response.ExecutiveDashboardResponse;
import com.fossiles.fossilescorebackend.application.service.ExecutiveDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** Tablero gerencial (solo administradores; ver {@code ExecutiveDashboardAccessGuard}). */
@RestController
@RequestMapping("/api/executive-dashboard")
@RequiredArgsConstructor
public class ExecutiveDashboardController {

    private final ExecutiveDashboardService service;

    /**
     * @param days    ventana del periodo hasta hoy (1–366, por defecto 30); refresh=true ignora el caché de 60 s.
     * @param finFrom inicio del rango de Finanzas (por defecto el 1 del mes).
     * @param finTo   fin del rango de Finanzas; nunca pasa de ayer (por defecto ayer).
     */
    @GetMapping
    public ResponseEntity<ExecutiveDashboardResponse> get(
            @RequestParam(required = false) Integer days,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate finFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate finTo,
            @RequestParam(defaultValue = "false") boolean refresh) {
        return ResponseEntity.ok(service.get(days, finFrom, finTo, refresh));
    }

    /** Solo Finanzas, para cambiar el rango sin recargar todo el tablero. El rango termina como máximo ayer. */
    @GetMapping("/finance")
    public ResponseEntity<ExecutiveDashboardResponse.Finance> finance(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(service.finance(from, to));
    }
}
