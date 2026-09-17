package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskSalesByProductColorReportResponse {
    private LocalDate startDate;
    private LocalDate endDate;
    private Long kioskLocationId;
    private String kioskLabel;

    @Builder.Default
    private List<KioskRef> kiosks = new ArrayList<>();

    @Builder.Default
    private List<ColorRef> colors = new ArrayList<>();

    @Builder.Default
    private List<ProductRow> products = new ArrayList<>();

    private Totals totals;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KioskRef {
        private Long id;
        private String code;
        private String name;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ColorRef {
        private Long id;
        private String name;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProductRow {
        private Long productId;
        private String productCode;
        private String productName;
        private Long categoryId;
        private String categoryName;
        private String audienceCategory;
        private BigDecimal totalQuantity;
        private BigDecimal totalAmount;
        private Integer totalTickets;
        private Integer currentStock;
        private Integer colorsWithSales;
        private Integer colorsWithoutSales;

        @Builder.Default
        private List<ColorCell> colors = new ArrayList<>();

        @Builder.Default
        private List<KioskCell> kiosks = new ArrayList<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ColorCell {
        private Long colorId;
        private String colorName;
        private BigDecimal quantity;
        private BigDecimal amount;
        private Integer tickets;
        private Integer currentStock;

        @Builder.Default
        private List<KioskCell> byKiosk = new ArrayList<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KioskCell {
        private Long kioskLocationId;
        private BigDecimal quantity;
        private BigDecimal amount;
        private Integer tickets;
        private Integer currentStock;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Totals {
        private BigDecimal quantity;
        private BigDecimal amount;
        private Integer tickets;
        private Integer products;
        private Integer productsWithSales;
        private Integer productsWithoutSales;
        private Integer colorCombinations;
        private Integer colorCombinationsWithSales;
        private Integer colorCombinationsWithoutSales;
    }
}
