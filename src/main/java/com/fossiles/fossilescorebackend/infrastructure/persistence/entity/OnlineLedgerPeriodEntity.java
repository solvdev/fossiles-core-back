package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Periodo de revisión del libro de ventas en línea. Abierto = Contabilidad lo está revisando; cerrado = todas las
 * ventas con fecha dentro del rango quedan bloqueadas en el libro.
 */
@Entity
@Table(name = "online_ledger_period")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OnlineLedgerPeriodEntity {

    public static final String OPEN = "OPEN";
    public static final String CLOSED = "CLOSED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "date_from", nullable = false)
    private LocalDate dateFrom;

    @Column(name = "date_to", nullable = false)
    private LocalDate dateTo;

    @Column(name = "label", nullable = false, length = 100)
    private String label;

    @Column(name = "status", nullable = false, length = 10)
    @Builder.Default
    private String status = OPEN;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "opened_at", nullable = false)
    private LocalDateTime openedAt;

    @Column(name = "opened_by")
    private Long openedBy;

    @Column(name = "opened_by_name", length = 150)
    private String openedByName;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "closed_by")
    private Long closedBy;

    @Column(name = "closed_by_name", length = 150)
    private String closedByName;

    @Column(name = "reopened_at")
    private LocalDateTime reopenedAt;

    @Column(name = "reopened_by_name", length = 150)
    private String reopenedByName;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public boolean isClosed() {
        return CLOSED.equals(status);
    }

    public boolean contains(LocalDate date) {
        return date != null && !date.isBefore(dateFrom) && !date.isAfter(dateTo);
    }

    @PrePersist
    protected void onCreate() {
        createdAt = GuatemalaDateTime.now();
        updatedAt = createdAt;
        if (openedAt == null) openedAt = createdAt;
        if (status == null) status = OPEN;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = GuatemalaDateTime.now();
    }
}
