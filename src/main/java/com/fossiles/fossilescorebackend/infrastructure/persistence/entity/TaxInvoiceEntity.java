package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "tax_invoice")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaxInvoiceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_type", length = 30, nullable = false)
    private String sourceType;

    @Column(name = "source_id")
    private Long sourceId;

    @Column(name = "document_type", length = 10, nullable = false)
    private String documentType;

    @Column(name = "status", length = 30, nullable = false)
    private String status;

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

    @Column(name = "fel_uuid", length = 64)
    private String felUuid;

    @Column(name = "fel_serie", length = 32)
    private String felSerie;

    @Column(name = "fel_numero", length = 32)
    private String felNumero;

    @Column(name = "fel_error", length = 4000)
    private String felError;

    @Column(name = "fel_certified_at")
    private LocalDateTime felCertifiedAt;

    @Column(name = "fel_transaction_id", length = 80)
    private String felTransactionId;

    @Column(name = "fel_certified_xml", columnDefinition = "TEXT")
    private String felCertifiedXml;

    @Column(name = "internal_number", length = 40)
    private String internalNumber;

    @Column(name = "issued_at")
    private LocalDateTime issuedAt;

    @Column(name = "notes", length = 2000)
    private String notes;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "voided_at")
    private LocalDateTime voidedAt;

    @Column(name = "void_reason", length = 500)
    private String voidReason;

    @Column(name = "fel_void_uuid", length = 64)
    private String felVoidUuid;

    @OneToMany(mappedBy = "taxInvoice", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<TaxInvoiceLineEntity> lines = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = GuatemalaDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (documentType == null || documentType.isBlank()) {
            documentType = "FACT";
        }
        if (status == null || status.isBlank()) {
            status = "DRAFT";
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
        if (issuedAt == null) {
            issuedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = GuatemalaDateTime.now();
    }
}
