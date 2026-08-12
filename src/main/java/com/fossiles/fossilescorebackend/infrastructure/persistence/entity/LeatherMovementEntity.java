package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Movimiento de cuero: cada recepción (ENTRADA) o entrega a producción (SALIDA).
 * Funciona como Kardex de cuero.
 */
@Entity
@Table(name = "leather_movement", indexes = {
    @Index(name = "idx_leather_mov_material", columnList = "material_id"),
    @Index(name = "idx_leather_mov_type", columnList = "movement_type"),
    @Index(name = "idx_leather_mov_date", columnList = "movement_date")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeatherMovementEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ENTRADA o SALIDA */
    @Column(name = "movement_type", nullable = false, length = 20)
    private String movementType;

    /** FK al material (cuero) */
    @Column(name = "material_id", nullable = false)
    private Long materialId;

    /** Cantidad en pies cuadrados */
    @Column(name = "quantity", nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    /** Costo unitario por pie cuadrado (solo para entradas/compras) */
    @Column(name = "unit_cost", precision = 12, scale = 4)
    private BigDecimal unitCost;

    /** Costo total del movimiento */
    @Column(name = "total_cost", precision = 12, scale = 2)
    private BigDecimal totalCost;

    /** Fecha del movimiento */
    @Column(name = "movement_date", nullable = false)
    private LocalDate movementDate;

    /** FK al proveedor (solo para entradas) */
    @Column(name = "supplier_id")
    private Long supplierId;

    /** Referencia de documento de compra (factura, etc.) */
    @Column(name = "purchase_document", length = 100)
    private String purchaseDocument;

    /** FK a orden de producción (solo para salidas) */
    @Column(name = "production_order_id")
    private Long productionOrderId;

    /** Nombre de quien entrega el cuero */
    @Column(name = "delivered_by", length = 150)
    private String deliveredBy;

    /** Nombre de quien recibe el cuero */
    @Column(name = "received_by", length = 150)
    private String receivedBy;

    /** Observaciones / notas */
    @Column(name = "observations", length = 500)
    private String observations;

    /** Saldo después de este movimiento (para Kardex) */
    @Column(name = "balance_after", precision = 12, scale = 3)
    private BigDecimal balanceAfter;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @PrePersist
    protected void onCreate() {
        createdAt = GuatemalaDateTime.now();
        if (movementDate == null) movementDate = GuatemalaDateTime.today();
        if (totalCost == null && unitCost != null && quantity != null) {
            totalCost = unitCost.multiply(quantity);
        }
    }
}

