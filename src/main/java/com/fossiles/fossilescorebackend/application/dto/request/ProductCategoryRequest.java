package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductCategoryRequest {
    @NotBlank(message = "Code is required")
    @Size(max = 20, message = "Code must not exceed 20 characters")
    private String code;

    @Size(max = 100, message = "Name must not exceed 100 characters")
    private String name;
}

