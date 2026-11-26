package com.fossiles.fossilescorebackend.application.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Envío directo a un cliente de Luis Felipe, desde stock (Bodega PT o Devoluciones), sin orden de producción.
 * Al enviarlo descarga la bodega y genera el cargo en cuentas por cobrar del cliente.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DirectSaleShipmentRequest {

    private Long customerId;

    /** OPV (Fossiles) u OPC (GCF: cinchos y marcas). Es la cartera donde cae el cargo. */
    private String kind;

    /** PT (Bodega PT) o RETURNS (Devoluciones): bodega de la que sale. */
    private String source;

    /** Costo del envío (opcional): entra como ajuste aparte del cargo, igual que en las OPV/OPC. */
    private BigDecimal shippingCost;

    private String notes;

    private List<Line> lines;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Line {
        private Long productId;
        private Long colorId;
        private String size;
        private BigDecimal quantity;
        /** Precio unitario cobrado; sin él se usa el precio de vendedor del producto. */
        private BigDecimal unitPrice;
    }
}
