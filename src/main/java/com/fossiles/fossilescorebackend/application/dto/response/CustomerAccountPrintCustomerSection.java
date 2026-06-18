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
public class CustomerAccountPrintCustomerSection {
    private Long customerId;
    private String customerName;
    private String nit;
    private String phone;
    private String email;
    private String address;
    private BigDecimal balance;
    private BigDecimal balanceDue;
    private BigDecimal creditBalance;
    private LocalDate lastChargeDate;
    private LocalDate lastPaymentDate;
    private int lfOrderCount;
    private String routeLocationCode;
    private String routeRegionCode;
    private Integer routeNumber;
    private String routeLocationLabel;
    private List<CustomerAccountStatementLineResponse> lines;
}
