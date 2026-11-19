package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.response.ExecutiveDashboardResponse;
import com.fossiles.fossilescorebackend.application.service.ExecutiveDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Tablero gerencial (solo administradores; ver {@code ExecutiveDashboardAccessGuard}). */
@RestController
@RequestMapping("/api/executive-dashboard")
@RequiredArgsConstructor
public class ExecutiveDashboardController {

    private final ExecutiveDashboardService service;

    /** @param days ventana del periodo hasta hoy (1–366, por defecto 30); refresh=true ignora el caché de 60 s. */
    @GetMapping
    public ResponseEntity<ExecutiveDashboardResponse> get(
            @RequestParam(required = false) Integer days,
            @RequestParam(defaultValue = "false") boolean refresh) {
        return ResponseEntity.ok(service.get(days, refresh));
    }
}
