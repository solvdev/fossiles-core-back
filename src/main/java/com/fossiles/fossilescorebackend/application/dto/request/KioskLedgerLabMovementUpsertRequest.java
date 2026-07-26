package com.fossiles.fossilescorebackend.application.dto.request;

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
public class KioskLedgerLabMovementUpsertRequest {
    private Long kioscoStockId;
    private KioscoMovementType movementType;
    private Integer quantity;
    private String sizeKey;
    private Integer stockBefore;
    private Integer stockAfter;
    private Long referenceId;
    private Long physicalCountId;
    private String physicalSlipNumber;
    private String reason;
    private Boolean affectsStock;
    private Long userId;
    private Long originLocationId;
    private Long destinationLocationId;
    private LocalDateTime createdAt;
}
