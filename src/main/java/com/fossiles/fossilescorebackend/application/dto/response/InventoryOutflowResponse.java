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
public class InventoryOutflowResponse {
    private Long id;
    private String ticketNumber;
    private Long materialId;
    private Long fromLocationId;
    private String fromLocationName;
    private BigDecimal quantity;
    private String reason;
    private String referenceType;
    private Long referenceId;
    private String referenceNumber;
    private LocalDateTime createdAt;
}
