package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Table(name = "product_category")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductCategoryEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 20)
    private String code;

    @Column(length = 100)
    private String name;

    @Column(name = "hourly_cost", precision = 12, scale = 2)
    private BigDecimal hourlyCost;

    @Column(name = "payroll_total", precision = 12, scale = 2)
    private BigDecimal payrollTotal;

    @Column(name = "available_hours", precision = 10, scale = 2)
    private BigDecimal availableHours;

    @Column(name = "number_of_tables")
    private Integer numberOfTables;
}

