package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Meta y tasas (decimales: 0.18 = 18 %) por sitio / anio / mes. */
@Entity
@Table(name = "kiosk_period_config")
@IdClass(KioskPeriodConfigEntity.Key.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskPeriodConfigEntity {

    public static final String SOURCE_EXCEL = "EXCEL";
    public static final String SOURCE_COPIED = "COPIED";
    public static final String SOURCE_MANUAL = "MANUAL";

    @Id
    @Column(name = "site_id", nullable = false)
    private Long siteId;

    @Id
    @Column(name = "year", nullable = false)
    private Integer periodYear;

    @Id
    @Column(name = "month", nullable = false)
    private Integer periodMonth;

    @Column(name = "sales_goal", precision = 12, scale = 2)
    private BigDecimal salesGoal;

    @Column(name = "product_cost_pct", precision = 6, scale = 4)
    private BigDecimal productCostPct;

    @Column(name = "sales_commission_pct", precision = 6, scale = 4)
    private BigDecimal salesCommissionPct;

    @Column(name = "card_commission_pct", precision = 6, scale = 4)
    private BigDecimal cardCommissionPct;

    @Column(name = "tax_pct", precision = 6, scale = 4)
    private BigDecimal taxPct;

    @Column(name = "source", nullable = false, length = 20)
    @Builder.Default
    private String source = SOURCE_MANUAL;

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
        if (source == null) {
            source = SOURCE_MANUAL;
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private Long siteId;
        private Integer periodYear;
        private Integer periodMonth;
    }
}
