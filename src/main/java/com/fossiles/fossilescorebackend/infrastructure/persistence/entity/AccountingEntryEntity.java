package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "accounting_entry")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountingEntryEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_type", nullable = false, length = 50)
    private String documentType; // PURCHASE_ORDER, MATERIAL_RECEIPT, PURCHASE_ORDER_CANCELLATION

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "entry_date", nullable = false)
    private LocalDateTime entryDate;

    @Column(name = "debit_amount", precision = 15, scale = 2)
    private BigDecimal debitAmount;

    @Column(name = "credit_amount", precision = 15, scale = 2)
    private BigDecimal creditAmount;

    @Column(name = "account_code", nullable = false, length = 50)
    private String accountCode;

    @Column(name = "account_name", length = 200)
    private String accountName;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "cost_center_id")
    private Long costCenterId;

    @Column(name = "reference_number", length = 100)
    private String referenceNumber; // Código de la orden de compra, etc.

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (entryDate == null) {
            entryDate = LocalDateTime.now();
        }
    }
}

