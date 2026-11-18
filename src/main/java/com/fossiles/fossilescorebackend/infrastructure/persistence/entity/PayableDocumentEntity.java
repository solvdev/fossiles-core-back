package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Lo que se debe y no viene de una orden de compra: saldos iniciales, servicios, fletes y rentas. */
@Entity
@Table(name = "payable_document")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayableDocumentEntity {

    public static final String KIND_MANUAL = "MANUAL";
    public static final String KIND_OPENING = "OPENING";
    public static final String KIND_RENT = "RENT";
    public static final String STATUS_ACTIVE = "ACTIVO";
    public static final String STATUS_VOID = "ANULADO";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "supplier_id")
    private Long supplierId;

    @Column(name = "payee_name", nullable = false, length = 200)
    private String payeeName;

    @Column(name = "concept", nullable = false, length = 300)
    private String concept;

    @Column(name = "document_number", length = 60)
    private String documentNumber;

    @Column(name = "issue_date", nullable = false)
    private LocalDate issueDate;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "kind", nullable = false, length = 10)
    private String kind;

    @Column(name = "location_id")
    private Long locationId;

    /** Renta que no es kiosco. En una renta ACTIVA va este id o {@code location_id}, no los dos. */
    @Column(name = "other_rent_id")
    private Long otherRentId;

    /** "YYYY-MM" de una renta. */
    @Column(name = "period", length = 7)
    private String period;

    @Column(name = "status", nullable = false, length = 10)
    @Builder.Default
    private String status = STATUS_ACTIVE;

    @Column(name = "void_reason", length = 300)
    private String voidReason;

    @Column(name = "voided_at")
    private LocalDateTime voidedAt;

    @Column(name = "voided_by")
    private Long voidedBy;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = GuatemalaDateTime.now();
        }
    }
}
