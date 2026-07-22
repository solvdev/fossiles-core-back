package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskPosSaleResponse {
    private Long id;
    private String saleNumber;
    private LocalDate saleDate;
    private LocalDateTime soldAt;
    private Long kioskId;
    private String kioskCode;
    private String kioskName;
    private Long soldByUserId;
    private String soldByUsername;
    private String soldByName;
    private String customerTaxId;
    private String customerName;
    private String address;
    private String phone;
    private String email;
    private String paymentMethod;
    private String status;
    private Long cashSessionId;
    /** true = venta piloto/prueba; excluida de dashboard y reporte general admin. */
    private Boolean testSale;
    private BigDecimal totalItems;
    private BigDecimal discountAmount;
    private BigDecimal subtotal;
    private BigDecimal totalAmount;
    private BigDecimal amountReceived;
    private BigDecimal changeAmount;
    private BigDecimal cashAmount;
    private BigDecimal cardAmount;
    private String cardAuthNumber;
    private String cardLast4;
    private String cardBrand;
    private String notes;
    private String comments;
    private Long promotionId;
    private String promotionName;
    private List<Item> items;

    private String felStatus;
    private String felUuid;
    private String felSerie;
    private String felNumero;
    private String felError;
    private LocalDateTime felCertifiedAt;
    /** Número de control interno de la tax invoice (ej. A45-241). */
    private String internalNumber;
    private InvoiceInfo invoice;

    private String depositSlipNumber;
    private LocalDateTime depositRecordedAt;
    private Long depositRecordedByUserId;
    private String depositRecordedByName;
    /** true = venta en efectivo/mixto sin boleta registrada aún. */
    private Boolean pendingDeposit;
    /** Efectivo bruto sujeto a depósito (sin desembolsos). */
    private BigDecimal cashAmountForDeposit;
    /** Desembolsos ligados a esta venta. */
    private BigDecimal disbursementsTotal;
    /** Monto neto a depositar (efectivo − desembolsos). */
    private BigDecimal netDepositAmount;
    private List<SaleDisbursement> disbursements;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SaleDisbursement {
        private Long id;
        private BigDecimal amount;
        private String description;
        private LocalDateTime createdAt;
        private String createdByName;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InvoiceInfo {
        private Long id;
        private String status;
        private String internalNumber;
        private String felUuid;
        private String felSerie;
        private String felNumero;
        private String felError;
        private LocalDateTime felCertifiedAt;
        private Boolean hasCertifiedXml;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        private Long id;
        private Long productId;
        private String productCode;
        private String productName;
        private Long colorId;
        private String colorName;
        /** Categoría / material para reportes (ej. Cuero, Empaque). */
        private String categoryName;
        private BigDecimal quantity;
        private BigDecimal unitPrice;
        private BigDecimal lineTotal;
    }
}
