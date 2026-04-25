package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskPosContextResponse {
    private Long userId;
    private String username;
    private String fullName;
    private Boolean admin;
    private Long kioskId;
    private String kioskCode;
    private String kioskName;
    private List<KioskOption> kiosks;
    private List<InventoryItem> inventory;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KioskOption {
        private Long kioskId;
        private String kioskCode;
        private String kioskName;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InventoryItem {
        private Long productId;
        private String productCode;
        private String productName;
        private String productImageUrl;
        private Long colorId;
        private String colorName;
        private BigDecimal quantity;
        private BigDecimal suggestedUnitPrice;
    }
}
