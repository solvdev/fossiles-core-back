package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Cargo a colaborador con su saldo y lo reservado en planillas en borrador. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeChargeResponse {
    private Long id;
    private Long employeeId;
    private String employeeName;
    private String payGroup;
    private String sourceType;
    private Long sourceId;
    private String description;
    private BigDecimal amount;
    private BigDecimal balance;
    /** DESCUENTO, TRANSFERENCIA o EFECTIVO; null mientras Contabilidad no decide. */
    private String settlementMethod;
    private String status;
    private LocalDate chargedOn;
    private LocalDateTime decidedAt;
    /** Monto reservado por planillas en borrador que todavía no rebajan el saldo. */
    private BigDecimal reservedInDrafts;
    /** Sigue activo y sin fecha de baja; si ya salió, un cargo de planta también se puede saldar por transferencia o en efectivo. */
    private Boolean employeeActive;
}
