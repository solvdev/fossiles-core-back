package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioscoStockResponse {
    private Long id;
    private Long locationId;
    private String locationCode;
    private String locationName;
    private Long productId;
    private String productCode;
    private String productName;
    private Long colorId;
    private String colorName;
    private Integer currentStock;
    /** Desglose por talla cuando aplica (cinchos, etc.). */
    private Map<String, BigDecimal> sizes;
    private Integer minimumStock;
    private boolean lowStock;
    private LocalDateTime lastUpdatedAt;
}
