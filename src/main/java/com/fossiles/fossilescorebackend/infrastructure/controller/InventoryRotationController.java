package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.response.InventoryRotationResponse;
import com.fossiles.fossilescorebackend.application.service.InventoryRotationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Reporte de rotación y cobertura de inventario (Reportes &gt; Inventarios). Solo lectura. */
@RestController
@RequestMapping("/api/inventory-rotation")
@RequiredArgsConstructor
public class InventoryRotationController {

    private final InventoryRotationService inventoryRotationService;

    @GetMapping("/products")
    public ResponseEntity<InventoryRotationResponse> products(
            @RequestParam(required = false) Integer windowDays,
            @RequestParam(required = false) List<Long> locationIds
    ) {
        return ResponseEntity.ok(inventoryRotationService.products(windowDays, locationIds));
    }

    @GetMapping("/materials")
    public ResponseEntity<InventoryRotationResponse> materials(
            @RequestParam(required = false) Integer windowDays
    ) {
        return ResponseEntity.ok(inventoryRotationService.materials(windowDays));
    }
}
