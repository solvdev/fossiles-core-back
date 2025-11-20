package com.fossiles.fossilescorebackend.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Task {
    private Long id;
    private String code;
    private Integer desk;
    private Double deskTime;
    private Integer numArticles;
    private Long productionOrderId;
    private String status; // pending, in_progress, completed
    private LocalDateTime createdAt;
    private Long createdBy;
    private LocalDateTime updatedAt;
    private Long updatedBy;
}

