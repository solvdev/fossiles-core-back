package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

public enum KioscoMovementType {
    ENTRADA,
    VENTA,
    DEVOLUCION_DEPOSITO,
    DEVOLUCION_CLIENTE,
    /** Producto entregado al cliente (egreso de cambio) → Sal. */
    DEVOLUCION_A_CLIENTE,
    TRASLADO_SALIDA,
    TRASLADO_ENTRADA,
    MERMA,
    AJUSTE,
    ANULACION,
    CAMBIO
}
