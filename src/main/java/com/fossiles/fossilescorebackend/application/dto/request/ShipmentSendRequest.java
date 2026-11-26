package com.fossiles.fossilescorebackend.application.dto.request;

import lombok.Data;

import java.util.List;

/** Cuerpo opcional al marcar un envío como enviado. */
@Data
public class ShipmentSendRequest {
    /** Líneas (id de detalle) que salen completas de la cubeta de respaldo en vez de la principal. */
    private List<Long> fromFallbackDetailIds;
}
