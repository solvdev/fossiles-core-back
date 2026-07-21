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
public class KioscoOpeningInventoryStatusResponse {
    /** NONE, DRAFT, APLICADO */
    private String status;
    private Long draftId;
    private Long appliedId;
    private LocalDateTime appliedAt;
    private String appliedByName;
    private int draftItemCount;
}
