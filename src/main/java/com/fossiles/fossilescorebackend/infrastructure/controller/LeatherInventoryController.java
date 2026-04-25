package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.LeatherMovementRequest;
import com.fossiles.fossilescorebackend.application.dto.response.LeatherInventoryResponse;
import com.fossiles.fossilescorebackend.application.dto.response.LeatherMovementResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.service.LeatherInventoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/leather")
@RequiredArgsConstructor
public class LeatherInventoryController {

    private final LeatherInventoryService service;

    // ─── Inventario ──────────────────────────────────────────────────

    @GetMapping("/inventory")
    public ResponseEntity<List<LeatherInventoryResponse>> getInventory() {
        return ResponseEntity.ok(service.getAllInventory());
    }

    @GetMapping("/inventory/{materialId}")
    public ResponseEntity<LeatherInventoryResponse> getInventoryByMaterial(
            @PathVariable Long materialId) throws ResourceNotFoundException {
        return ResponseEntity.ok(service.getInventoryByMaterial(materialId));
    }

    // ─── Movimientos ─────────────────────────────────────────────────

    @GetMapping("/movements")
    public ResponseEntity<List<LeatherMovementResponse>> getMovements(
            @RequestParam(required = false) Long materialId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        if (materialId != null) {
            return ResponseEntity.ok(service.getMovementsByMaterial(materialId));
        }
        if (from != null && to != null) {
            return ResponseEntity.ok(service.getMovementsByDateRange(from, to));
        }
        return ResponseEntity.ok(service.getAllMovements());
    }

    @GetMapping("/kardex/{materialId}")
    public ResponseEntity<List<LeatherMovementResponse>> getKardex(
            @PathVariable Long materialId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(service.getKardex(materialId, from, to));
    }

    @GetMapping("/movements/production-order/{productionOrderId}")
    public ResponseEntity<List<LeatherMovementResponse>> getByProductionOrder(
            @PathVariable Long productionOrderId) {
        return ResponseEntity.ok(service.getMovementsByProductionOrder(productionOrderId));
    }

    // ─── Inicializar inventario faltante ────────────────────────────

    @PostMapping("/inventory/initialize")
    public ResponseEntity<Map<String, Object>> initializeMissing() {
        int created = service.initializeMissingLeatherInventory();
        return ResponseEntity.ok(Map.of("createdCount", created));
    }

    // ─── Recepción (compra) ──────────────────────────────────────────

    @PostMapping("/reception")
    public ResponseEntity<LeatherMovementResponse> createReception(
            @RequestBody LeatherMovementRequest request) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(service.createReception(request));
    }

    // ─── Entrega a Producción ────────────────────────────────────────

    @PostMapping("/delivery")
    public ResponseEntity<LeatherMovementResponse> createDelivery(
            @RequestBody LeatherMovementRequest request) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(service.createDelivery(request));
    }

    // ─── Editar movimiento (datos descriptivos) ──────────────────────

    @PutMapping("/movements/{id}")
    public ResponseEntity<LeatherMovementResponse> updateMovement(
            @PathVariable Long id,
            @RequestBody LeatherMovementRequest request) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(service.updateMovement(id, request));
    }

    // ─── Anular movimiento ───────────────────────────────────────────

    @PostMapping("/movements/{id}/cancel")
    public ResponseEntity<LeatherMovementResponse> cancelMovement(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body) throws BusinessException, ResourceNotFoundException {
        String reason = body != null ? body.get("reason") : null;
        return ResponseEntity.ok(service.cancelMovement(id, reason));
    }
}

