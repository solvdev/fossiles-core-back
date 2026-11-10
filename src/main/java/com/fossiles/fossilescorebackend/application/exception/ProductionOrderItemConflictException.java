package com.fossiles.fossilescorebackend.application.exception;

/**
 * No se puede quitar una línea de orden de producción porque una tarea no cancelada
 * o una unidad de bodega todavía la cita. El controlador la responde con 409 y la
 * transacción no escribe nada.
 */
public class ProductionOrderItemConflictException extends RuntimeException {

    public ProductionOrderItemConflictException(String message) {
        super(message);
    }
}
