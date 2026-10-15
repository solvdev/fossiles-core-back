package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Movimiento de kardex archivado por una limpieza de inventario. Solo lectura: no participa en
 * saldos, idempotencia de documentos ni recálculos del ledger.
 */
@Entity
@Immutable
@Table(name = "product_inventory_kardex_historico")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductInventoryKardexHistorico {
    /** Mismo id que tenía en product_inventory_kardex. */
    @Id
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "location_id", nullable = false)
    private Long locationId;

    @Column(name = "color_id")
    private Long colorId;

    @Column(name = "size_label", length = 50)
    private String sizeLabel;

    @Column(name = "movement_type", nullable = false, length = 50)
    private String movementType;

    @Column(name = "quantity", nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    @Column(name = "quantity_before", precision = 12, scale = 3)
    private BigDecimal quantityBefore;

    @Column(name = "quantity_after", precision = 12, scale = 3)
    private BigDecimal quantityAfter;

    @Column(name = "unit_cost", precision = 12, scale = 2)
    private BigDecimal unitCost;

    @Column(name = "total_cost", precision = 15, scale = 2)
    private BigDecimal totalCost;

    @Column(name = "reference_type", length = 50)
    private String referenceType;

    @Column(name = "reference_id")
    private Long referenceId;

    @Column(name = "reference_number", length = 100)
    private String referenceNumber;

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

    /** Limpieza que archivó el movimiento, p. ej. LIMPIEZA-20261020. */
    @Column(name = "archive_reference", nullable = false, length = 100)
    private String archiveReference;

    @Column(name = "archived_at", nullable = false)
    private LocalDateTime archivedAt;
}
