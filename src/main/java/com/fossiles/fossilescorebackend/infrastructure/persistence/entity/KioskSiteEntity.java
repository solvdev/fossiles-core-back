package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Sitio de finanzas: kiosco real (location_id) o sitio historico (location_id null). */
@Entity
@Table(name = "kiosk_site")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskSiteEntity {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_CLOSED = "CLOSED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "location_id")
    private Long locationId;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = STATUS_ACTIVE;

    @Column(name = "closed_on")
    private LocalDate closedOn;

    @Column(name = "pos_go_live_override")
    private LocalDate posGoLiveOverride;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    /** Sitio externo (p. ej. Entrecueros Pueblito): no aparece en ningún reporte ni pantalla de costos de Finanzas. */
    @Column(name = "exclude_from_reports", nullable = false)
    @Builder.Default
    private Boolean excludeFromReports = false;

    /** Categoria de ventas del kiosco (A, B o C), manual; null = sin categoria. */
    @Column(name = "sales_category", length = 1)
    private String salesCategory;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (status == null) {
            status = STATUS_ACTIVE;
        }
        if (sortOrder == null) {
            sortOrder = 0;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
