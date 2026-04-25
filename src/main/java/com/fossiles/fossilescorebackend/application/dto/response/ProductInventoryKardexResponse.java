package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductInventoryKardexResponse {
    private Long id;
    private Long productId;
    private String productCode;
    private String productName;
    private Long locationId;
    private String locationCode;
    private String locationName;
    private String movementType;
    private BigDecimal quantity;
    private BigDecimal quantityBefore;
    private BigDecimal quantityAfter;
    private BigDecimal unitCost;
    private BigDecimal totalCost;
    private String referenceType;
    private Long referenceId;
    private String referenceNumber;
    private String description;
    private LocalDateTime movementDate;
    private LocalDateTime createdAt;
    private Long createdBy;
    
    // Campos para método FIFO - Entradas
    private BigDecimal cantidadEntrada;
    private BigDecimal costoUnitarioEntrada;
    private BigDecimal totalEntrada;
    
    // Campos para método FIFO - Salidas
    private BigDecimal cantidadSalida;
    private BigDecimal costoUnitarioSalida;
    private BigDecimal totalSalida;
    
    // Información sobre lotes FIFO en el saldo
    private java.util.List<FifoBatchInfo> lotesFifo;
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FifoBatchInfo {
        private LocalDateTime fechaEntrada;
        private BigDecimal cantidad;
        private BigDecimal costoUnitario;
        private BigDecimal total;
    }
}

