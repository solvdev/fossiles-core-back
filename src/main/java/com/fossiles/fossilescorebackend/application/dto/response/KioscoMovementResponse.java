package com.fossiles.fossilescorebackend.application.dto.response;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoMovementType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioscoMovementResponse {
    private Long id;
    private Long kioscoStockId;
    private Long locationId;
    private String locationName;
    private Long productId;
    private String productCode;
    private String productName;
    private Long colorId;
    private String colorName;
    private KioscoMovementType movementType;
    private Integer quantity;
    private Integer stockBefore;
    private Integer stockAfter;
    private Long referenceId;
    private String reason;
    private Boolean affectsStock;
    private Long userId;
    private String username;
    private Long originLocationId;
    private String originLocationName;
    private Long destinationLocationId;
    private String destinationLocationName;
    private LocalDateTime createdAt;
}
