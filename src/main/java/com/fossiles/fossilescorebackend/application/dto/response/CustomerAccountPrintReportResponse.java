package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerAccountPrintReportResponse {
    private LocalDateTime generatedAt;
    private LocalDate fromDate;
    private LocalDate toDate;
    private BigDecimal totalBalanceDue;
    private BigDecimal totalCreditBalance;
    private int customerCount;
    private List<CustomerAccountPrintCustomerSection> customers;
}
