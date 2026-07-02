package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartialReleaseSearchItemResponse {
    private Long id;
    private Long productionOrderId;
    private String orderCode;
    private String customerName;
    private String orderType;
    private Integer sequence;
    private String label;
    private String status;
    private Long shipmentId;
    private String shipmentNumber;
    private String shipmentStatus;
    private Integer totalUnits;
}
