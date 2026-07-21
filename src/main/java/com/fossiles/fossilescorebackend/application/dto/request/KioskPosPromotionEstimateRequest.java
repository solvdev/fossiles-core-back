package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
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
public class KioskPosPromotionEstimateRequest {
    private Long kioskLocationId;
    private Long promotionId;
    private BigDecimal manualDiscountPercent;

    @Valid
    private List<ItemRequest> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ItemRequest {
        @NotNull(message = "El producto es obligatorio.")
        private Long productId;
        private Long colorId;
        private String size;
        /** Herraje del stock kiosko: NUEVO o VIEJO. */
        private String hardwareCondition;

        @NotNull(message = "La cantidad es obligatoria.")
        @Positive(message = "La cantidad debe ser mayor a cero.")
        private BigDecimal quantity;
    }
}
