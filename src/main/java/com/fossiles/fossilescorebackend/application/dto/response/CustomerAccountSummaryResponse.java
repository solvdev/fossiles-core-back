package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerAccountSummaryResponse {
    private Long customerId;
    private String customerName;
    private String legacyCode;
    private String nit;
    private String phone;
    private BigDecimal balance;
    /** Deuda por cobrar (neto positivo). */
    private BigDecimal balanceDue;
    /** Crédito a favor del cliente (neto negativo en valor absoluto). */
    private BigDecimal creditBalance;
    private BigDecimal balanceDueOpv;
    private BigDecimal balanceDueOpc;
    private LocalDate lastChargeDate;
    private LocalDate lastPaymentDate;
    private int lfOrderCount;
    private String routeLocationCode;
    private String routeRegionCode;
    private Integer routeNumber;
    private String routeLocationLabel;
}
