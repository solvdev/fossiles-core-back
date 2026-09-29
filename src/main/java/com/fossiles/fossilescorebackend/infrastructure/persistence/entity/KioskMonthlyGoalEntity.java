package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "kiosk_monthly_goal")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskMonthlyGoalEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "kiosk_location_id", nullable = false)
    private Long kioskLocationId;

    @Column(name = "goal_year", nullable = false)
    private Integer goalYear;

    @Column(name = "goal_month", nullable = false)
    private Integer goalMonth;

    @Column(name = "goal_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal goalAmount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by_user_id", nullable = false)
    private Long createdByUserId;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by_user_id")
    private Long updatedByUserId;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = GuatemalaDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = GuatemalaDateTime.now();
    }
}
