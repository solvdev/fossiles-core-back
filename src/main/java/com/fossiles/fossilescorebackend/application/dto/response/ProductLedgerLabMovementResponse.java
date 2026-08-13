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
public class ProductLedgerLabMovementResponse {
    private Long id;
    private Long stockId;
    private Long locationId;
    private String locationCode;
    private String locationName;
    private Long productId;
    private String productCode;
    private String productName;
    private Long colorId;
    private String colorName;
    private String movementType;
    private BigDecimal quantity;
    private String sizeLabel;
    private BigDecimal quantityBefore;
    private BigDecimal quantityAfter;
    /** Saldo de la talla (visual). quantityBefore/After son totales del color. */
    private BigDecimal sizeStockBefore;
    private BigDecimal sizeStockAfter;
    private BigDecimal unitCost;
    private BigDecimal totalCost;
    private String referenceType;
    private Long referenceId;
    private String referenceNumber;
    private Long referenceLineId;
    private String description;
    private LocalDateTime movementDate;
    private LocalDateTime createdAt;
    private Long createdBy;
    private String username;
}
