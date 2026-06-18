package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CinchoDayWorkStatusUpdateRequest {

    @NotNull
    private LocalDate workDate;

    @NotNull
    private Long productionOrderId;

    @NotNull
    private Long productionOrderItemId;

    @NotBlank
    private String workStatus;
}
