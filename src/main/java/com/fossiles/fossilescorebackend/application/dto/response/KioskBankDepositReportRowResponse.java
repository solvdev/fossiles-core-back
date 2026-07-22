package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskBankDepositReportRowResponse {
    private Long id;
    private Long saleId;
    private String accountNumber;
    private String bankName;
    private String documentNumber;
    /** Efectivo bruto de la venta (antes de desembolsos ligados). */
    private BigDecimal grossCashAmount;
    /** Suma de desembolsos ligados a la venta. */
    private BigDecimal disbursementsTotal;
    /** Monto depositado (neto). */
    private BigDecimal amount;
    private String userName;
    private String description;
    private LocalDateTime recordedAt;
    private Long kioskLocationId;
    private String kioskCode;
    private String kioskName;
}
