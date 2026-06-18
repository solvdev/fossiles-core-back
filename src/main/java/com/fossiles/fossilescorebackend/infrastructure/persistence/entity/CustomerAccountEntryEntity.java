package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "customer_account_entry")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerAccountEntryEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "entry_type", nullable = false, length = 30)
    private String entryType;

    @Column(name = "entry_date", nullable = false)
    private LocalDate entryDate;

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(length = 100)
    private String reference;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "payment_method", length = 50)
    private String paymentMethod;

    @Column(name = "production_order_id")
    private Long productionOrderId;

    @Column(name = "partial_release_id")
    private Long partialReleaseId;

    @Column(name = "product_shipment_id")
    private Long productShipmentId;

    @Column(name = "vendor_shipment_number", length = 30)
    private String vendorShipmentNumber;

    @Column(name = "order_kind", length = 10)
    private String orderKind;

    @Column(name = "movement_concept_code", length = 10)
    private String movementConceptCode;

    @Column(name = "receipt_number", length = 50)
    private String receiptNumber;

    @Column(name = "collection_date")
    private LocalDate collectionDate;

    @Column(name = "payment_discount_amount", precision = 15, scale = 2)
    private BigDecimal paymentDiscountAmount;

    @Column(name = "payment_discount_percent", precision = 7, scale = 4)
    private BigDecimal paymentDiscountPercent;

    @Column(name = "gross_collected_amount", precision = 15, scale = 2)
    private BigDecimal grossCollectedAmount;

    @Column(name = "applied_to_entry_id")
    private Long appliedToEntryId;

    @Column(name = "invoice_number", length = 50)
    private String invoiceNumber;

    @Column(name = "document_number", length = 50)
    private String documentNumber;

    @Column(name = "return_voucher_number", length = 50)
    private String returnVoucherNumber;

    @Column(name = "return_date")
    private LocalDate returnDate;

    @Column(name = "return_reason", columnDefinition = "TEXT")
    private String returnReason;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "ACTIVE";

    @Column(name = "voided_at")
    private LocalDateTime voidedAt;

    @Column(name = "voided_by")
    private Long voidedBy;

    @Column(name = "void_reason", columnDefinition = "TEXT")
    private String voidReason;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null || status.isBlank()) {
            status = "ACTIVE";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
