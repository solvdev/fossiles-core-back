package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CinchoDayStatusEntryResponse {
    private Long productionOrderId;
    private Long productionOrderItemId;
    private Boolean delivered;
    /** PENDING | IN_PROGRESS | COMPLETED */
    private String workStatus;
}
