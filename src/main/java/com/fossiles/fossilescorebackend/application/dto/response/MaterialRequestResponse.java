package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaterialRequestResponse {
    private Long id;
    private String origin;
    private Long originReferenceId;
    private String status;
    private LocalDateTime requestDate;
    private LocalDateTime approvedDate;
    private Long approvedBy;
    private LocalDateTime rejectedDate;
    private Long rejectedBy;
    private String rejectionReason;
    private String reviewComments;
    private String observations;
    private List<MaterialRequestItemResponse> items;
    private LocalDateTime createdAt;
    private Long createdBy;
    private String createdByName;
    private LocalDateTime updatedAt;
    private Long updatedBy;
    private String updatedByName;
}

