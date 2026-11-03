package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductDistributionResponse {
    private Long id;
    private String distributionNumber;
    private LocalDate distributionDate;
    private String status;
    private String description;
    /** MANUAL o REPOSICION. Campo nuevo: los clientes que no lo conocen lo ignoran. */
    private String origin;
    /** Días de venta usados cuando la distribución viene de la sugerencia de reposición. */
    private Integer replenishmentWindowDays;
    private Integer shipmentCount;
    private List<ProductShipmentResponse> shipments;
    private Long productionOrderId;
    private String productionOrderCode;
    /** Todas las OP ligadas a la distribución (p. ej. la OPK sugerida por el faltante de una reposición). */
    private List<LinkedProductionOrder> linkedProductionOrders;
    private LocalDateTime createdAt;
    private Long createdBy;
    private String createdByName;
    private LocalDateTime updatedAt;
    private Long updatedBy;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LinkedProductionOrder {
        private Long id;
        private String code;
        private String orderType;
        private String status;
    }
}
