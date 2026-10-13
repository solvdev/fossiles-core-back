package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Monto que el servidor usará al crear el CHARGE de una orden.
 * El cargo guarda productos de toda la orden más el envío real de los parciales que ya tienen envío.
 * Un parcial sin envío no suma envío y no se estima.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderChargeQuoteResponse {
    private Long productionOrderId;
    private String orderCode;
    private String orderKind;
    private BigDecimal productsTotal;
    /** Envío real de parciales que ya tienen envío. Se cobra con CHARGE_ADJUSTMENT, no dentro del cargo. */
    private BigDecimal shippingTotal;
    /** Monto que se guarda en el CHARGE: solo los productos. */
    private BigDecimal amount;
    /** productsTotal + shippingTotal. Un cargo por encima de este total se rechaza. */
    private BigDecimal orderTotal;
    private List<OrderChargeShippingLineResponse> shippingLines;
}
