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

/** Pago a un proveedor: una o varias órdenes de compra del mismo proveedor, con una forma de pago y una cuenta. */
@Entity
@Table(name = "supplier_payment")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupplierPaymentEntity {

    public static final String STATUS_TO_PRINT = "POR_IMPRIMIR";
    public static final String STATUS_PRINTED = "IMPRESO";
    public static final String STATUS_PAID = "PAGADO";
    public static final String STATUS_VOID = "ANULADO";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_number", length = 20)
    private String paymentNumber;

    @Column(name = "supplier_id", nullable = false)
    private Long supplierId;

    @Column(name = "payment_method_id", nullable = false)
    private Long paymentMethodId;

    @Column(name = "bank_account_id")
    private Long bankAccountId;

    @Column(name = "pay_date", nullable = false)
    private LocalDate payDate;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "payee_name", length = 200)
    private String payeeName;

    /** Número de cheque, de boleta o referencia. */
    @Column(name = "reference", length = 60)
    private String reference;

    @Column(name = "concept", length = 300)
    private String concept;

    @Column(name = "status", nullable = false, length = 15)
    private String status;

    @Column(name = "printed_at")
    private LocalDateTime printedAt;

    @Column(name = "printed_by")
    private Long printedBy;

    @Column(name = "print_count", nullable = false)
    @Builder.Default
    private Integer printCount = 0;

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
