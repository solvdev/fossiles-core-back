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
public class InventoryKardexResponse {
    private Long id;
    private Long materialId;
    private String materialSku;
    private String materialName;
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
}

