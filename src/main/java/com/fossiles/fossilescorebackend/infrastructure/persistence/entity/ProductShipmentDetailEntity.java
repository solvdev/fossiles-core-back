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
       uniqueConstraints = @UniqueConstraint(
               name = "uq_shipment_detail_product_color_size_hw",
               columnNames = {"shipment_id", "product_id", "color_id", "size_label", "hardware_condition"}))
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

    /** NUEVO | VIEJO — herraje del producto enviado. */
    @Column(name = "hardware_condition", length = 20)
    private String hardwareCondition;

    @Column(name = "quantity", nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    /** Precio unitario de esta línea (permite override por talla / cliente). */
    @Column(name = "unit_price", precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "quantity_received", precision = 12, scale = 3)
    private BigDecimal quantityReceived;

    @Column(name = "quantity_difference", precision = 12, scale = 3)
    private BigDecimal quantityDifference;

    @Column(name = "received_line_notes", length = 500)
    private String receivedLineNotes;

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

