package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class LeatherInventoryResponse {
    private Long id;
    private Long materialId;
    private String materialName;
    private String materialSku;
    private BigDecimal quantityAvailable;
    private BigDecimal totalReceived;
    private BigDecimal totalDelivered;
    private LocalDateTime updatedAt;
}

