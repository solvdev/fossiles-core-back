package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

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
    /** Kiosko en piloto: ventas no cuentan en reportes de producción. */
    private Boolean posTestMode;
    /** Fondo inicial de caja POS al abrir turno. */
    private BigDecimal posOpeningCashAmount;
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
        private BigDecimal posOpeningCashAmount;
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
        private Long categoryId;
        private String categoryName;
        private String audienceCategory;
        private BigDecimal quantity;
        private BigDecimal suggestedUnitPrice;
        /** Cinchos y variantes por talla: talla → cantidad disponible en kiosko. */
        private Map<String, BigDecimal> sizes;
        /** NUEVO o VIEJO cuando el kiosko separa stock por herraje. */
        private String hardwareCondition;
        private String hardwareLabel;
    }
}
