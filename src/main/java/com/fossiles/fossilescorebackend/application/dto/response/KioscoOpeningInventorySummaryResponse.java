package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioscoOpeningInventorySummaryResponse {
    private Long id;
    private Long locationId;
    private String status;
    private String notes;
    private Long createdBy;
    private String createdByName;
    private Long appliedBy;
    private String appliedByName;
    private LocalDateTime appliedAt;
    private LocalDateTime createdAt;
    private int itemCount;
}
