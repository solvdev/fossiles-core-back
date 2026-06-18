package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class DeskCountUpdateRequest {
    @Min(1)
    @Max(32)
    private Integer numDesks;
}

