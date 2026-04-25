package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaterialRequestReviewRequest {
    @NotNull(message = "Action is required")
    private String action; // APPROVE, REJECT, COMMENT

    private Long reviewedBy;

    @Size(max = 1000, message = "Review comments must not exceed 1000 characters")
    private String reviewComments;

    @Size(max = 1000, message = "Rejection reason must not exceed 1000 characters")
    private String rejectionReason;
}

