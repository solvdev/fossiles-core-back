package com.fossiles.fossilescorebackend.application.exception;

import java.util.List;

/**
 * Conflicto al armar o despachar cajas de Luis Felipe (alguien más tomó las piezas, faltan piezas por recibir,
 * la caja ya cambió de estado...). Se responde 409 con el detalle y la transacción no escribe nada.
 */
public class LfPackingConflictException extends RuntimeException {

    private final String code;
    private final List<String> details;

    public LfPackingConflictException(String code, String message) {
        this(code, message, List.of());
    }

    public LfPackingConflictException(String code, String message, List<String> details) {
        super(message);
        this.code = code;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public String getCode() {
        return code;
    }

    public List<String> getDetails() {
        return details;
    }
}
