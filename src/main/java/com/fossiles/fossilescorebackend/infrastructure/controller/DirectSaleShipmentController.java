package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.DirectSaleShipmentRequest;
import com.fossiles.fossilescorebackend.application.dto.response.DirectSaleShipmentResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.service.DirectSaleShipmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Envíos directos a clientes de Luis Felipe (sin OP). Se crean aquí (confirmados) y se envían con el
 * PUT /api/product-distributions/shipments/{id}/send de siempre, que descarga la bodega y genera el cargo.
 * Fuera de /api/product-distributions/** para que exija sesión.
 */
@RestController
@RequestMapping("/api/direct-sale-shipments")
@RequiredArgsConstructor
public class DirectSaleShipmentController {

    private final DirectSaleShipmentService directSaleShipmentService;

    @PostMapping
    public ResponseEntity<DirectSaleShipmentResponse> create(@RequestBody DirectSaleShipmentRequest request)
            throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(directSaleShipmentService.create(request));
    }

    @GetMapping
    public ResponseEntity<List<DirectSaleShipmentResponse>> list(
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) Integer days) {
        return ResponseEntity.ok(directSaleShipmentService.list(customerId, days));
    }

    @GetMapping("/{id}")
    public ResponseEntity<DirectSaleShipmentResponse> get(@PathVariable Long id)
            throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(directSaleShipmentService.get(id));
    }
}
