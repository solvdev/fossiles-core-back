package com.fossiles.fossilescorebackend.application.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class KioskExchangeCompleteRequest extends KioskExchangePreviewRequest {
    private String customerTaxId;
    private String customerName;
    private String address;
    private String phone;
    private String email;
    private String paymentMethod;
    private BigDecimal amountReceived;
    private BigDecimal cashAmount;
    private BigDecimal cardAmount;
    private String cardAuthNumber;
    private String cardLast4;
    private String cardBrand;
    private BigDecimal cardVoucherAmount;
    private String notes;
    private String comments;
    private Boolean requestInvoice;
    private String reason;
    private String observations;
    private String physicalSlipNumber;
}
