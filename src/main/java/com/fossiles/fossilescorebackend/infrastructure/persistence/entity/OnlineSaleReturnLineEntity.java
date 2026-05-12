package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Table(name = "online_sale_return_line")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OnlineSaleReturnLineEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "return_id", nullable = false)
    private Long returnId;

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

    @PrePersist
    protected void onCreate() {
        if (subtotal == null && unitPrice != null && quantity != null) {
            subtotal = unitPrice.multiply(BigDecimal.valueOf(quantity));
        }
    }
}

