package com.fossiles.fossilescorebackend.application.exception;

/** Ya hay una renta activa con la misma categoría y el mismo nombre. El controlador responde 409. */
public class OtherRentConflictException extends RuntimeException {

    public OtherRentConflictException(String message) {
        super(message);
    }
}
