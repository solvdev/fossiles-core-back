package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "material_consumption_history", 
       uniqueConstraints = @UniqueConstraint(columnNames = {"material_id", "consumption_date"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaterialConsumptionHistoryEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "material_id", nullable = false)
    private Long materialId;

    @Column(name = "consumption_date", nullable = false)
    private LocalDate consumptionDate;

    @Column(name = "quantity_consumed", nullable = false, precision = 12, scale = 3)
    private BigDecimal quantityConsumed;

    @Column(name = "source", length = 50)
    private String source; // PRODUCTION_ORDER, TASK, MANUAL, etc.

    @Column(name = "source_reference_id")
    private Long sourceReferenceId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (consumptionDate == null) {
            consumptionDate = LocalDate.now();
        }
    }
}

