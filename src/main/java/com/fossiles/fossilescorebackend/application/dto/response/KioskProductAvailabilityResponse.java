package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskProductAvailabilityResponse {
    private Long kioskId;
    private String kioskCode;
    private String kioskName;
    private Boolean available;
    private BigDecimal quantity;
}
