package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

public enum KioscoMovementType {
    ENTRADA,
    VENTA,
    DEVOLUCION_DEPOSITO,
    DEVOLUCION_CLIENTE,
    /** Producto entregado al cliente (egreso manual) → Sal. El egreso de boleta de cambio usa {@link #CAMBIO}. */
    DEVOLUCION_A_CLIENTE,
    TRASLADO_SALIDA,
    TRASLADO_ENTRADA,
    MERMA,
    AJUSTE,
    ANULACION,
    CAMBIO
}
