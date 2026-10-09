package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Reporte de cartera de clientes: una fila por documento, con cargos, abonos, créditos y saldo.
 * El detalle de movimientos ({@link #movements}) es un anexo aparte y nunca se mezcla con {@link #rows}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerAccountPortfolioReportResponse {
    private LocalDateTime generatedAt;
    /** OPV | OPC */
    private String orderKind;
    /** Período del anexo de movimientos (la cartera siempre es la posición actual). */
    private LocalDate movementsFrom;
    private LocalDate movementsTo;

    private List<CustomerAccountPortfolioRowResponse> rows;
    private List<CustomerAccountPortfolioCustomerResponse> customers;
    private int customerCount;
    private int documentCount;

    private BigDecimal totalCharged;
    private BigDecimal totalPayments;
    private BigDecimal totalCredits;
    /** Suma de la deuda por cliente (mismo criterio que el listado). */
    private BigDecimal totalBalanceDue;
    private BigDecimal totalCreditBalance;

    /** Suma del saldo de la cartera según el listado de cuentas por cobrar (balanceDueOpv/Opc). */
    private BigDecimal systemBalanceDue;
    /** totalBalanceDue - systemBalanceDue; debe ser 0. */
    private BigDecimal difference;
    private boolean reconciled;

    /** Abonos/créditos activos sin documento: no forman parte del saldo por cartera. */
    private BigDecimal unappliedCreditsTotal;
    private int duplicateChargeDocuments;

    private boolean includesMovements;
    private List<CustomerAccountPortfolioMovementResponse> movements;
}
