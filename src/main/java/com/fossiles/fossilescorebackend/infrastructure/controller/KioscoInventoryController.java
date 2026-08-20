package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.request.KioscoInventoryAjusteRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioscoInventoryCambioRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioscoInventoryAnularFacturaRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioscoInventoryDevolucionClienteRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioscoInventoryDevolucionDepositoRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioscoInventoryEntradaRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioscoInventoryMermaRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioscoInventoryTrasladoRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioscoInventoryVentaRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioscoNotificationRecipientRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioscoPhysicalCountItemUpsertRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioscoPhysicalCountObservationsRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioscoPhysicalCountReviewRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioscoOpeningInventoryApplyRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioscoOpeningInventoryItemUpsertRequest;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoOpeningInventoryReportResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoOpeningInventoryStatusResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoOpeningInventorySummaryResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoConsolidatedReportResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoInventoryInitializeResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoKardexReportResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoMovementResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoNotificationRecipientResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoPhysicalCountLiveSessionResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoPhysicalCountReportResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoPhysicalCountSessionSummaryResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoShipmentReconcileResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoShipmentReconcilePreviewResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoStockResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoTrasladoBoletaResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.service.KioscoInventoryCountService;
import com.fossiles.fossilescorebackend.application.service.KioscoInventoryService;
import com.fossiles.fossilescorebackend.application.service.KioscoOpeningInventoryService;
import com.fossiles.fossilescorebackend.application.service.ProductDistributionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/kiosco-inventory")
@RequiredArgsConstructor
public class KioscoInventoryController {

