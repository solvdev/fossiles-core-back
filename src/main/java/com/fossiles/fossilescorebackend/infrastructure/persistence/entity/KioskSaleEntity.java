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

    @Column(name = "promotion_id")
    private Long promotionId;

    @Column(name = "promotion_name", length = 180)
    private String promotionName;

    @Column(name = "notes", length = 1500)
    private String notes;

    @Column(name = "comments", length = 1500)
    private String comments;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @OneToMany(mappedBy = "kioskSale", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<KioskSaleItemEntity> items = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
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
        createdAt = now;
    }
}
