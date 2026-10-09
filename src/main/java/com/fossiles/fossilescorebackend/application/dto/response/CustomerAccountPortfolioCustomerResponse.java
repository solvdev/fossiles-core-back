package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Subtotal por cliente de la cartera exportada (una sola entrada por cliente). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerAccountPortfolioCustomerResponse {
    private Long customerId;
    private String customerName;
    private String legacyCode;
    private String routeLocationCode;
    private int documentCount;
    private BigDecimal chargedAmount;
    private BigDecimal paymentsApplied;
    private BigDecimal creditsApplied;
    /** Neto por documentos (cargos - pagos - créditos); puede ser negativo. */
    private BigDecimal netBalance;
    /** Deuda (neto positivo); mismo criterio que el saldo del listado de cuentas por cobrar. */
    private BigDecimal balanceDue;
    /** Crédito a favor (neto negativo en valor absoluto). */
    private BigDecimal creditBalance;
}