    private final KioscoInventoryService kioscoInventoryService;
    private final KioscoInventoryCountService kioscoInventoryCountService;
    private final KioscoOpeningInventoryService kioscoOpeningInventoryService;
    private final ProductDistributionService productDistributionService;

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
                request.getUserId(),
                request.getSizeKey(),
                request.getHardwareCondition()
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
                request.getUserId(),
                request.getSizeKey(),
                request.getHardwareCondition()
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
                request.getUserId(),
                request.getSizeKey(),
                request.getPhysicalSlipNumber(),
                request.getReason(),
                request.getPhysicalCountId(),
                request.getHardwareCondition()
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
        return ResponseEntity.ok(kioscoInventoryService.registrarTraslado(request));
    }

    @GetMapping("/traslado/boleta")
    public ResponseEntity<KioscoTrasladoBoletaResponse> lookupTrasladoBoleta(
            @RequestParam("number") String number
    ) throws BusinessException {
        return ResponseEntity.ok(kioscoInventoryService.lookupTrasladoBoleta(number));
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
                request.getUserId(),
                request.getSizeKey(),
                request.getHardwareCondition()
        ));
    }

    @PostMapping("/{locationId}/ajuste")
    public ResponseEntity<KioscoStockResponse> registrarAjuste(
            @PathVariable Long locationId,
            @Valid @RequestBody KioscoInventoryAjusteRequest request
    ) throws BusinessException, ResourceNotFoundException {
        if (request.getQuantity() != null) {
            return ResponseEntity.ok(kioscoInventoryService.registrarAjustePorDelta(
                    locationId,
                    request.getProductId(),
                    request.getColorId(),
                    request.getQuantity(),
                    request.getDirection(),
                    request.getReason(),
                    request.getUserId(),
                    request.getSizeKey(),
                    request.getHardwareCondition()
            ));
        }
        return ResponseEntity.ok(kioscoInventoryService.registrarAjuste(
                locationId,
                request.getProductId(),
                request.getColorId(),
                request.getRealQuantity(),
                request.getRealSizes(),
                request.getReason(),
                request.getUserId(),
                request.getHardwareCondition()
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

    @GetMapping("/reporte/existencias")
    public ResponseEntity<List<KioscoStockResponse>> getStockReport(
            @RequestParam List<Long> locationIds
    ) {
        return ResponseEntity.ok(kioscoInventoryService.getStockReportByLocations(locationIds));
    }

    @GetMapping("/reporte/producto-en-kioskos")
    public ResponseEntity<List<KioscoStockResponse>> getProductAcrossKiosks(
            @RequestParam Long productId,
            @RequestParam(required = false) Long colorId
    ) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(kioscoInventoryService.getStockByProductAcrossKiosks(productId, colorId));
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

    @GetMapping("/{locationId}/reporte/kardex")
    public ResponseEntity<KioscoKardexReportResponse> getKardex(
            @PathVariable Long locationId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(kioscoInventoryService.getKardexReport(locationId, from, to));
    }

    @GetMapping("/{locationId}/reporte/kardex/movimientos")
    public ResponseEntity<List<KioscoMovementResponse>> getKardexMovimientos(
            @PathVariable Long locationId,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) Long colorId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(kioscoInventoryService.getKardexMovements(
                locationId, productId, colorId, from, to));
    }

    @PostMapping("/{locationId}/cambio")
    public ResponseEntity<KioscoInventoryService.CambioResult> registrarCambio(
            @PathVariable Long locationId,
            @Valid @RequestBody KioscoInventoryCambioRequest request
    ) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(kioscoInventoryService.registrarCambio(
                locationId,
                request.getReturnedProductId(),
                request.getReturnedColorId(),
                request.getGivenProductId(),
                request.getGivenColorId(),
                request.getQuantity(),
                request.getReferenceId(),
                request.getReason(),
                request.getUserId()
        ));
    }

    @PostMapping("/{locationId}/conteo-fisico")
    public ResponseEntity<KioscoPhysicalCountReportResponse> startConteoFisico(
            @PathVariable Long locationId,
            @RequestParam String from,
            @RequestParam String to
    ) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(kioscoInventoryCountService.startOrGetSession(locationId, from, to));
    }

    @GetMapping("/conteo-fisico/{countId}")
    public ResponseEntity<KioscoPhysicalCountReportResponse> getConteoFisico(
            @PathVariable Long countId,
            @RequestParam(required = false) String asOf
    ) throws BusinessException, ResourceNotFoundException {
        if (asOf != null && !asOf.isBlank()) {
            return ResponseEntity.ok(kioscoInventoryCountService.getSubcountReport(countId, asOf));
        }
        return ResponseEntity.ok(kioscoInventoryCountService.getReport(countId));
    }

    /** Alias de GET /conteo-fisico/{countId}?asOf= */
    @GetMapping("/conteo-fisico/{countId}/subconteo")
    public ResponseEntity<KioscoPhysicalCountReportResponse> getSubconteoFisico(
            @PathVariable Long countId,
            @RequestParam String asOf
    ) throws BusinessException, ResourceNotFoundException {
        return getConteoFisico(countId, asOf);
    }

    @PutMapping("/conteo-fisico/{countId}/items")
    public ResponseEntity<KioscoPhysicalCountReportResponse> saveConteoFisicoItems(
            @PathVariable Long countId,
            @RequestBody List<KioscoPhysicalCountItemUpsertRequest> items
    ) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(kioscoInventoryCountService.upsertItems(countId, items));
    }

    @PostMapping("/conteo-fisico/{countId}/live-session")
    public ResponseEntity<KioscoPhysicalCountLiveSessionResponse> pollConteoLiveSession(
            @PathVariable Long countId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime since
    ) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(kioscoInventoryCountService.pollLiveSession(countId, since));
    }

    @PostMapping("/conteo-fisico/{countId}/terminar")
    public ResponseEntity<KioscoPhysicalCountReportResponse> terminarConteoFisico(
            @PathVariable Long countId
    ) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(kioscoInventoryCountService.terminarConteo(countId));
    }

    @PostMapping("/conteo-fisico/{countId}/revisar")
    public ResponseEntity<KioscoPhysicalCountReportResponse> revisarConteoFisico(
            @PathVariable Long countId,
            @RequestBody(required = false) KioscoPhysicalCountReviewRequest request
    ) throws BusinessException, ResourceNotFoundException {
        String notes = request != null ? request.getNotes() : null;
        return ResponseEntity.ok(kioscoInventoryCountService.markReviewed(countId, notes));
    }

    @PutMapping("/conteo-fisico/{countId}/observations")
    public ResponseEntity<KioscoPhysicalCountReportResponse> updateConteoObservations(
            @PathVariable Long countId,
            @RequestBody(required = false) KioscoPhysicalCountObservationsRequest request
    ) throws BusinessException, ResourceNotFoundException {
        String observations = request != null ? request.getObservations() : null;
        return ResponseEntity.ok(kioscoInventoryCountService.updateObservations(countId, observations));
    }

    @GetMapping("/{locationId}/conteo-fisico/historial")
    public ResponseEntity<List<KioscoPhysicalCountSessionSummaryResponse>> getConteoFisicoHistorial(
            @PathVariable Long locationId
    ) {
        return ResponseEntity.ok(kioscoInventoryCountService.listSessions(locationId));
    }

    @PostMapping("/conteo-fisico/{countId}/cerrar")
    public ResponseEntity<KioscoPhysicalCountReportResponse> cerrarConteoFisico(
            @PathVariable Long countId
    ) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(kioscoInventoryCountService.cerrarConteo(countId));
    }

    @GetMapping("/conteo-fisico/alertas")
    public ResponseEntity<List<KioscoPhysicalCountSessionSummaryResponse>> getConteoFisicoAlertas(
            @RequestParam(required = false) Long locationId
    ) {
        return ResponseEntity.ok(kioscoInventoryCountService.listAlerts(locationId));
    }

    @GetMapping("/notificacion-destinatarios")
    public ResponseEntity<List<KioscoNotificationRecipientResponse>> getNotificationRecipients() {
        return ResponseEntity.ok(kioscoInventoryCountService.listNotificationRecipients());
    }

    @PostMapping("/notificacion-destinatarios")
    public ResponseEntity<KioscoNotificationRecipientResponse> addNotificationRecipient(
            @Valid @RequestBody KioscoNotificationRecipientRequest request
    ) throws BusinessException {
        return ResponseEntity.ok(kioscoInventoryCountService.addNotificationRecipient(request));
    }

    @DeleteMapping("/notificacion-destinatarios/{recipientId}")
    public ResponseEntity<Void> removeNotificationRecipient(
            @PathVariable Long recipientId
    ) throws ResourceNotFoundException {
        kioscoInventoryCountService.removeNotificationRecipient(recipientId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/initialize")
    public ResponseEntity<KioscoInventoryInitializeResponse> initializeStock(
            @RequestParam(required = false) Long locationId,
            @RequestParam(required = false) Long userId
    ) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(kioscoInventoryService.initializeMissingStock(locationId, userId));
    }

    @PostMapping("/{locationId}/reconcile-shipment-entries")
    public ResponseEntity<KioscoShipmentReconcileResponse> reconcileShipmentEntries(
            @PathVariable Long locationId
    ) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(productDistributionService.reconcileShipmentReceiptInventory(locationId, null));
    }

    @GetMapping("/{locationId}/reconcile-shipment-entries/preview")
    public ResponseEntity<KioscoShipmentReconcilePreviewResponse> previewReconcileShipmentEntries(
            @PathVariable Long locationId
    ) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(productDistributionService.previewShipmentReceiptInventoryReconcile(locationId, null));
    }

    @PostMapping("/{locationId}/inventario-inicial")
    public ResponseEntity<KioscoOpeningInventoryReportResponse> startInventarioInicial(
            @PathVariable Long locationId
    ) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(kioscoOpeningInventoryService.startOrGetDraft(locationId));
    }

    @GetMapping("/inventario-inicial/{id}")
    public ResponseEntity<KioscoOpeningInventoryReportResponse> getInventarioInicial(
            @PathVariable Long id
    ) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(kioscoOpeningInventoryService.getById(id));
    }

    @PutMapping("/inventario-inicial/{id}/items")
    public ResponseEntity<KioscoOpeningInventoryReportResponse> saveInventarioInicialItems(
            @PathVariable Long id,
            @RequestBody List<KioscoOpeningInventoryItemUpsertRequest> items
    ) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(kioscoOpeningInventoryService.upsertItems(id, items));
    }

    @PostMapping("/inventario-inicial/{id}/aplicar")
    public ResponseEntity<KioscoOpeningInventoryReportResponse> aplicarInventarioInicial(
            @PathVariable Long id,
            @RequestBody(required = false) KioscoOpeningInventoryApplyRequest request
    ) throws BusinessException, ResourceNotFoundException {
        return ResponseEntity.ok(kioscoOpeningInventoryService.apply(id, request));
    }

    @GetMapping("/{locationId}/inventario-inicial/estado")
    public ResponseEntity<KioscoOpeningInventoryStatusResponse> getInventarioInicialEstado(
            @PathVariable Long locationId
    ) throws ResourceNotFoundException {
        return ResponseEntity.ok(kioscoOpeningInventoryService.getStatus(locationId));
    }

    @GetMapping("/{locationId}/inventario-inicial/historial")
    public ResponseEntity<List<KioscoOpeningInventorySummaryResponse>> getInventarioInicialHistorial(
            @PathVariable Long locationId
    ) throws ResourceNotFoundException {
        return ResponseEntity.ok(kioscoOpeningInventoryService.listApplied(locationId));
    }
}
