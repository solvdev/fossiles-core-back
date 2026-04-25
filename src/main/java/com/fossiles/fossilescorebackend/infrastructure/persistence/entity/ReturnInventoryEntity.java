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
@Table(name = "return_inventory")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReturnInventoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "online_sale_id", nullable = false)
    private Long onlineSaleId;

    @Column(name = "product_id")
    private Long productId;

    @Column(name = "product_code", length = 30)
    private String productCode;

    @Column(name = "product_name", length = 150)
    private String productName;

    @Column(name = "color_id")
    private Long colorId;

    @Column(name = "color_name", length = 100)
    private String colorName;

    @Column(name = "size", length = 50)
    private String size;

    @Column(name = "quantity")
    private Integer quantity;

    @Column(name = "unit_price", precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "subtotal", precision = 12, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "return_date")
    private LocalDate returnDate;

    @Column(name = "return_reason", length = 500)
    private String returnReason;

    /** BUENO, DAÑADO, USADO */
    @Column(name = "item_condition", length = 30)
    private String itemCondition;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (returnDate == null) returnDate = LocalDate.now();
        if (itemCondition == null) itemCondition = "BUENO";
        if (unitPrice != null && quantity != null) {
            subtotal = unitPrice.multiply(BigDecimal.valueOf(quantity));
        }
    }
}

