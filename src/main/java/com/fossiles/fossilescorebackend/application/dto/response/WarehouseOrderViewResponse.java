package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Vista para bodega de producto terminado.
 * Muestra órdenes de producción con sus items y destino de despacho.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WarehouseOrderViewResponse {
    private Long productionOrderId;
    private String productionOrderCode;
    private String orderType;
    private String status;
    private LocalDate startDate;
    private LocalDate deliveryDate;
    private String observations;
    private LocalDateTime createdAt;

    // Totals
    private int totalItems;
    private int totalQuantity;
    private int completedTasks;
    private int totalTasks;

    // Dispatch destination info
    private String dispatchType; // KIOSK_DISTRIBUTION or CUSTOMER_SHIPMENTS
    private Long distributionId;
    private String distributionNumber;

    // For CUSTOMER_SHIPMENTS: grouped by customer
    private List<CustomerShipmentResponse> customerShipments;

    // For KIOSK_DISTRIBUTION: shipments per kiosk
    private List<ProductShipmentResponse> kioskShipments;

    // All items in the order
    private List<ProductionOrderItemResponse> items;
}

