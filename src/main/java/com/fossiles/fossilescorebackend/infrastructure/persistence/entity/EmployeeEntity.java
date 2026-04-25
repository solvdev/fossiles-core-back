package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "employee")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "first_name", length = 100)
    private String firstName;

    @Column(name = "last_name", length = 100)
    private String lastName;

    @Column(unique = true, length = 120)
    private String email;

    @Column(length = 30)
    private String phone;

    @Column(unique = true, length = 20)
    private String dpi;

    @Column(name = "hire_date")
    private LocalDate hireDate;

    @Column(name = "position", length = 100)
    private String position;

    @Column(name = "salary", precision = 10, scale = 2)
    private java.math.BigDecimal salary;

    @Column(name = "bank_account", length = 50)
    private String bankAccount;

    @Column(name = "payment_method", length = 50)
    private String paymentMethod;

    @Column(name = "igss_deduction", precision = 10, scale = 2)
    private java.math.BigDecimal igssDeduction;

    @Column(name = "quincena_bruta", precision = 10, scale = 2)
    private java.math.BigDecimal quincenaBruta;

    @Column(name = "quincena_neta", precision = 10, scale = 2)
    private java.math.BigDecimal quincenaNeta;

    @Column(name = "extra_hours", precision = 10, scale = 2)
    private java.math.BigDecimal extraHours;

    @Column(name = "extra_hours_amount", precision = 10, scale = 2)
    private java.math.BigDecimal extraHoursAmount;

    @Column(name = "night_hours", precision = 10, scale = 2)
    private java.math.BigDecimal nightHours;

    @Column(name = "bonification", precision = 10, scale = 2)
    private java.math.BigDecimal bonification;

    @Column(name = "meta_amount", precision = 10, scale = 2)
    private java.math.BigDecimal metaAmount;

    @Column(name = "total_bonus", precision = 10, scale = 2)
    private java.math.BigDecimal totalBonus;

    @Column(name = "variable_deduction", precision = 10, scale = 2)
    private java.math.BigDecimal variableDeduction;

    @Column(name = "department_id")
    private Long departmentId;

    @Column(name = "cost_center_id")
    private Long costCenterId;

    @Column(name = "operational_unit_id")
    private Long operationalUnitId;

    @Column(length = 20)
    private String status;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "employee_user",
        joinColumns = @JoinColumn(name = "employee_id"),
        inverseJoinColumns = @JoinColumn(name = "user_id")
    )
    @Builder.Default
    private java.util.Set<UserEntity> users = new java.util.HashSet<>();

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

