package com.fossiles.fossilescorebackend.application.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Crea una distribución de reposición a partir de la sugerencia ya revisada por el usuario: un envío confirmado por
 * kiosco con lo que hay en bodega y, si falta, un envío pendiente de producción con el faltante.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskReplenishmentDistributionRequest {

    /** Días de venta con los que se calculó la sugerencia. */
    private Integer windowDays;
    /** Fecha de la distribución; hoy si no viene. */
    private LocalDate distributionDate;
    private String description;
    private List<Kiosk> kiosks;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Kiosk {
        private Long locationId;
        private String notes;
        private List<Line> lines;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Line {
        private Long productId;
        private Long colorId;
        private String size;
        private String hardwareCondition;
        /** Lo que se manda ahora (puede venir ajustado a mano). */
        private BigDecimal quantity;
        /** Lo que propuso el cálculo para esta línea. */
        private BigDecimal suggestedQuantity;
        /** Lo que no alcanza en bodega: queda en el envío pendiente de producción. */
        private BigDecimal shortage;
    }
}
