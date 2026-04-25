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
public class InventoryLocationResponse {
    private Long id;
    private Long materialId;
    private String materialSku;
    private String materialName;
    private Long locationId;
    private String locationCode;
    private String locationName;
    private BigDecimal quantity;
    private Integer materialMin; // Stock mínimo del material
    private Integer materialMax; // Stock máximo del material
    private LocalDateTime createdAt;
    private Long createdBy;
    private LocalDateTime updatedAt;
    private Long updatedBy;
}

