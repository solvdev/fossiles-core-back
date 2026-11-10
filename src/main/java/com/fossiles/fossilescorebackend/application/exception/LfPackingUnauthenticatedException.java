package com.fossiles.fossilescorebackend.application.exception;

/**
 * Las cajas de Luis Felipe se pidieron sin sesión válida (token ausente o vencido). Responde 401 para que la app
 * móvil renueve el token o pida iniciar sesión; con otro rol la respuesta es 403 ({@link LfPackingForbiddenException}).
 */
public class LfPackingUnauthenticatedException extends RuntimeException {

    public LfPackingUnauthenticatedException(String message) {
        super(message);
    }
}
