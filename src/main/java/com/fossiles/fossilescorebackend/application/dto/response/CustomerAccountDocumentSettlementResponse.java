package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerAccountDocumentSettlementResponse {

    private Long appliedToEntryId;
    private BigDecimal initialBalance;
    private BigDecimal commercialDiscount;
    private BigDecimal balanceAfterDiscount;
    private BigDecimal paymentGross;
    private BigDecimal paymentNet;
    private BigDecimal paymentDiscountAtCollection;
    private BigDecimal finalBalance;
    private List<CustomerAccountEntryResponse> entries;
}
