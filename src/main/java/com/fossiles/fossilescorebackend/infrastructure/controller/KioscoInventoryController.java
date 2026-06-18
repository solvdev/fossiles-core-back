package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.KioscoInventoryAjusteRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioscoInventoryAnularFacturaRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioscoInventoryDevolucionClienteRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioscoInventoryDevolucionDepositoRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioscoInventoryEntradaRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioscoInventoryMermaRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioscoInventoryTrasladoRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioscoInventoryVentaRequest;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoConsolidatedReportResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoInventoryInitializeResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoMovementResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoStockResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.service.KioscoInventoryService;
import jakarta.validation.Valid;
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

@RestController
@RequestMapping("/api/kiosco-inventory")
@RequiredArgsConstructor
public class KioscoInventoryController {

    private final KioscoInventoryService kioscoInventoryService;

    @PostMapping("/{locationId}/entrada")
    public ResponseEntity<KioscoStockResponse> registrarEntrada(
            @PathVariable Long locationId,
            @Valid @RequestBody KioscoInventoryEntradaRequest request
    ) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(kioscoInventoryService.registrarEntrada(
                locationId,
                request.getProductId(),
                request.getColorId(),
                request.getQuantity(),
                request.getReferenceId(),
                request.getUserId()
        ));
    }

    @PostMapping("/{locationId}/venta")
    public ResponseEntity<KioscoStockResponse> registrarVenta(
            @PathVariable Long locationId,
            @Valid @RequestBody KioscoInventoryVentaRequest request
    ) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(kioscoInventoryService.registrarVenta(
                locationId,
                request.getProductId(),
                request.getColorId(),
                request.getQuantity(),
                request.getInvoiceId(),
                request.getUserId()
        ));
    }

    @PostMapping("/{locationId}/devolucion-deposito")
    public ResponseEntity<KioscoStockResponse> registrarDevolucionDeposito(
            @PathVariable Long locationId,
            @Valid @RequestBody KioscoInventoryDevolucionDepositoRequest request
    ) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(kioscoInventoryService.registrarDevolucionDeposito(
                locationId,
                request.getProductId(),
                request.getColorId(),
                request.getQuantity(),
                request.getReferenceId(),
                request.getUserId()
        ));
    }

    @PostMapping("/{locationId}/devolucion-cliente")
    public ResponseEntity<KioscoStockResponse> registrarDevolucionCliente(
            @PathVariable Long locationId,
            @Valid @RequestBody KioscoInventoryDevolucionClienteRequest request
    ) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(kioscoInventoryService.registrarDevolucionCliente(
                locationId,
                request.getProductId(),
                request.getColorId(),
                request.getQuantity(),
                request.getOriginalInvoiceId(),
                request.getApto(),
                request.getUserId()
        ));
    }

    @PostMapping("/traslado")
    public ResponseEntity<KioscoInventoryService.TrasladoResult> registrarTraslado(
            @Valid @RequestBody KioscoInventoryTrasladoRequest request
    ) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(kioscoInventoryService.registrarTraslado(
                request.getLocationOriginId(),
                request.getLocationDestinationId(),
                request.getProductId(),
                request.getColorId(),
                request.getQuantity(),
                request.getUserId()
        ));
    }

    @PostMapping("/{locationId}/merma")
    public ResponseEntity<KioscoStockResponse> registrarMerma(
            @PathVariable Long locationId,
            @Valid @RequestBody KioscoInventoryMermaRequest request
    ) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(kioscoInventoryService.registrarMerma(
                locationId,
                request.getProductId(),
                request.getColorId(),
                request.getQuantity(),
                request.getReason(),
                request.getUserId()
        ));
    }

    @PostMapping("/{locationId}/ajuste")
    public ResponseEntity<KioscoStockResponse> registrarAjuste(
            @PathVariable Long locationId,
            @Valid @RequestBody KioscoInventoryAjusteRequest request
    ) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(kioscoInventoryService.registrarAjuste(
                locationId,
                request.getProductId(),
                request.getColorId(),
                request.getRealQuantity(),
                request.getReason(),
                request.getUserId()
        ));
    }

    @PostMapping("/{locationId}/anular-factura")
    public ResponseEntity<KioscoStockResponse> anularFactura(
            @PathVariable Long locationId,
            @Valid @RequestBody KioscoInventoryAnularFacturaRequest request
    ) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(kioscoInventoryService.anularFactura(
                request.getInvoiceId(),
                locationId,
                request.getProductId(),
                request.getColorId(),
                request.getQuantity(),
                request.getReason(),
                request.getProductLeftKiosk(),
                request.getUserId()
        ));
    }

    @GetMapping("/{locationId}/stock")
    public ResponseEntity<List<KioscoStockResponse>> getStock(
            @PathVariable Long locationId
    ) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(kioscoInventoryService.getStockByLocation(locationId));
    }

    @GetMapping("/{locationId}/movimientos")
    public ResponseEntity<List<KioscoMovementResponse>> getMovimientos(
            @PathVariable Long locationId,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) Long colorId
    ) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(kioscoInventoryService.getMovements(locationId, productId, colorId));
    }

    @GetMapping("/{locationId}/stock-bajo")
    public ResponseEntity<List<KioscoStockResponse>> getStockBajo(
            @PathVariable Long locationId
    ) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(kioscoInventoryService.getLowStock(locationId));
    }

    @GetMapping("/reporte/consolidado")
    public ResponseEntity<KioscoConsolidatedReportResponse> getConsolidado() {
        return ResponseEntity.ok(kioscoInventoryService.getConsolidatedReport());
    }

    @PostMapping("/initialize")
    public ResponseEntity<KioscoInventoryInitializeResponse> initializeStock(
            @RequestParam(required = false) Long locationId,
            @RequestParam(required = false) Long userId
    ) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(kioscoInventoryService.initializeMissingStock(locationId, userId));
    }
}
