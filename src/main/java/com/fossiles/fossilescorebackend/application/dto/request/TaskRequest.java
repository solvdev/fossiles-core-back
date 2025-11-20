package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.NotBlank;
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
public class TaskRequest {
    @NotBlank(message = "Code is required")
    @Size(max = 30, message = "Code must not exceed 30 characters")
    private String code;

    private Integer desk;

    private Double deskTime;

    private Integer numArticles;

    @NotNull(message = "Production order ID is required")
    private Long productionOrderId;

    @Size(max = 20, message = "Status must not exceed 20 characters")
    private String status;
}

