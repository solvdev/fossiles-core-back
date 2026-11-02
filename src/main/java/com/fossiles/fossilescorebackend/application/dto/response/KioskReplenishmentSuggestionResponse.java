package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Sugerencia de reposición por kiosco calculada con su venta y sus envíos recientes. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskReplenishmentSuggestionResponse {

    /** Días de venta analizados. */
    private int windowDays;
    private LocalDate since;
    private List<Kiosk> kiosks;
    /** Unidades que se sugerían pero no alcanzan en Bodega PT + Devoluciones. */
    private int totalShortage;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Kiosk {
        private Long locationId;
        private String code;
        private String name;
        /** A, B o C. */
        private String category;
        /** FINANZAS si viene de kiosk_site.sales_category; CALCULADA si se asignó por ranking de venta. */
        private String categorySource;
        private BigDecimal unitsSold;
        private int totalSuggested;
        private int totalShortage;
        private List<Line> lines;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Line {
        private Long productId;
        private String productCode;
        private String productName;
        private Long colorId;
        private String colorName;
        private String size;
        private String hardwareCondition;
        private int stock;
        private int inTransit;
        private BigDecimal sold;
        private BigDecimal dailyRate;
        private int min;
        private int max;
        /** Lo que pide la regla de la categoría. */
        private int need;
        /** Lo que se puede mandar con el stock de bodega (≤ need). */
        private int suggested;
        private int shortage;
        private boolean topSeller;
        private boolean base;
        private String reason;
    }
}
