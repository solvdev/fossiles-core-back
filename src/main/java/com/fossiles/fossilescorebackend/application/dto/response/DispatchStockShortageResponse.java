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
public class DispatchStockShortageResponse {
    private Long productId;
    private String productCode;
    private String productName;
    private Long colorId;
    private String size;
    private BigDecimal requiredQuantity;
    private BigDecimal availableQuantity;
    private BigDecimal shortageQuantity;
}
