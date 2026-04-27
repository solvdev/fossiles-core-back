package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.response.CustomerShipmentResponse;
import com.fossiles.fossilescorebackend.application.dto.response.WarehouseOrderViewResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.service.CustomerShipmentDispatchService;
import com.fossiles.fossilescorebackend.application.service.WarehouseOrderViewAssembler;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineSaleItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineSaleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
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

    @GetMapping("/orders")
    @Transactional(readOnly = true)
    public ResponseEntity<List<WarehouseOrderViewResponse>> listOnlineOrders(
            @RequestParam(required = false) String status) {

        List<String> statuses = status != null
                ? List.of(status)
                : List.of("PENDING", "IN_PROGRESS", "COMPLETED");

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
                .map(this::toShipment)
                .collect(Collectors.toList());
        return ResponseEntity.ok(rows);
    }

    private CustomerShipmentResponse toShipment(OnlineSaleEntity sale) {
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

        return CustomerShipmentResponse.builder()
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
                .items(shipItems)
                .build();
    }
}
