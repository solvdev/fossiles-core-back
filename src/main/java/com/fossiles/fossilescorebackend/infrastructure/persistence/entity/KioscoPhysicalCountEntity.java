package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Sesion de conteo fisico de inventario kiosco por periodo; editable antes y despues de revisada. */
@Entity
@Table(name = "kiosco_physical_count")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioscoPhysicalCountEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "location_id", nullable = false)
    private Long locationId;

    @Column(name = "period_from", nullable = false)
    private LocalDate periodFrom;

    @Column(name = "period_to", nullable = false)
    private LocalDate periodTo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private KioscoPhysicalCountStatus status = KioscoPhysicalCountStatus.DRAFT;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "generated_by", nullable = false)
    private Long generatedBy;

    @Column(name = "generated_at", nullable = false)
    private LocalDateTime generatedAt;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "closed_by")
    private Long closedBy;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    /** Mayor diferencia absoluta (sistema vs. fisico) entre todas las filas; recalculada al guardar/revisar. */
    @Column(name = "max_abs_diff", nullable = false)
    @Builder.Default
    private Integer maxAbsDiff = 0;

    /** Fecha de envio del correo de alerta por diferencias sin resolver (evita reenvios diarios). */
    @Column(name = "diff_notified_at")
    private LocalDateTime diffNotifiedAt;

    /** Hoja principal: REVISADO Y CERTIFICADO POR. */
    @Column(name = "main_sheet_certified_by", length = 120)
    private String mainSheetCertifiedBy;

    /** Hoja principal: REVISADO POR (segunda revisión). */
    @Column(name = "main_sheet_reviewed_by", length = 120)
    private String mainSheetReviewedBy;

    /** Hoja principal: INVENTARIO DIGITAL (fecha de certificación). */
    @Column(name = "main_sheet_certified_at")
    private LocalDateTime mainSheetCertifiedAt;

    /** Hoja principal: INVENTARIO DIGITAL — inicio del rango. */
    @Column(name = "main_sheet_inventory_from")
    private LocalDate mainSheetInventoryFrom;

    /** Hoja principal: INVENTARIO DIGITAL — fin del rango. */
    @Column(name = "main_sheet_inventory_to")
    private LocalDate mainSheetInventoryTo;

    /** Hoja principal: VENTAS DEL — inicio del rango. */
    @Column(name = "main_sheet_sales_from")
    private LocalDate mainSheetSalesFrom;

    /** Hoja principal: VENTAS DEL — fin del rango. */
    @Column(name = "main_sheet_sales_to")
    private LocalDate mainSheetSalesTo;

    /**
     * Snapshot JSON del Fin. al cerrar (producto+color y por talla).
     * Fuente inmutable del Ini. del siguiente conteo.
     */
    @Column(name = "closing_balances_data", columnDefinition = "TEXT")
    private String closingBalancesData;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id", insertable = false, updatable = false)
    private LocationEntity location;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "generated_by", insertable = false, updatable = false)
    private UserEntity generatedByUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by", insertable = false, updatable = false)
    private UserEntity reviewedByUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "closed_by", insertable = false, updatable = false)
    private UserEntity closedByUser;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (status == null) {
            status = KioscoPhysicalCountStatus.DRAFT;
        }
        if (generatedAt == null) {
            generatedAt = now;
        }
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
