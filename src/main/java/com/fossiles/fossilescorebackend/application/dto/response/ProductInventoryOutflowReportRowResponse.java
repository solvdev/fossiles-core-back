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
public class ProductInventoryOutflowReportRowResponse {
    private Long id;
    private LocalDateTime movementDate;
    private String movementType;
    private BigDecimal quantity;
    private BigDecimal quantityBefore;
    private BigDecimal quantityAfter;

    private Long productId;
    private String productCode;
    private String productName;
    private Long colorId;
    private String colorName;

    private Long locationId;
    private String locationCode;
    private String locationName;
    private String destinationLocationName;

    private String sourceCategory;
    private String sourceLabel;

    private String referenceType;
    private Long referenceId;
    private String referenceNumber;

    private String orderType;
    private String orderCode;
    private String distributionCode;

    private String description;
}
