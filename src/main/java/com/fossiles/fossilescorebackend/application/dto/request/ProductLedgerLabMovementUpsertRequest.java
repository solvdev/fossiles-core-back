package com.fossiles.fossilescorebackend.application.dto.request;

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
public class ProductLedgerLabMovementUpsertRequest {
    /** product_inventory_location.id */
    private Long stockId;
    private String movementType;
    /** Firmada: positiva entrada, negativa salida. */
    private BigDecimal quantity;
    private String sizeLabel;
    private BigDecimal quantityBefore;
    private BigDecimal quantityAfter;
    private BigDecimal unitCost;
    private BigDecimal totalCost;
    private String referenceType;
    private Long referenceId;
    private String referenceNumber;
    private Long referenceLineId;
    private String description;
    private LocalDateTime movementDate;
    private Long createdBy;
}
