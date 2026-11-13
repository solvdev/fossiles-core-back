package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SpecialDeskRequest {
    @NotNull
    @Min(1)
    @Max(32)
    private Integer desk;

    /** Nombre que se ve en el tablero, p. ej. «Maquila». */
    private String name;

    /** Por ahora solo MAQUILA. */
    private String kind;

    private Boolean active;
}
