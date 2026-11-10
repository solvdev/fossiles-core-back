package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * Lo mínimo para pintar el Tablero de un día: las tareas activas de ese día (más las activas sin
 * mesa) y solo los conteos de las pestañas, sin cargar sus listas.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskBoardResponse {
    private LocalDate date;
    private List<TaskResponse> tasks;
    private Counts counts;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Counts {
        /** Pestaña «Por cortar». */
        private long dieCutPending;
        /** Pendientes atrasadas, sin fecha o sin mesa. */
        private long backlog;
        /** «No terminadas»: empezadas un día anterior y aún abiertas. */
        private long unfinished;
    }
}
