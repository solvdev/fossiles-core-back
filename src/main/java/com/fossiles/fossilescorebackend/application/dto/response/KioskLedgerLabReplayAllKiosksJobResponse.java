package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Estado del job en background de "Replay stock de todos los kioscos". Se consulta por
 * polling desde el frontend para evitar que un proxy/gateway corte la conexión (504) mientras
 * el recorrido de todos los kioscos toma varios minutos.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskLedgerLabReplayAllKiosksJobResponse {
    /** IDLE (nunca corrido), RUNNING, DONE, ERROR. */
    private String status;
    private int locationsTotal;
    private int locationsDone;
    private int stockCount;
    private String currentLocationName;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
