package com.fossiles.fossilescorebackend.application.dto.request;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
public class LeatherMovementRequest {

    /** ENTRADA o SALIDA */
    private String movementType;

    /** FK al material (cuero) */
    private Long materialId;

    /** Cantidad en pies cuadrados */
    private BigDecimal quantity;

    /** Costo unitario por pie cuadrado (entradas) */
    private BigDecimal unitCost;

    /** Fecha del movimiento */
    private LocalDate movementDate;

    /** FK al proveedor (entradas) */
    private Long supplierId;

    /** Documento de compra (entradas) */
    private String purchaseDocument;

    /** FK a orden de producción (salidas) */
    private Long productionOrderId;

    /** Quién entrega */
    private String deliveredBy;

    /** Quién recibe */
    private String receivedBy;

    /** Observaciones */
    private String observations;

    /** Detalle de productos para trazabilidad (especialmente cuando no hay OP) */
    private List<DeliveryProductItem> deliveryProducts;

    @Data
    public static class DeliveryProductItem {
        private Long productId;
        private String productCode;
        private String productName;
        private BigDecimal productQuantity;
        private BigDecimal leatherQuantity;
    }
}

