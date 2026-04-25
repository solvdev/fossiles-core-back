package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.response.WarehouseOrderViewResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.service.CustomerShipmentDispatchService;
import com.fossiles.fossilescorebackend.application.service.WarehouseOrderViewAssembler;
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
}
