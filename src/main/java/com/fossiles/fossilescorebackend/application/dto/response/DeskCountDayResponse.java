package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

@Data
@Builder
public class DeskCountDayResponse {
    private LocalDate effectiveDate;
    private Integer numDesks;

    /** Clave de configuración resuelta cuando no existe override en producción. */
    private String resolvedKey;
    private boolean isDefault;
}

