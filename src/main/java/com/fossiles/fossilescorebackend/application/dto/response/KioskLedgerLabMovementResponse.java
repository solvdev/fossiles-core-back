package com.fossiles.fossilescorebackend.application.dto.response;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoMovementType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskLedgerLabMovementResponse {
    private Long id;
    private Long kioscoStockId;
    private Long locationId;
    private String locationName;
    private Long productId;
    private String productCode;
    private String productName;
    private Long colorId;
    private String colorName;
    private String hardwareCondition;
    private KioscoMovementType movementType;
    private Integer quantity;
    private String sizeKey;
    private Integer stockBefore;
    private Integer stockAfter;
    /** Saldo de la talla (visual). stockBefore/stockAfter siguen siendo totales del color. */
    private Integer sizeStockBefore;
    private Integer sizeStockAfter;
    private Long referenceId;
    private String referenceType;
    private String referenceNumber;
    /** Resumen legible: venta, envío, etc. */
    private String referenceSummary;
    private Long physicalCountId;
    private String physicalSlipNumber;
    private String reason;
    private Boolean affectsStock;
    private Long userId;
    private String username;
    private Long originLocationId;
    private String originLocationName;
    private String originLocationCode;
    private Long destinationLocationId;
    private String destinationLocationName;
    private String destinationLocationCode;
    private LocalDateTime createdAt;
}
