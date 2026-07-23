package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;

@Entity
@Table(name = "kiosk_sale")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskSaleEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sale_number", length = 40, nullable = false)
    private String saleNumber;

    @Column(name = "kiosk_location_id", nullable = false)
    private Long kioskLocationId;

    @Column(name = "sold_by_user_id", nullable = false)
    private Long soldByUserId;

    @Column(name = "sale_date", nullable = false)
    private LocalDate saleDate;

    @Column(name = "sold_at", nullable = false)
    private LocalDateTime soldAt;

    @Column(name = "customer_name", length = 160)
    private String customerName;

    @Column(name = "customer_tax_id", length = 30)
    private String customerTaxId;

    @Column(name = "address", length = 400)
    private String address;

    @Column(name = "phone", length = 40)
    private String phone;

    @Column(name = "email", length = 180)
    private String email;

    @Column(name = "payment_method", length = 50)
    private String paymentMethod;

    @Column(name = "status", length = 30)
    private String status;

    @Column(name = "total_items", precision = 12, scale = 3)
    private BigDecimal totalItems;

    @Column(name = "subtotal", precision = 12, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "total_amount", precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "discount_amount", precision = 12, scale = 2)
    private BigDecimal discountAmount;

    @Column(name = "amount_received", precision = 12, scale = 2)
    private BigDecimal amountReceived;

    @Column(name = "change_amount", precision = 12, scale = 2)
    private BigDecimal changeAmount;

    @Column(name = "cash_amount", precision = 12, scale = 2)
    private BigDecimal cashAmount;

    @Column(name = "card_amount", precision = 12, scale = 2)
    private BigDecimal cardAmount;

    @Column(name = "card_auth_number", length = 40)
    private String cardAuthNumber;

    @Column(name = "card_last4", length = 4)
    private String cardLast4;

    /** Marca de tarjeta: VISA, MC, AMEX. */
    @Column(name = "card_brand", length = 10)
    private String cardBrand;

    /** Segunda tarjeta cuando el pago TARJETA se divide en dos vouchers. */
    @Column(name = "card2_amount", precision = 12, scale = 2)
    private BigDecimal card2Amount;

    @Column(name = "card2_auth_number", length = 40)
    private String card2AuthNumber;

    @Column(name = "card2_last4", length = 4)
    private String card2Last4;

    @Column(name = "card2_brand", length = 10)
    private String card2Brand;

    @Column(name = "promotion_id")
    private Long promotionId;

    @Column(name = "promotion_name", length = 180)
    private String promotionName;

    @Column(name = "notes", length = 1500)
    private String notes;

    @Column(name = "comments", length = 1500)
    private String comments;

    @Column(name = "fel_status", length = 30)
    private String felStatus;

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

    @Column(name = "invoice_id")
    private Long invoiceId;

    /** Venta registrada con fel.emission.test-mode=true; no cuenta en métricas de producción. */
    @Column(name = "test_sale", nullable = false)
    @Builder.Default
    private Boolean testSale = false;

    @Column(name = "cash_session_id")
    private Long cashSessionId;

    @Column(name = "deposit_slip_number", length = 40)
    private String depositSlipNumber;

    @Column(name = "deposit_recorded_at")
    private LocalDateTime depositRecordedAt;

    @Column(name = "deposit_recorded_by")
    private Long depositRecordedBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @OneToMany(mappedBy = "kioskSale", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<KioskSaleItemEntity> items = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = GuatemalaDateTime.now();
        if (soldAt == null) {
            soldAt = now;
        }
        if (saleDate == null) {
            saleDate = soldAt.toLocalDate();
        }
        if (status == null || status.isBlank()) {
            status = "COMPLETED";
        }
        if (totalItems == null) {
            totalItems = BigDecimal.ZERO;
        }
        if (subtotal == null) {
            subtotal = BigDecimal.ZERO;
        }
        if (totalAmount == null) {
            totalAmount = subtotal;
        }
        if (discountAmount == null) {
            discountAmount = BigDecimal.ZERO;
        }
        if (testSale == null) {
            testSale = false;
        }
        createdAt = now;
    }
}
