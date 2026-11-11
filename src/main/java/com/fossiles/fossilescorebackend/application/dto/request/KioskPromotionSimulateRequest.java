package com.fossiles.fossilescorebackend.application.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Simulador de promociones: calcula con productos y precios reales sin vender nada.
 * Sirve para promociones guardadas (incluso en borrador) o para la que se está editando sin guardar.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskPromotionSimulateRequest {
    /** Promoción guardada a simular (se ignora si viene {@link #promotion}). */
    private Long promotionId;
    /** Promoción tal como está en el formulario, sin guardar. */
    private KioskPromotionRequest promotion;
    /** Kiosco para precios y reglas por kiosco; null = sin kiosco. */
    private Long kioskLocationId;
    /** Fecha y hora a simular (Guatemala); null = ahora. */
    private LocalDate date;
    /** "HH:mm". */
    private String time;
    private String paymentMethod;
    private List<Item> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        private Long productId;
        private Long colorId;
        private String size;
        private BigDecimal quantity;
        /** Precio por pieza a probar; null = precio POS del catálogo. */
        private BigDecimal unitPrice;
    }
}
