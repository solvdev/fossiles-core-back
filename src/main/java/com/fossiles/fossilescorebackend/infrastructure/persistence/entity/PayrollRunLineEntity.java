package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/** Detalle por empleado con los datos de la ficha copiados al calcular: la planilla aprobada no cambia si la ficha cambia. */
@Entity
@Table(name = "payroll_run_line")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayrollRunLineEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "employee_name", nullable = false, length = 200)
    private String employeeName;

    @Column(length = 100)
    private String position;

    @Column(name = "department_id")
    private Long departmentId;

    @Column(name = "cost_center_id")
    private Long costCenterId;

    @Column(name = "employment_type", nullable = false, length = 20)
    private String employmentType;

    @Column(name = "payment_method", length = 50)
    private String paymentMethod;

    @Column(name = "bank_account", length = 50)
    private String bankAccount;

    @Column(name = "monthly_salary", nullable = false, precision = 10, scale = 2)
    private BigDecimal monthlySalary;

    @Column(name = "days_paid", nullable = false, precision = 5, scale = 2)
    private BigDecimal daysPaid;

    @Column(name = "base_salary", nullable = false, precision = 12, scale = 2)
    private BigDecimal baseSalary;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal bonification;

    @Column(name = "igss_employee", nullable = false, precision = 12, scale = 2)
    private BigDecimal igssEmployee;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal isr;

    @Column(name = "opi_discount", nullable = false, precision = 12, scale = 2)
    private BigDecimal opiDiscount;

    @Column(name = "other_deductions", nullable = false, precision = 12, scale = 2)
    private BigDecimal otherDeductions;

    @Column(name = "net_pay", nullable = false, precision = 12, scale = 2)
    private BigDecimal netPay;

    @Column(name = "igss_employer", nullable = false, precision = 12, scale = 2)
    private BigDecimal igssEmployer;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal irtra;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal intecap;

    @Column(name = "cheque_number", length = 30)
    private String chequeNumber;
}
