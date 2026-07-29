package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "production_order_item")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductionOrderItemEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "production_order_id", nullable = false)
    private Long productionOrderId;

    @Column(name = "online_sale_id")
    private Long onlineSaleId;

    @Column(name = "online_sale_item_id")
    private Long onlineSaleItemId;

    @Column(name = "product_id")
    private Long productId;

    @Column(name = "color_id")
    private Long colorId;

    @Column(name = "brand_name", length = 100)
    private String brandName;

    @Column(name = "quantity")
    private Integer quantity;

    @Column(name = "warehouse_received_qty")
    private Integer warehouseReceivedQty;

    // Para cinchos: tallas y cantidades por talla (JSON o campos separados)
    // Usaremos un campo JSON para flexibilidad
    @Column(name = "sizes_data", columnDefinition = "TEXT")
    private String sizesData; // JSON: {"16": 3, "18": 5, "20": 2, ...}

    @Column(name = "observations", length = 500)
    private String observations;

    @Column(name = "unit_price", precision = 12, scale = 2)
    private BigDecimal unitPrice;

    /** Precios unitarios por talla (JSON), ej. {"46":125,"30":80}. Si falta una talla, se usa unitPrice. */
    @Column(name = "unit_prices_json", columnDefinition = "TEXT")
    private String unitPricesJson;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

