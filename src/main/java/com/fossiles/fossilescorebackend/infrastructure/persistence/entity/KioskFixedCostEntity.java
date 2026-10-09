package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Costo fijo por sitio / anio / mes / categoria. Columnas year/month se mapean a periodYear/periodMonth. */
@Entity
@Table(name = "kiosk_fixed_cost")
@IdClass(KioskFixedCostEntity.Key.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskFixedCostEntity {

    @Id
    @Column(name = "site_id", nullable = false)
    private Long siteId;

    @Id
    @Column(name = "year", nullable = false)
    private Integer periodYear;

    @Id
    @Column(name = "month", nullable = false)
    private Integer periodMonth;

    @Id
    @Column(name = "category_code", nullable = false, length = 40)
    private String categoryCode;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "import_batch_id")
    private Long importBatchId;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private Long siteId;
        private Integer periodYear;
        private Integer periodMonth;
        private String categoryCode;
    }
}
