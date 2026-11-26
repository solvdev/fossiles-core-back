package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Desglose de stock de un envío antes de enviarlo: cuánto hay en Devoluciones y en Bodega PT por línea, de cuál
 * sale sin autorización y si el faltante se puede cubrir tomando de la otra cubeta.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShipmentDispatchStockCheckResponse {
    private Long shipmentId;
    private String shipmentNumber;
    private Boolean directKioskShipment;
    private Boolean distributionShipment;
    /** RETURNS_ONLY, PT_ONLY, PT_FIRST o RETURNS_FIRST. */
    private String dispatchSource;
    private String primaryWarehouseLabel;
    /** Cubeta de la que se puede tomar el faltante con autorización; null si el envío no tiene esa opción. */
    private String fallbackWarehouseLabel;
    private Boolean fallbackAvailable;
    /** Alguna línea no alcanza con la bodega principal. */
    private Boolean hasShortage;
    /** Con la autorización alcanzan todas las líneas. */
    private Boolean canSendWithFallback;
    private List<Line> lines;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Line {
        private Long detailId;
        private Long productId;
        private String productCode;
        private String productName;
        private Long colorId;
        private String colorName;
        private String size;
        private BigDecimal requiredQuantity;
        private BigDecimal returnsAvailable;
        private BigDecimal ptAvailable;
        private BigDecimal primaryAvailable;
        /** Disponible en la cubeta de respaldo; null si la línea no puede salir de ella (cinchos). */
        private BigDecimal fallbackQuantity;
        /** Lo que falta con la bodega principal. */
        private BigDecimal shortageQuantity;
        /** Lo que sigue faltando aun tomando de la otra cubeta. */
        private BigDecimal shortageAfterFallback;
    }
}
