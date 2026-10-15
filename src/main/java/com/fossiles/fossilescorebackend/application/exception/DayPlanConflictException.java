package com.fossiles.fossilescorebackend.application.exception;

import com.fossiles.fossilescorebackend.application.dto.response.DayPlanPreviewResponse;

import java.util.List;

/**
 * «Armar el día» no pudo confirmar. Es una {@link RuntimeException} para que la transacción
 * revierta y no quede ninguna tarea a medias (todo o nada); el controlador la traduce a 409
 * (o 422 si la propuesta no es válida) con el código que el front distingue.
 */
public class DayPlanConflictException extends RuntimeException {

    public static final String PLAN_CHANGED = "PLAN_CHANGED";
    public static final String QTY_UNAVAILABLE = "QTY_UNAVAILABLE";
    public static final String LOCK_TIMEOUT = "LOCK_TIMEOUT";
    /** La propuesta tiene tareas BLOCKED que no son de cantidad (tope de 5 h, fin de semana…). */
    public static final String PLAN_BLOCKED = "PLAN_BLOCKED";

    public record Failure(String clientId, String message) {}

    private final String code;
    private final transient List<Failure> failures;
    private final transient DayPlanPreviewResponse preview;

    public DayPlanConflictException(String code, String message, List<Failure> failures,
                                    DayPlanPreviewResponse preview) {
        super(message);
        this.code = code;
        this.failures = failures == null ? List.of() : failures;
        this.preview = preview;
    }

    public String getCode() {
        return code;
    }

    public List<Failure> getFailures() {
        return failures;
    }

    public DayPlanPreviewResponse getPreview() {
        return preview;
    }
}
