package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Ventas en línea pedidas un día y que deben despacharse al siguiente.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OplDispatchSummaryResponse {
    /** Día en que se pidió (ayer, por defecto). */
    private LocalDate saleDate;
    /** Día en que debe salir (hoy, por defecto). */
    private LocalDate dispatchDate;
    private int saleCount;
    private int lineCount;
    private int unitCount;
    /** Ventas que armó (o armará) orden OPL. */
    private int oplSaleCount;
    /** Ventas que salen de stock/bodega, sin OPL. */
    private int stockSaleCount;
    private int excludedCount;
    @Builder.Default
    private List<Sale> sales = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Sale {
        private Long onlineSaleId;
        private String saleNumber;
        private String customerName;
        private String phone;
        private String address;
        private String status;
        private String paymentMethod;
        private String shippingCarrier;
        private Long productionOrderId;
        private String productionOrderCode;
        /** true si la venta tiene o genera OPL. */
        private boolean generatesOpl;
        /** OPL, STOCK, MIXTA, ANULADA, PENDIENTE */
        private String dispatchKind;
        @Builder.Default
        private List<Line> lines = new ArrayList<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Line {
        private String productCode;
        private String productName;
        private String colorName;
        private String size;
        private int quantity;
        private String fulfillmentRoute;
    }
}
