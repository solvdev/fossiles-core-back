package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerAccountBalanceResponse {
    private Long customerId;
    /** Saldo neto: positivo = debe, negativo = crédito a favor. */
    private BigDecimal balance;
    private BigDecimal balanceDue;
    private BigDecimal creditBalance;
    private BigDecimal balanceDueOpv;
    private BigDecimal balanceDueOpc;
}
