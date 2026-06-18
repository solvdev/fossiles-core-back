package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerAccountStatementResponse {
    private Long customerId;
    private String customerName;
    private String legacyCode;
    private String nit;
    private String phone;
    private String email;
    private String address;
    private LocalDate fromDate;
    private LocalDate toDate;
    private BigDecimal openingBalance;
    private BigDecimal closingBalance;
    private BigDecimal closingBalanceDue;
    private BigDecimal closingCreditBalance;
    private BigDecimal closingBalanceDueOpv;
    private BigDecimal closingBalanceDueOpc;
    private BigDecimal totalCharges;
    private BigDecimal totalPayments;
    private BigDecimal totalCreditNotes;
    private BigDecimal totalReturns;
    private List<CustomerAccountStatementLineResponse> lines;
}
