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
}
