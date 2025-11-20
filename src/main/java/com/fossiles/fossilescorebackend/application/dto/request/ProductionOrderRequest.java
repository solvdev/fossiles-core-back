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
public class ProductionOrderRequest {
    @NotBlank(message = "Code is required")
    @Size(max = 30, message = "Code must not exceed 30 characters")
    private String code;

    @NotNull(message = "Product ID is required")
    private Long productId;

    @Size(max = 50, message = "PO color must not exceed 50 characters")
    private String poColor;

    @NotNull(message = "Quantity is required")
    private Integer quantity;

    @Size(max = 255, message = "Location must not exceed 255 characters")
    private String location;

    @Size(max = 255, message = "Description must not exceed 255 characters")
    private String description;

    @Size(max = 20, message = "Status must not exceed 20 characters")
    private String status;
}

