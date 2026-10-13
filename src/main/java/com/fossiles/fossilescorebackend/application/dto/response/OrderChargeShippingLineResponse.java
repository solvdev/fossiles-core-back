package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderChargeShippingLineResponse {
    private Long partialReleaseId;
    private Long productShipmentId;
    private String shipmentNumber;
    /** Costo real guardado en el envío. Nunca un estimado de la orden. */
    private BigDecimal shippingCost;
}
