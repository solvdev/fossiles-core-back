package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "tax_invoice_attempt")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaxInvoiceAttemptEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tax_invoice_id", nullable = false)
    private Long taxInvoiceId;

    @Column(name = "attempt_number", nullable = false)
    private Integer attemptNumber;

    @Column(name = "action", length = 20, nullable = false)
    private String action;

    @Column(name = "status", length = 30, nullable = false)
    private String status;

    @Column(name = "source_type", length = 30)
    private String sourceType;

    @Column(name = "source_id")
    private Long sourceId;

    @Column(name = "internal_number", length = 40)
    private String internalNumber;

    @Column(name = "customer_tax_id", length = 50)
    private String customerTaxId;

    @Column(name = "customer_name", length = 200)
    private String customerName;

    @Column(name = "address", length = 500)
    private String address;

    @Column(name = "phone", length = 50)
    private String phone;

    @Column(name = "email", length = 200)
    private String email;

    @Column(name = "subtotal", precision = 12, scale = 2, nullable = false)
    private BigDecimal subtotal;

    @Column(name = "discount_amount", precision = 12, scale = 2, nullable = false)
    private BigDecimal discountAmount;

    @Column(name = "tax_amount", precision = 12, scale = 2, nullable = false)
    private BigDecimal taxAmount;

    @Column(name = "total_amount", precision = 12, scale = 2, nullable = false)
    private BigDecimal totalAmount;

    @Column(name = "fel_enabled", nullable = false)
    private Boolean felEnabled;

    @Column(name = "fel_transaction_id", length = 80)
    private String felTransactionId;

    @Column(name = "fel_uuid", length = 64)
    private String felUuid;

    @Column(name = "fel_serie", length = 32)
    private String felSerie;

    @Column(name = "fel_numero", length = 32)
    private String felNumero;

    @Column(name = "fel_error", length = 4000)
    private String felError;

    @Column(name = "lines_json", columnDefinition = "TEXT")
    private String linesJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = GuatemalaDateTime.now();
        }
        if (subtotal == null) {
            subtotal = BigDecimal.ZERO;
        }
        if (discountAmount == null) {
            discountAmount = BigDecimal.ZERO;
        }
        if (taxAmount == null) {
            taxAmount = BigDecimal.ZERO;
        }
        if (totalAmount == null) {
            totalAmount = BigDecimal.ZERO;
        }
        if (felEnabled == null) {
            felEnabled = false;
        }
    }
}
