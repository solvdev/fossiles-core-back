package com.fossiles.fossilescorebackend.application.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartialReleaseLineRequest {
    private Long productionOrderItemId;
    private Integer quantity;
    /** Tallas → cantidad (cinchos). */
    private Map<String, Integer> sizes;
}
