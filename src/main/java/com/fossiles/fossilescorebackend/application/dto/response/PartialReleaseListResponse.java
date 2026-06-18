package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartialReleaseListResponse {
    private Long productionOrderId;
    private String productionOrderCode;
    private List<PartialReleaseResponse> releases;
    private List<PartialReleaseLineResponse> orderItemAvailability;
}
