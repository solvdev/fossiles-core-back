package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.response.CustomerShipmentResponse;
import com.fossiles.fossilescorebackend.application.dto.response.WarehouseOrderViewResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.service.CustomerShipmentDispatchService;
import com.fossiles.fossilescorebackend.application.service.OnlineSaleProductionOrderService;
import com.fossiles.fossilescorebackend.application.service.ProductionOrderWarehouseUnitService;
import com.fossiles.fossilescorebackend.application.service.WarehouseOrderViewAssembler;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineSaleItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineSaleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/public/online-warehouse")
@RequiredArgsConstructor
public class PublicOnlineWarehouseController {

    private final ProductionOrderRepository productionOrderRepository;
    private final WarehouseOrderViewAssembler warehouseOrderViewAssembler;
    private final CustomerShipmentDispatchService customerShipmentDispatchService;
    private final OnlineSaleRepository onlineSaleRepository;
    private final OnlineSaleItemRepository onlineSaleItemRepository;
    private final ProductRepository productRepository;
    private final ColorRepository colorRepository;
    private final OnlineSaleProductionOrderService onlineSaleProductionOrderService;
    private final ProductionOrderItemRepository productionOrderItemRepository;
    private final ProductionOrderWarehouseUnitService productionOrderWarehouseUnitService;

    /**
     * Detalle de una venta online para bodega / QR (incluye ENVIADO y ENTREGADO; consulta histórica).
     */
    @GetMapping("/sales/{onlineSaleId}")
    @Transactional(readOnly = true)
    public ResponseEntity<CustomerShipmentResponse> getOnlineSaleDetail(@PathVariable Long onlineSaleId)
            throws ResourceNotFoundException {
        OnlineSaleEntity sale = onlineSaleRepository.findById(onlineSaleId)
                .orElseThrow(() -> new ResourceNotFoundException("Online Sale", onlineSaleId));
        return ResponseEntity.ok(toShipment(sale, true));
    }

    @GetMapping("/orders")
    @Transactional(readOnly = true)
    public ResponseEntity<List<WarehouseOrderViewResponse>> listOnlineOrders(
            @RequestParam(required = false) String status) {

        List<String> statuses = status != null
                ? List.of(status)
                // Por defecto: solo las que están en proceso (las COMPLETED ya fueron despachadas)
                : List.of("PENDING", "IN_PROGRESS");

        List<WarehouseOrderViewResponse> responses = productionOrderRepository.findByStatusIn(statuses).stream()
                .filter(o -> "VENTA_EN_LINEA".equals(o.getOrderType()))
                .map(warehouseOrderViewAssembler::toWarehouseView)
                .collect(Collectors.toList());

        return ResponseEntity.ok(responses);
    }

    @PutMapping("/orders/{productionOrderId}/dispatch/{onlineSaleId}")
    @Transactional
    public ResponseEntity<Map<String, Object>> dispatch(
            @PathVariable Long productionOrderId,
            @PathVariable Long onlineSaleId,
            @RequestBody(required = false) Map<String, String> body)
            throws ResourceNotFoundException, BusinessException {
        Map<String, String> payload = body != null ? body : Map.of();
        return ResponseEntity.ok(customerShipmentDispatchService.dispatchCustomerShipment(
                productionOrderId, onlineSaleId, payload));
    }

    @PutMapping("/direct-sales/{onlineSaleId}/dispatch")
    @Transactional
    public ResponseEntity<Map<String, Object>> dispatchDirectSale(
            @PathVariable Long onlineSaleId,
            @RequestBody(required = false) Map<String, String> body)
            throws ResourceNotFoundException, BusinessException {
        Map<String, String> payload = body != null ? body : Map.of();
        return ResponseEntity.ok(customerShipmentDispatchService.dispatchDirectOnlineSale(onlineSaleId, payload));
    }

    /**
     * Recepción en Bodega PT de las piezas de una venta OPL (QR móvil).
     * No despacha: solo marca piezas pendientes como recibidas.
     */
    @PutMapping("/sales/{onlineSaleId}/receive-warehouse")
    public ResponseEntity<Map<String, Object>> receiveWarehouseForSale(@PathVariable Long onlineSaleId)
            throws BusinessException, ResourceNotFoundException {
        OnlineSaleEntity sale = onlineSaleRepository.findById(onlineSaleId)
                .orElseThrow(() -> new ResourceNotFoundException("Online Sale", onlineSaleId));
        if ("ENVIADO".equals(sale.getStatus()) || "ENTREGADO".equals(sale.getStatus())) {
            throw new BusinessException("Esta venta ya fue despachada. Estado actual: " + sale.getStatus());
        }
        return ResponseEntity.ok(productionOrderWarehouseUnitService.receivePendingUnitsForOnlineSale(onlineSaleId));
    }

    /**
     * Prepara venta desde inventario BODEGA_PT / Devoluciones cuando corresponde el cierre completo:
     * Ventas mixtas (DISPATCH+PRODUCE) exigen recepciones OP en PT y stock para todas las lineas;
     * legado sin rutas y con OP igual.
     */
    @PutMapping("/direct-sales/{onlineSaleId}/prepare")
    @Transactional
    public ResponseEntity<Map<String, Object>> prepareDirectSale(@PathVariable Long onlineSaleId)
            throws BusinessException {
        return ResponseEntity.ok(onlineSaleProductionOrderService.prepareDirectSaleFromInventory(onlineSaleId));
    }

