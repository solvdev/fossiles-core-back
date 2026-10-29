package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Salario y bonificación vigentes desde una fecha; se registra cada vez que cambian en la ficha. */
@Entity
@Table(name = "employee_salary_history")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeSalaryHistoryEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(precision = 10, scale = 2)
    private BigDecimal salary;

    @Column(precision = 10, scale = 2)
    private BigDecimal bonification;

    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;

    @Column(name = "recorded_by")
    private Long recordedBy;

    @PrePersist
    void onCreate() {
        if (recordedAt == null) recordedAt = GuatemalaDateTime.now();
    }
}
