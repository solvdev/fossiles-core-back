package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.LfPackingDtos.BoxLineRequest;
import com.fossiles.fossilescorebackend.application.dto.LfPackingDtos.CreateBoxRequest;
import com.fossiles.fossilescorebackend.application.dto.LfPackingDtos.DispatchRequest;
import com.fossiles.fossilescorebackend.application.dto.LfPackingDtos.DispatchResult;
import com.fossiles.fossilescorebackend.application.dto.LfPackingDtos.OrderDetail;
import com.fossiles.fossilescorebackend.application.dto.LfPackingDtos.OrderSummary;
import com.fossiles.fossilescorebackend.application.dto.LfPackingDtos.UpdateBoxRequest;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.service.LfPackingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Cajas de armado de Bodega PT para las OPV y OPC de Luis Felipe.
 * <p>
 * {@code /api/product-distributions/**} está abierto sin login (dispositivo de bodega), pero estas rutas exigen
 * sesión de Bodega o Administración: la validación del rol vive en {@link LfPackingService}.
 */
@RestController
@RequestMapping("/api/product-distributions/lf-orders")
@RequiredArgsConstructor
public class LfPackingController {

    private final LfPackingService packingService;

    /** Pedidos LF (OPV y OPC) con su estado en bodega; busca por número de orden o de envío. */
    @GetMapping
    public ResponseEntity<List<OrderSummary>> list(@RequestParam(required = false) String q) {
        return ResponseEntity.ok(packingService.listOrders(q));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderDetail> detail(@PathVariable Long orderId)
            throws ResourceNotFoundException, BusinessException {
        return ResponseEntity.ok(packingService.getOrder(orderId));
    }

    @PostMapping("/{orderId}/boxes")
    public ResponseEntity<OrderDetail> createBox(
            @PathVariable Long orderId,
            @RequestBody(required = false) CreateBoxRequest request)
            throws ResourceNotFoundException, BusinessException {
        return ResponseEntity.ok(packingService.createBox(orderId, request));
    }

    @PutMapping("/{orderId}/boxes/{boxId}")
    public ResponseEntity<OrderDetail> updateBox(
            @PathVariable Long orderId,
            @PathVariable Long boxId,
            @RequestBody(required = false) UpdateBoxRequest request)
            throws ResourceNotFoundException, BusinessException {
        return ResponseEntity.ok(packingService.updateBox(orderId, boxId, request));
    }

    @DeleteMapping("/{orderId}/boxes/{boxId}")
    public ResponseEntity<OrderDetail> deleteBox(@PathVariable Long orderId, @PathVariable Long boxId)
            throws ResourceNotFoundException, BusinessException {
        return ResponseEntity.ok(packingService.deleteBox(orderId, boxId));
    }

    /** Cambia las piezas de una línea/talla de la caja ({@code quantity} fija, {@code delta} suma o resta). */
    @PutMapping("/{orderId}/boxes/{boxId}/lines")
    public ResponseEntity<OrderDetail> changeLine(
            @PathVariable Long orderId,
            @PathVariable Long boxId,
            @RequestBody BoxLineRequest request)
            throws ResourceNotFoundException, BusinessException {
        return ResponseEntity.ok(packingService.changeLine(orderId, boxId, request));
    }

    @PostMapping("/{orderId}/boxes/{boxId}/close")
    public ResponseEntity<OrderDetail> close(@PathVariable Long orderId, @PathVariable Long boxId)
            throws ResourceNotFoundException, BusinessException {
        return ResponseEntity.ok(packingService.closeBox(orderId, boxId));
    }

    @PostMapping("/{orderId}/boxes/{boxId}/reopen")
    public ResponseEntity<OrderDetail> reopen(@PathVariable Long orderId, @PathVariable Long boxId)
            throws ResourceNotFoundException, BusinessException {
        return ResponseEntity.ok(packingService.reopenBox(orderId, boxId));
    }

    /** Despacha juntas las cajas cerradas seleccionadas: un solo envío (ENVP / OPC-ENV) por orden. */
    @PostMapping("/{orderId}/boxes/dispatch")
    public ResponseEntity<DispatchResult> dispatch(
            @PathVariable Long orderId,
            @RequestBody DispatchRequest request)
            throws ResourceNotFoundException, BusinessException {
        return ResponseEntity.ok(packingService.dispatch(orderId, request));
    }
}
