package com.fossiles.fossilescorebackend.application.dto.onlineledger;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Libro de ventas en línea (hoja de Contabilidad): filas, cambios y cálculo de comisiones por vendedor. */
public final class OnlineSaleLedgerDtos {

    private OnlineSaleLedgerDtos() {
    }

    /** Una venta como fila del libro: datos de la venta (solo lectura) + lo que anota Contabilidad. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LedgerRow {
        // ── De la venta: no se editan desde el libro ──
        private Long id;
        private String saleNumber;
        private String customerName;
        private String address;
        private String phone;
        private Boolean packaging;
        private String paymentMethod;
        private String paymentMethodDisplay;
        private String invoiceTaxId;
        private String products;
        private String colors;
        private String sizes;
        private BigDecimal totalAmount;
        private BigDecimal shippingCost;
        private BigDecimal netAmount;
        private String carrier;
        private LocalDate saleDate;
        private String socialNetwork;
        private String email;
        private String salesperson;
        private String guideNumber;
        private String shipmentNumber;
        private String paymentAuthorization;
        private String status;
        private String observations;
        private LocalDate dispatchedDate;

        // ── De Contabilidad: editables ──
        private BigDecimal realShippingCost;
        private BigDecimal carrierCommission;
        private String accountingNote;
        private LocalDate sentDate;
        private LocalDate receivedDate;
        private String paymentForm;
        private LocalDate liquidationDate;
        private String depositSlipNumber;
        private String deliveryNote;
        private BigDecimal realDepositAmount;
        private String depositNote;
        private BigDecimal shortageAmount;
        private String shortageNote;
        private Boolean reviewed;
        private LocalDateTime accountingUpdatedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LedgerResponse {
        private LocalDate from;
        private LocalDate to;
        private String query;
        private List<LedgerRow> rows;
        /** true si la búsqueda se cortó en el máximo de filas. */
        private boolean truncated;
        private boolean canEdit;
        private BigDecimal commissionRate;
        private BigDecimal vatRate;
    }

    /** Cambio en lote: los mismos campos a varias ventas. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BulkUpdateRequest {
        private List<Long> saleIds;
        private Map<String, Object> changes;
        /** true = solo llena los campos que están vacíos (no pisa lo ya anotado). */
        private boolean onlyEmpty;
    }

    /** Cálculo de comisión de un vendedor, con las mismas líneas de la hoja «Cálculo comisiones x vendedor». */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SellerCommission {
        private String seller;
        private int salesCount;
        private BigDecimal sales;
        private int returnsCount;
        private BigDecimal returns;
        private int voidedCount;
        private BigDecimal voided;
        private BigDecimal realShipping;
        private BigDecimal carrierCommissions;
        private BigDecimal subtotal;
        private BigDecimal vat;
        private BigDecimal netSales;
        private BigDecimal commission;
        private BigDecimal shortages;
        private BigDecimal totalToPay;
    }
}
