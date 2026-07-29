package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskCashCloseReportResponse {
    private Long sessionId;
    private Long kioskLocationId;
    private String kioskCode;
    private String kioskName;
    private String openedByName;
    private String closedByName;
    private String generatedByName;
    private LocalDateTime openedAt;
    private LocalDateTime closedAt;
    private LocalDateTime generatedAt;

    private BigDecimal openingAmount;
    private BigDecimal cashSalesTotal;
    private BigDecimal cardSalesTotal;
    private BigDecimal salesSubtotal;
    private BigDecimal disbursementsTotal;
    private BigDecimal salesMinusDisbursements;
    private BigDecimal depositAmount;
    private String depositDetail;
    private BigDecimal totalCash;
    private BigDecimal closeAmount;
    private BigDecimal salesDayTotal;
    private BigDecimal countedCash;
    private BigDecimal expectedCash;
    private BigDecimal variance;

    @Builder.Default
    private List<SaleLine> sales = new ArrayList<>();

    @Builder.Default
    private List<DisbursementLine> disbursements = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SaleLine {
        private Long saleId;
        private String saleNumber;
        private String invoiceNumber;
        private String paymentMethod;
        private String paymentLabel;
        private String paymentKind;
        private BigDecimal amount;
        private LocalDateTime soldAt;
        /** Monto de tarjeta en factura (no cambia con el voucher). */
        private BigDecimal cardInvoiceAmount;
        private BigDecimal cardVoucherAmount;
        /** voucher − factura (positivo = cobró de más en terminal). */
        private BigDecimal cardVoucherDifference;
        private BigDecimal card2InvoiceAmount;
        private BigDecimal card2VoucherAmount;
        private BigDecimal card2VoucherDifference;
        /** Texto legible de diferencias de voucher, si aplica. */
        private String voucherDifferenceNote;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DisbursementLine {
        private Long id;
        private String description;
        private BigDecimal amount;
        private LocalDateTime createdAt;
    }

    /** Resumen de diferencias voucher vs factura del turno (informativo; no altera efectivo/FEL). */
    private BigDecimal cardVoucherTotal;
    private BigDecimal cardInvoiceCardTotal;
    private BigDecimal cardVoucherDifferencesTotal;
}
