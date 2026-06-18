package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaxInvoiceAttemptResponse {
    private Long id;
    private Integer attemptNumber;
    private String action;
    private String status;
    private String sourceType;
    private Long sourceId;
    private String internalNumber;
    private String customerTaxId;
    private String customerName;
    private String address;
    private String phone;
    private String email;
    private BigDecimal subtotal;
    private BigDecimal discountAmount;
    private BigDecimal taxAmount;
    private BigDecimal totalAmount;
    private Boolean felEnabled;
    private String felTransactionId;
    private String felUuid;
    private String felSerie;
    private String felNumero;
    private String felError;
    private LocalDateTime createdAt;
    private Long createdBy;
    private List<LineSnapshot> lines;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LineSnapshot {
        private Integer lineNumber;
        private String description;
        private BigDecimal quantity;
        private BigDecimal unitPrice;
        private BigDecimal lineTotal;
    }
}
