package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ColorBatchRequest {
    @NotEmpty(message = "Color names list cannot be empty")
    private List<@Size(max = 50, message = "Each color name must not exceed 50 characters") String> names;
}

