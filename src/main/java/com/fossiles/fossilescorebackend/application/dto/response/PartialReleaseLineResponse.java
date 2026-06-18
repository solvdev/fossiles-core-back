package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartialReleaseLineResponse {
    private Long id;
    private Long productionOrderItemId;
    private Long productId;
    private String productCode;
    private String productName;
    private Long colorId;
    private String colorName;
    private Integer quantity;
    private Map<String, Integer> sizes;
    private Integer orderedTotal;
    private Map<String, Integer> orderedSizes;
    private Integer allocatedInOtherReleases;
    private Map<String, Integer> allocatedSizesInOtherReleases;
    private Integer pendingTotal;
    private Map<String, Integer> pendingSizes;
}
