package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.ProductionOrderGroupRequest;
import com.fossiles.fossilescorebackend.application.dto.response.ProductionOrderGroupResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.service.ProductionOrderGroupService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * OP consolidada del Centro de Producción: varias OPV (o varias OPC) de Luis Felipe que se
 * producen como una sola. Las órdenes originales conservan su envío y su cobro.
 */
@RestController
@RequestMapping("/api/production-order-groups")
@RequiredArgsConstructor
public class ProductionOrderGroupController {

    private final ProductionOrderGroupService service;

    /** Consolidadas activas, con sus órdenes. */
    @GetMapping
    public ResponseEntity<List<ProductionOrderGroupResponse>> listActive() {
        return ResponseEntity.ok(service.listActive());
    }

    /** OPV u OPC abiertas de Luis Felipe que aún no están en ninguna consolidada. */
    @GetMapping("/candidates")
    public ResponseEntity<List<ProductionOrderGroupResponse.OrderRef>> candidates(
            @RequestParam(required = false) String family) {
        return ResponseEntity.ok(service.candidates(family));
    }

    /** Detalle con los productos sumados (para la hoja). */
    @GetMapping("/{id:\\d+}")
    public ResponseEntity<ProductionOrderGroupResponse> get(@PathVariable Long id) throws ResourceNotFoundException {
        return ResponseEntity.ok(service.get(id));
    }

    @PostMapping
    public ResponseEntity<ProductionOrderGroupResponse> create(@RequestBody ProductionOrderGroupRequest request)
            throws BusinessException {
        return ResponseEntity.ok(service.create(request));
    }

    /** Desagrupa: las órdenes vuelven a planificarse sueltas; las tareas ya creadas no cambian. */
    @PostMapping("/{id:\\d+}/dissolve")
    public ResponseEntity<ProductionOrderGroupResponse> dissolve(@PathVariable Long id)
            throws ResourceNotFoundException, BusinessException {
        return ResponseEntity.ok(service.dissolve(id));
    }
}
