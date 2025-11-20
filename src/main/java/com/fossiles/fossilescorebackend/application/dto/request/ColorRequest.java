package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ColorRequest {
    @Size(max = 50, message = "Name must not exceed 50 characters")
    private String name;
}

