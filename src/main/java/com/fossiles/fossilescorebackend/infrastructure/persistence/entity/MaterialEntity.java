package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

@Entity
@Table(name = "material")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaterialEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String sku;

    @Column(length = 150)
    private String name;

    // ============================================
    // INFORMACIÓN DE COMPRA
    // ============================================
    
    @Column(name = "purchase_uom_id")
    private Long purchaseUomId; // Unidad en que se COMPRA (ej: Rollo, Gruesa, Caja, Paquete)
    
    @Column(name = "purchase_quantity", precision = 12, scale = 3)
    private BigDecimal purchaseQuantity; // Cantidad en la unidad de manufactura que contiene 1 unidad de compra
    // Ejemplo: 1 rollo = 25 pulgadas → purchaseQuantity = 25
    // Ejemplo: 1 gruesa = 144 unidades → purchaseQuantity = 144
    // Ejemplo: 1 caja = 100 metros → purchaseQuantity = 100
    
    @Column(name = "purchase_price", precision = 12, scale = 2)
    private BigDecimal purchasePrice; // Precio de 1 unidad de compra completa
    // Ejemplo: Q 50.00 por rollo
    // Ejemplo: Q 200.00 por gruesa
    
    // ============================================
    // INFORMACIÓN DE MANUFACTURA/USO
    // ============================================
    
    @Column(name = "manufacturing_uom_id")
    private Long manufacturingUomId; // Unidad en que se USA en producción (ej: Pulgadas, Unidades, Metros)
    
    @Column(name = "unit_cost", precision = 12, scale = 4)
    private BigDecimal unitCost; // Costo por UNA unidad de manufactura (se calcula automáticamente)
    // Fórmula: unitCost = purchasePrice / purchaseQuantity
    // Ejemplo: Q 50.00 / 25 pulgadas = Q 2.00 por pulgada
    // Ejemplo: Q 200.00 / 144 unidades = Q 1.3889 por unidad
    
    // ============================================
    // CAMPOS LEGACY (mantener por compatibilidad)
    // ============================================
    
    @Column(name = "uom_id")
    private Long uomId; // Campo legacy - mantener por compatibilidad durante migración
    
    @Column(name = "quantity", precision = 12, scale = 3)
    private BigDecimal quantity; // Campo legacy - mantener por compatibilidad
    
    @Column(precision = 12, scale = 2)
    private BigDecimal cost; // Campo legacy - mantener por compatibilidad

    @Column
    private Integer min;

    @Column
    private Integer max;

    @Column(name = "delivery_days")
    private Integer deliveryDays;

    @Column(name = "material_color_id")
    private Long materialColorId;

    @Column(name = "supplier_id")
    private Long supplierId;

    @Column(length = 255)
    private String description;

    @Column(length = 20)
    private String status;

    @Column(precision = 12, scale = 2)
    private BigDecimal lossPercentage;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(name = "is_primary_leather")
    private Boolean isPrimaryLeather;

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
        if (isPrimaryLeather == null) {
            isPrimaryLeather = false;
        }
        // Calcular unitCost automáticamente
        if (purchasePrice != null && purchaseQuantity != null && purchaseQuantity.compareTo(BigDecimal.ZERO) > 0) {
            this.unitCost = purchasePrice.divide(purchaseQuantity, 4, RoundingMode.HALF_UP);
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        // Recalcular unitCost si cambió precio o cantidad
        if (purchasePrice != null && purchaseQuantity != null && purchaseQuantity.compareTo(BigDecimal.ZERO) > 0) {
            this.unitCost = purchasePrice.divide(purchaseQuantity, 4, RoundingMode.HALF_UP);
        }
    }
}

