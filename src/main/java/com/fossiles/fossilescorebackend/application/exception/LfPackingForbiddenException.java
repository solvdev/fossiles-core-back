package com.fossiles.fossilescorebackend.application.exception;

/** El usuario no tiene sesión o no es de Bodega / Administración: las cajas de Luis Felipe responden 403. */
public class LfPackingForbiddenException extends RuntimeException {

    public LfPackingForbiddenException(String message) {
        super(message);
    }
}
