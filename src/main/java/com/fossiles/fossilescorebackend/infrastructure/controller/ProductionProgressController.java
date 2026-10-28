package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.response.ProductionOrderProgressResponse;
import com.fossiles.fossilescorebackend.application.service.ProductionOrderProgressService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Avance de producción para el Dashboard: piezas por etapa y tareas de cada OP activa. */
@RestController
@RequestMapping("/api/production-progress")
@RequiredArgsConstructor
public class ProductionProgressController {

    private final ProductionOrderProgressService productionOrderProgressService;

    /** Todas las OP activas (pendientes, en proceso, en QA) con sus piezas por etapa, líneas y tareas. */
    @GetMapping("/orders")
    public ResponseEntity<ProductionOrderProgressResponse> getOrderProgress() {
        return ResponseEntity.ok(productionOrderProgressService.report());
    }
}
