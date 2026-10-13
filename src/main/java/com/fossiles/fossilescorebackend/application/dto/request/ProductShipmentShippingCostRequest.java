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
public class ProductShipmentShippingCostRequest {
    /** Costo de envío del documento (Q). Null limpia el valor (vuelve a heredar de la OP). */
    private BigDecimal shippingCost;
}
