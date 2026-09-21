package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

public enum KioscoMovementType {
    ENTRADA,
    VENTA,
    DEVOLUCION_DEPOSITO,
    DEVOLUCION_CLIENTE,
    /** Producto entregado al cliente (egreso manual) → Sal. El egreso de boleta con diferencia a cobrar se clasifica como venta en el kardex. */
    DEVOLUCION_A_CLIENTE,
    TRASLADO_SALIDA,
    TRASLADO_ENTRADA,
    MERMA,
    AJUSTE,
    ANULACION,
    CAMBIO
}
