package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "monthly_liquidation",
       uniqueConstraints = @UniqueConstraint(columnNames = {"liquidation_year", "liquidation_month"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MonthlyLiquidationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "liquidation_year", nullable = false)
    private Integer liquidationYear;

    @Column(name = "liquidation_month", nullable = false)
    private Integer liquidationMonth;

    /** Pago de envío real (lo que se pagó a transportistas) */
    @Column(name = "real_shipping_cost", precision = 12, scale = 2)
    private BigDecimal realShippingCost;

    /** Comisiones servicio de transporte Forza */
    @Column(name = "forza_commission", precision = 12, scale = 2)
    private BigDecimal forzaCommission;

    /** Comisiones servicio de transporte Guatex */
    @Column(name = "guatex_commission", precision = 12, scale = 2)
    private BigDecimal guatexCommission;

    /** Faltante a descontar */
    @Column(name = "shortfall", precision = 12, scale = 2)
    private BigDecimal shortfall;

    /** Tasa de IVA (default 12% Guatemala) */
    @Column(name = "iva_rate", precision = 5, scale = 2)
    private BigDecimal ivaRate;

    /** Tasa de comisión vendedores (default 2%) */
    @Column(name = "commission_rate", precision = 5, scale = 2)
    private BigDecimal commissionRate;

    @Column(name = "notes", length = 2000)
    private String notes;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_by")
    private Long updatedBy;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (ivaRate == null) ivaRate = new BigDecimal("12.00");
        if (commissionRate == null) commissionRate = new BigDecimal("2.00");
        if (realShippingCost == null) realShippingCost = BigDecimal.ZERO;
        if (forzaCommission == null) forzaCommission = BigDecimal.ZERO;
        if (guatexCommission == null) guatexCommission = BigDecimal.ZERO;
        if (shortfall == null) shortfall = BigDecimal.ZERO;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

