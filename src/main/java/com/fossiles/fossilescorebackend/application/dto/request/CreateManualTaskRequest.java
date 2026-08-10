package com.fossiles.fossilescorebackend.application.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * Creación manual de una tarea desde el Organizador: ítems de OP con cantidad
 * (parcial o total). Las líneas OPL (VENTA_EN_LINEA) se tratan siempre como
 * daySaleExtra y no cuentan contra el cupo de 4/5 horas.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateManualTaskRequest {
    /** OP base de la tarea (encabezado). */
    private Long productionOrderId;
    private List<ManualTaskItemRequest> items;
    /** Mesa opcional; null = tarea sin asignar (se arrastra luego en el tablero). */
    private Integer desk;
    /** Fecha opcional. */
    private LocalDate scheduledDate;
    private String observations;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ManualTaskItemRequest {
        private Long productionOrderItemId;
        private Integer quantity;
        /** true = extra sobre el cupo; las líneas OPL se fuerzan a true en el servidor. */
        private Boolean daySaleExtra;
    }
}
