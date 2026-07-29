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
public class KioskVoucherReportRowResponse {
    private Long id;
    private Long saleId;
    private String saleCode;
    private String invoiceNumber;
    private String cardBrand;
    /** Monto del voucher físico (terminal). */
    private BigDecimal amount;
    /** Monto de tarjeta en la factura (no se altera por el voucher). */
    private BigDecimal invoiceCardAmount;
    /** voucher − factura (positivo = de más). */
    private BigDecimal difference;
    private String voucherNumber;
    private String cardLast4;
    private String description;
    private LocalDateTime soldAt;
    private Long kioskLocationId;
    private String kioskCode;
    private String kioskName;
}
