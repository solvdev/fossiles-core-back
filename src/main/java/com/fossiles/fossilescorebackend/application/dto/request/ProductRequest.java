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
public class ProductRequest {
    @NotBlank(message = "Code is required")
    @Size(max = 30, message = "Code must not exceed 30 characters")
    private String code;

    @Size(max = 150, message = "Name must not exceed 150 characters")
    private String name;

    @NotNull(message = "Category ID is required")
    private Long categoryId;

    @NotNull(message = "UOM ID is required")
    private Long uomId;

    private Double prdTime;

    @Size(max = 20, message = "Status must not exceed 20 characters")
    private String status;
}

