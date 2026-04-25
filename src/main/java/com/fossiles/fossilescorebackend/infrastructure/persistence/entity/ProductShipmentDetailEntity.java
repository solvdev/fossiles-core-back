package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "product_shipment_detail",
       uniqueConstraints = @UniqueConstraint(columnNames = {"shipment_id", "product_id", "color_id", "size_label"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductShipmentDetailEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "shipment_id", nullable = false)
    private Long shipmentId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "color_id")
    private Long colorId;

    @Column(name = "size_label", nullable = false, length = 50, columnDefinition = "varchar(50) not null default ''")
    @Builder.Default
    private String sizeLabel = "";

    @Column(name = "quantity", nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    @Column(name = "quantity_received", precision = 12, scale = 3)
    private BigDecimal quantityReceived;

    @Column(name = "quantity_difference", precision = 12, scale = 3)
    private BigDecimal quantityDifference;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shipment_id", insertable = false, updatable = false)
    private ProductShipmentEntity shipment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", insertable = false, updatable = false)
    private ProductEntity product;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (sizeLabel == null) {
            sizeLabel = "";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        if (sizeLabel == null) {
            sizeLabel = "";
        }
    }
}

