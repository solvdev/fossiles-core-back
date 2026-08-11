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
public class TaxInvoiceResponse {
    private Long id;
    private String sourceType;
    private Long sourceId;
    private String documentType;
    private String status;
    private String internalNumber;
    private LocalDateTime issuedAt;
    private String customerTaxId;
    private String customerName;
    private String address;
    private String phone;
    private String email;
    private BigDecimal subtotal;
    private BigDecimal discountAmount;
    private BigDecimal taxAmount;
    private BigDecimal totalAmount;
    private String felUuid;
    private String felSerie;
    private String felNumero;
    private String felError;
    private LocalDateTime felCertifiedAt;
    private LocalDateTime voidedAt;
    private String voidReason;
    private String felVoidUuid;
    /** true si hay XML certificado almacenado (descargable vía GET /{id}/certified-xml). */
    private Boolean hasCertifiedXml;
    /** Receptor Consumidor Final (CF). */
    private Boolean consumidorFinal;
    /** Anulación FEL directa permitida ahora (CF: solo emisión o día siguiente). */
    private Boolean felDirectVoidAllowed;
    /** Último día permitido para anulación directa CF (emisión+1 GT); null si no aplica. */
    private LocalDate felDirectVoidDeadlineDate;
    private String notes;
    private LocalDateTime createdAt;
    private Long createdBy;
    private List<LineResponse> lines;
    private List<TaxInvoiceAttemptResponse> attempts;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LineResponse {
        private Long id;
        private Integer lineNumber;
        private String description;
        private BigDecimal quantity;
        private BigDecimal unitPrice;
        private BigDecimal lineTotal;
        private BigDecimal gravableAmount;
        private BigDecimal taxAmount;
    }
}
