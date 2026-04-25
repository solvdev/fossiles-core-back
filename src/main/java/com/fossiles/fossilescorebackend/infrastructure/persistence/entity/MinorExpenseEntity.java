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
@Table(name = "minor_expense", uniqueConstraints = {
    @UniqueConstraint(columnNames = "invoice_number")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MinorExpenseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "invoice_number", nullable = false, unique = true, length = 100)
    private String invoiceNumber;

    @Column(name = "purchase_date", nullable = false)
    private LocalDate purchaseDate;

    @Column(nullable = false, length = 1000)
    private String description;

    @Column(nullable = false, length = 200)
    private String supplier;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "purchaser_name", nullable = false, length = 100)
    private String purchaserName; // Persona que realizó la compra (ej: "Mensajero")

    @Column(name = "authorizer_name", length = 100)
    private String authorizerName; // Persona que autorizó (ej: "Contabilidad")

    @Column(name = "company_amount", precision = 12, scale = 2)
    private BigDecimal companyAmount;

    @Column(name = "messenger_amount", precision = 12, scale = 2)
    private BigDecimal messengerAmount;

    @Column(name = "initial_amount_given", precision = 12, scale = 2)
    private BigDecimal initialAmountGiven; // Monto inicial que la empresa le dio al mensajero (caja chica)

    @Column(name = "returned_amount", precision = 12, scale = 2)
    private BigDecimal returnedAmount; // Monto que el mensajero devuelve a la empresa (cambio/sobrante)

    @Column(name = "reimbursement_status", length = 30)
    private String reimbursementStatus; // PENDIENTE, PAGADO, NO_APLICA

    @Column(name = "reimbursement_date")
    private LocalDate reimbursementDate;

    @Column(name = "reimbursement_payment_method", length = 100)
    private String reimbursementPaymentMethod;

    @Column(name = "reimbursement_adjustment", precision = 12, scale = 2)
    private BigDecimal reimbursementAdjustment; // Ajuste manual del reembolso (puede ser positivo o negativo para compensar con vuelto)

    @Column(name = "initial_payment_method", length = 30, nullable = false)
    private String initialPaymentMethod; // EMPRESA, MENSAJERO

    @Column(length = 2000)
    private String observations;

    @Column(name = "invoice_file_url", length = 500)
    private String invoiceFileUrl;

    @Column(name = "purchase_number_id")
    private Long purchaseNumberId; // ID del número de compra asociado

    @Column(name = "purchase_number_item_id")
    private Long purchaseNumberItemId; // ID del artículo de la compra asociado

    @Column(name = "estimated_price", precision = 12, scale = 2)
    private BigDecimal estimatedPrice; // Precio estimado del artículo (del PurchaseNumberItem)

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (reimbursementStatus == null) {
            reimbursementStatus = "NO_APLICA";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

