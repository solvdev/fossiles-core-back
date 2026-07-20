package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioscoPhysicalCountItemSyncResponse {
    private Long productId;
    private Long colorId;
    private Map<String, Integer> counts;
    private Map<String, Integer> physicalSizes;
    private Map<String, Map<String, Integer>> physicalSizesByLocation;
    private Map<String, Map<String, Integer>> hardwareLocationCounts;
    private String observation;
    private Map<String, String> sizeObservations;
    private LocalDateTime updatedAt;
    private Long updatedBy;
    private String updatedByName;
}
