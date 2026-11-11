package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
public class DeskCountDayResponse {
    private LocalDate effectiveDate;
    private Integer numDesks;

    /** Clave de configuración resuelta cuando no existe override en producción. */
    private String resolvedKey;
    private boolean isDefault;

    /** Mesas especiales activas (maquila): fuera de 1..numDesks, no entran al reparto ni al cupo. */
    private List<SpecialDeskResponse> specialDesks;
}

