package com.fossiles.fossilescorebackend.application.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskPosSalePaymentUpdateRequest {
    private String paymentMethod;
    private BigDecimal amountReceived;
    private BigDecimal cashAmount;
    private BigDecimal cardAmount;
    private String cardAuthNumber;
    private String cardLast4;
    private String cardBrand;
    private BigDecimal card2Amount;
    private String card2AuthNumber;
    private String card2Last4;
    private String card2Brand;
}
