package com.fossiles.fossilescorebackend.application.dto.request;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Actualiza solo precios de ítems (y opcionalmente envío) sin recrear líneas de la OP.
 * Seguro cuando ya existen envíos / unidades de bodega.
 */
@Data
public class ProductionOrderItemPricesRequest {
    private BigDecimal shippingCost;
    private List<ItemPrice> items;

    @Data
    public static class ItemPrice {
        private Long productId;
        private Long colorId;
        private BigDecimal unitPrice;
        private Map<String, BigDecimal> unitPrices;
    }
}
