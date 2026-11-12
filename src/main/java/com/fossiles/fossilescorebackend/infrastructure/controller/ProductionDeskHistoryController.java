package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.response.DeskHistoryResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.service.ProductionDeskHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** Historial de mesas: tareas, órdenes y tiempos por mesa en un rango de fechas. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/tasks/desk-history")
public class ProductionDeskHistoryController {

    private final ProductionDeskHistoryService productionDeskHistoryService;

    @GetMapping
    public ResponseEntity<DeskHistoryResponse> history(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) throws BusinessException {
        return ResponseEntity.ok(productionDeskHistoryService.history(from, to));
    }
}
