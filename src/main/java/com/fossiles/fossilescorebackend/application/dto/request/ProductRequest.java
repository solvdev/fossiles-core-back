package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
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

    /** DAMA, CABALLERO o UNISEX */
    @Size(max = 20, message = "Audience category must not exceed 20 characters")
    private String audienceCategory;

    /** CASUAL o REVERSIBLE para productos cincho FOSS */
    @Size(max = 20, message = "Cincho type must not exceed 20 characters")
    private String cinchoType;

    private Double prdTime;

    private BigDecimal salePrice;

    private BigDecimal discountedPrice;

    private BigDecimal sellerPrice;

    @Size(max = 20, message = "Status must not exceed 20 characters")
    private String status;

    @Size(max = 500, message = "Image URL must not exceed 500 characters")
    private String imageUrl;

    private BigDecimal leatherConsumption;

    private Boolean requiresMaterials;
}

