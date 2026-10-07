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

/** Inversión en publicidad de un día (un solo monto para todas las plataformas). Un registro por día. */
@Entity
@Table(name = "online_ad_spend")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OnlineAdSpendEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Día de la inversión (único) */
    @Column(name = "spend_date", nullable = false, unique = true)
    private LocalDate spendDate;

    /** Monto invertido en quetzales (>= 0) */
    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "notes", length = 255)
    private String notes;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    @PrePersist
    protected void onCreate() {
        createdAt = GuatemalaDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = GuatemalaDateTime.now();
    }
}
