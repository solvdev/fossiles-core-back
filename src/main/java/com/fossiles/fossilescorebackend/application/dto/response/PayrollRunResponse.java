package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Planilla con sus totales; el detalle agrega líneas, avisos, si quedó desactualizada y el siguiente cheque. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayrollRunResponse {
    private Long id;
    private String payGroup;
    private LocalDate scheduledDate;
    private LocalDate payDate;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private String status;
    private String bankName;
    private String notes;
    private BigDecimal totalGross;
    private BigDecimal totalDeductions;
    private BigDecimal totalNet;
    private LocalDateTime createdAt;
    private LocalDateTime calculatedAt;
    private LocalDateTime approvedAt;
    private LocalDateTime paidAt;
    private LocalDateTime cancelledAt;
    private String cancellationReason;
    /** Solo en el detalle. */
    private List<Line> lines;
    /** Solo en el detalle de un borrador o una aprobada: datos de la ficha que hay que revisar. */
    private List<String> warnings;
    /** Borrador cuyas fichas, cargos o parámetros cambiaron después del cálculo; no se puede aprobar sin recalcular. */
    private Boolean stale;
    /** Siguiente número de la chequera según los cheques ya usados; solo en una aprobada. */
    private String suggestedFirstChequeNumber;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Line {
        private Long id;
        private Long employeeId;
        private String employeeName;
        private String position;
        private Long departmentId;
        private String employmentType;
        private String paymentMethod;
        private String bankAccount;
        private BigDecimal monthlySalary;
        private BigDecimal daysPaid;
        private BigDecimal baseSalary;
        private BigDecimal bonification;
        private BigDecimal igssEmployee;
        private BigDecimal isr;
        private BigDecimal opiDiscount;
        private BigDecimal otherDeductions;
        private BigDecimal netPay;
        private BigDecimal igssEmployer;
        private BigDecimal irtra;
        private BigDecimal intecap;
        private String chequeNumber;
    }
}
