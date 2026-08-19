package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductResponse {
    private Long id;
    private String code;
    private String name;
    private Long categoryId;
    private String audienceCategory;
    private String cinchoType;
    private Boolean cinchoForKids;
    private Double prdTime;
    private Integer unitsPerTask;
    private BigDecimal salePrice;
    private BigDecimal discountedPrice;
    private BigDecimal sellerPrice;
    private String status;
    private String imageUrl;
    private BigDecimal leatherConsumption;
    private Boolean requiresMaterials;
    private LocalDateTime createdAt;
    private Long createdBy;
    private LocalDateTime updatedAt;
    private Long updatedBy;
}

