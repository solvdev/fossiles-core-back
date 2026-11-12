package com.fossiles.fossilescorebackend.application.service.promotion;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Promoción dinámica armada por bloques. Se guarda como JSON en kiosk_promotion.rule_config.
 *
 * <ul>
 *   <li>{@link Conditions} CUÁNDO aplica (días, horario, método de pago, mínimos).</li>
 *   <li>{@link Filter} A QUÉ piezas (línea, categoría, producto, color, talla, rango de precio).</li>
 *   <li>{@link Action} QUÉ descuento (porcentaje, por posición de precio, escalones, compra A lleva B...).</li>
 *   <li>{@link Limits} tope de descuento y veces por venta.</li>
 * </ul>
 * Fechas y kiosco siguen en las columnas de kiosk_promotion.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PromotionRuleConfig {

    public static final int CURRENT_VERSION = 1;

    @Builder.Default
    private Integer version = CURRENT_VERSION;

    /** Plantilla de origen (solo informativo para la pantalla). */
    private String template;

    @Builder.Default
    private Conditions conditions = new Conditions();

    @Builder.Default
    private Filter filter = new Filter();

    @Builder.Default
    private Action action = new Action();

    @Builder.Default
    private Limits limits = new Limits();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Conditions {
        /** 1 = lunes … 7 = domingo. Vacío = todos los días. */
        @Builder.Default
        private List<Integer> daysOfWeek = new ArrayList<>();
        /** "HH:mm" (hora de Guatemala). Vacío = todo el día. Si timeTo < timeFrom cruza medianoche. */
        private String timeFrom;
        private String timeTo;
        /** EFECTIVO, TARJETA, TRANSFERENCIA, MIXTO. Vacío = cualquiera. */
        @Builder.Default
        private List<String> paymentMethods = new ArrayList<>();
        /** Mínimo de piezas que entran a la promoción. */
        private Integer minQty;
        /** Mínimo en Q de las piezas que entran a la promoción. */
        private BigDecimal minAmount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Filter {
        /** DAMA, CABALLERO, UNISEX. Vacío = todas. */
        @Builder.Default
        private List<String> audiences = new ArrayList<>();
        @Builder.Default
        private List<Long> categoryIds = new ArrayList<>();
        @Builder.Default
        private List<Long> productIds = new ArrayList<>();
        @Builder.Default
        private List<Long> colorIds = new ArrayList<>();
        @Builder.Default
        private List<String> sizes = new ArrayList<>();
        private BigDecimal minUnitPrice;
        private BigDecimal maxUnitPrice;
        @Builder.Default
        private List<Long> excludeCategoryIds = new ArrayList<>();
        @Builder.Default
        private List<Long> excludeProductIds = new ArrayList<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Action {
        /**
         * PERCENT       % a cada pieza (value)
         * AMOUNT_EACH   Q menos a cada pieza (value)
         * FIXED_PRICE   cada pieza queda en Q (value)
         * POSITION      % según la posición de precio dentro de grupos (order, groupSize, positions, remainderPercent)
         * QTY_TIERS     % según cuántas piezas lleva (qtyTiers)
         * AMOUNT_TIERS  descuento según el monto de las piezas (amountTiers)
         * BUY_GET       compra N de buyFilter y M piezas del filtro llevan getPercent
         */
        private String type;
        private BigDecimal value;

        /** POSITION / BUY_GET: DESC = de la más cara a la más barata, ASC = al revés. */
        private String order;
        /** POSITION: piezas por grupo; 0 o null = todas las piezas forman un solo grupo. */
        private Integer groupSize;
        /** POSITION: % para la posición 1, 2, 3… dentro de cada grupo. */
        @Builder.Default
        private List<BigDecimal> positions = new ArrayList<>();
        /** POSITION: % para las piezas que no tienen posición (sobran del grupo o grupo incompleto). */
        private BigDecimal remainderPercent;
        /** POSITION: qué hacer con el último grupo incompleto: POSITIONS (usa las posiciones) o REMAINDER. */
        private String incompleteGroup;
        /**
         * POSITION: las posiciones solo aplican desde N piezas en total; con menos, todas llevan remainderPercent.
         * Ej. Black Friday: 1 pieza 15%; desde 2, la más barata 30% y el resto 15%.
         */
        private Integer positionsMinQty;

        @Builder.Default
        private List<QtyTier> qtyTiers = new ArrayList<>();
        @Builder.Default
        private List<AmountTier> amountTiers = new ArrayList<>();

        /** BUY_GET: piezas que hay que comprar (las que reciben descuento salen del filtro principal). */
        private Filter buyFilter;
        private Integer buyQty;
        private Integer getQty;
        private BigDecimal getPercent;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class QtyTier {
        private Integer minQty;
        private BigDecimal percent;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AmountTier {
        private BigDecimal minAmount;
        /** Usa uno de los dos: % sobre las piezas, o monto fijo en Q repartido entre ellas. */
        private BigDecimal percent;
        private BigDecimal amount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Limits {
        /** Tope del descuento total en Q. */
        private BigDecimal maxDiscount;
        /** POSITION: grupos máximos; BUY_GET: veces máximas por venta. */
        private Integer maxApplications;
    }
}
