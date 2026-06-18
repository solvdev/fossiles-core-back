package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioscoInventoryInitializeResponse {
    private String message;
    private Integer kiosksProcessed;
    private Integer productsProcessed;
    private Integer createdCount;
    private Integer existingCount;
    private Long locationId;
}
