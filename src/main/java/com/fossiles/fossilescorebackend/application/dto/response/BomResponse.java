package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BomResponse {
    private Long id;
    private String bomName;
    private Long productId;
    private Long colorId;
    private String status;
    private Long leatherMaterialId;
    private String leatherMaterialSku;
    private String leatherMaterialName;
    private BigDecimal leatherQtyPerUnit;
    private BigDecimal totalCost; // Costo total del BOM (suma de todos los itemCost)
    private LocalDateTime createdAt;
    private Long createdBy;
    private LocalDateTime updatedAt;
    private Long updatedBy;
    private List<BomItemResponse> items;
}