    /**
     * Ventas online listas para despacho desde BODEGA_PT sin orden de producción.
     * Criterio: PRODUCIDO + NO inProductionOrder + NO ENVIADO/ENTREGADO.
     */
    @GetMapping("/direct-sales")
    @Transactional(readOnly = true)
    public ResponseEntity<List<CustomerShipmentResponse>> listDirectSales() {
        List<OnlineSaleEntity> candidates = onlineSaleRepository.findAll().stream()
                .filter(s -> Boolean.FALSE.equals(s.getInProductionOrder()))
                .filter(s -> "PRODUCIDO".equals(s.getStatus()))
                .filter(s -> !"ENVIADO".equals(s.getStatus()) && !"ENTREGADO".equals(s.getStatus()))
                .collect(Collectors.toList());

        List<CustomerShipmentResponse> rows = candidates.stream()
                .map(s -> toShipment(s, false))
                .collect(Collectors.toList());
        return ResponseEntity.ok(rows);
    }

    /**
     * Ventas pagadas (no OP) que tienen stock suficiente para prepararse desde bodega.
     * La regla de inventario revisa primero bodega de devoluciones y luego Bodega PT (mismo criterio que al preparar).
     */
    @GetMapping("/direct-sales/stock-ready")
    @Transactional(readOnly = true)
    public ResponseEntity<List<CustomerShipmentResponse>> listDirectSalesStockReady() throws BusinessException {
        List<OnlineSaleEntity> candidates = onlineSaleRepository.findEligibleForProduction().stream()
                .filter(s -> Boolean.FALSE.equals(s.getInProductionOrder()))
                .filter(s -> !"PRODUCIDO".equals(s.getStatus()))
                .filter(s -> !"ENVIADO".equals(s.getStatus()) && !"ENTREGADO".equals(s.getStatus()))
                .collect(Collectors.toList());

        // Reusar preview interno para filtrar solo las que sí tienen stock
        List<Long> ids = candidates.stream().map(OnlineSaleEntity::getId).collect(Collectors.toList());
        if (ids.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }
        @SuppressWarnings("unchecked")
        List<OnlineSaleProductionOrderService.FulfillmentPreviewRow> rows =
                (List<OnlineSaleProductionOrderService.FulfillmentPreviewRow>)
                        onlineSaleProductionOrderService.previewFulfillment(ids).get("rows");

        List<Long> readyIds = rows.stream()
                .filter(OnlineSaleProductionOrderService.FulfillmentPreviewRow::canFulfillFromInventory)
                .map(OnlineSaleProductionOrderService.FulfillmentPreviewRow::saleId)
                .collect(Collectors.toList());

        List<CustomerShipmentResponse> out = candidates.stream()
                .filter(s -> readyIds.contains(s.getId()))
                .map(s -> toShipment(s, false))
                .collect(Collectors.toList());

        return ResponseEntity.ok(out);
    }

    private CustomerShipmentResponse toShipment(OnlineSaleEntity sale, boolean includeProductionContext) {
        List<OnlineSaleItemEntity> items = onlineSaleItemRepository.findByOnlineSaleIdOrderByIdAsc(sale.getId());

        List<CustomerShipmentResponse.ShipmentItem> shipItems = (items != null && !items.isEmpty())
                ? items.stream().map(i -> {
                    ProductEntity p = i.getProductId() != null
                            ? productRepository.findById(i.getProductId()).orElse(null) : null;
                    ColorEntity c = i.getColorId() != null
                            ? colorRepository.findById(i.getColorId()).orElse(null) : null;
                    return CustomerShipmentResponse.ShipmentItem.builder()
                            .productionOrderItemId(null)
                            .productId(i.getProductId())
                            .productCode(p != null ? p.getCode() : i.getProductCode())
                            .productName(p != null ? p.getName() : i.getProductName())
                            .colorId(i.getColorId())
                            .colorName(c != null ? c.getName() : i.getColorName())
                            .quantity(i.getQuantity())
                            .size(i.getSize())
                            .build();
                }).collect(Collectors.toList())
                : List.of();

        CustomerShipmentResponse.CustomerShipmentResponseBuilder b = CustomerShipmentResponse.builder()
                .onlineSaleId(sale.getId())
                .saleNumber(sale.getSaleNumber())
                .customerName(sale.getCustomerName())
                .address(sale.getAddress())
                .phone(sale.getPhone())
                .phone2(sale.getPhone2())
                .shipmentNumber(sale.getShipmentNumber())
                .shippingCarrier(sale.getShippingCarrier())
                .guideNumber(sale.getGuideNumber())
                .paymentMethod(sale.getPaymentMethod())
                .saleStatus(sale.getStatus())
                .saleDate(sale.getSaleDate())
                .totalAmount(sale.getTotalAmount())
                .shippingCost(sale.getShippingCost())
                .packaging(sale.getPackaging())
                .items(shipItems);

        if (includeProductionContext) {
            Long productionOrderId = null;
            List<ProductionOrderItemEntity> opItems = productionOrderItemRepository.findByOnlineSaleId(sale.getId());
            if (opItems != null && !opItems.isEmpty()) {
                productionOrderId = opItems.stream()
                        .map(ProductionOrderItemEntity::getProductionOrderId)
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse(null);
            }
            b.productionOrderId(productionOrderId)
                    .inProductionOrder(Boolean.TRUE.equals(sale.getInProductionOrder()));
        }

        return b.build();
    }
}
