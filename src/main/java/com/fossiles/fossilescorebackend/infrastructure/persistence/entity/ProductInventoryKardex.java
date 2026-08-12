package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "product_inventory_kardex")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductInventoryKardex {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "location_id", nullable = false)
    private Long locationId;

    /** Variante de color del movimiento; null en registros históricos o producto sin color. */
    @Column(name = "color_id")
    private Long colorId;

    /** Talla del movimiento en cinchos FOSS con desglose; null si el producto no maneja tallas. */
    @Column(name = "size_label", length = 50)
    private String sizeLabel;

    @Column(name = "movement_type", nullable = false, length = 50)
    private String movementType; // PRODUCTION_ENTRY, SALE_EXIT, TRANSFER_IN, TRANSFER_OUT, ADJUSTMENT, RETURN

    @Column(name = "quantity", nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity; // Positiva para entrada, negativa para salida

    @Column(name = "quantity_before", precision = 12, scale = 3)
    private BigDecimal quantityBefore;

    @Column(name = "quantity_after", precision = 12, scale = 3)
    private BigDecimal quantityAfter;

    @Column(name = "unit_cost", precision = 12, scale = 2)
    private BigDecimal unitCost;

    @Column(name = "total_cost", precision = 15, scale = 2)
    private BigDecimal totalCost;

    @Column(name = "reference_type", length = 50)
    private String referenceType; // PRODUCTION_ORDER, SALE_ORDER, TRANSFER, ADJUSTMENT, RETURN

    @Column(name = "reference_id")
    private Long referenceId;

    @Column(name = "reference_number", length = 100)
    private String referenceNumber; // Ej: OP-00001, V-00001

    /**
     * Línea del documento que originó el movimiento (product_shipment_detail, online_sale_item,
     * envio_detalle). Distingue dos tallas del mismo producto+color dentro del mismo documento.
     */
    @Column(name = "reference_line_id")
    private Long referenceLineId;

    @Column(length = 500)
    private String description;

    @Column(name = "movement_date", nullable = false)
    private LocalDateTime movementDate;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", insertable = false, updatable = false)
    private ProductEntity product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id", insertable = false, updatable = false)
    private LocationEntity location;

    @PrePersist
    protected void onCreate() {
        createdAt = GuatemalaDateTime.now();
        if (movementDate == null) {
            movementDate = GuatemalaDateTime.now();
        }
    }
}

