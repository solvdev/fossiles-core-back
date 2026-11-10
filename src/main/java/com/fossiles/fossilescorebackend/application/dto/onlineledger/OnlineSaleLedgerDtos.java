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

    /**
     * Una venta como fila del libro: datos de la venta + lo que anota Contabilidad.
     * Desde «Total con envío» los campos ya traen el valor efectivo (lo del libro si se corrigió, si no el de la venta);
     * los {@code accounting*} dicen qué se corrigió y {@code saleOriginal} guarda el dato original de la venta.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LedgerRow {
        // ── De la venta (hasta «Total con envío» no se editan; desde ahí, valor efectivo) ──
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
        /** Autorización anotada en el libro; vacía = rige {@code paymentAuthorization} de la venta. */
        private String accountingAuthorization;
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

        // ── Correcciones del libro a datos de la venta: vacío = no se corrigió ──
        private BigDecimal accountingTotal;
        private BigDecimal accountingShipping;
        private BigDecimal accountingNet;
        private String accountingCarrier;
        private LocalDate accountingSaleDate;
        private String accountingSocialNetwork;
        private String accountingEmail;
        private String accountingSalesperson;
        private String accountingGuideNumber;
        private String accountingShipmentNumber;
        private String accountingStatus;
        /** Dato original de la venta, solo de los campos corregidos (clave = nombre del campo efectivo). */
        private Map<String, Object> saleOriginal;

        // ── Bloqueo: una línea bloqueada no admite cambios ──
        private Boolean locked;
        private LocalDateTime lockedAt;
        private String lockedByName;
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
        /** true si el usuario puede bloquear líneas ya revisadas. */
        private boolean canLock;
        /** true si el usuario puede desbloquear (solo administradores). */
        private boolean canUnlock;
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

    /** Bloquear o desbloquear varias líneas del libro. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LockRequest {
        private List<Long> saleIds;
        /** true = bloquear, false = desbloquear. */
        private boolean locked;
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
