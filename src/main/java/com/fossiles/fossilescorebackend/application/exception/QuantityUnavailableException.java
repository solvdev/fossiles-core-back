package com.fossiles.fossilescorebackend.application.exception;

/**
 * La cantidad pedida de un ítem de OP ya no está disponible (alguien más la tomó o el borrador
 * pide más de lo que queda). Es una {@link BusinessException} para no cambiar a quien ya la
 * captura, pero «Armar el día» la distingue para responder 409 QTY_UNAVAILABLE.
 */
public class QuantityUnavailableException extends BusinessException {
    public QuantityUnavailableException(String message) {
        super(message);
    }
}
