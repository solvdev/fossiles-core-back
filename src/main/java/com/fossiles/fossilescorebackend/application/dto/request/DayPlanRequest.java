package com.fossiles.fossilescorebackend.application.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * «Armar el día»: las tareas borrador que el jefe quiere crear, para medirlas (vista previa)
 * o crearlas (confirmar). Lo mismo viaja en los dos pasos; confirmar suma la huella.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DayPlanRequest {
    /** Primer día desde el que se reparte; si cae en fin de semana se pasa al lunes. */
    private LocalDate anchorDate;
    /** Días hábiles en los que se busca mesa (por defecto 5, máximo 10). */
    private Integer horizonDays;
    /** Prioridad manual (2..99) por id de OP. La vista previa no la guarda; confirmar sí. */
    private Map<String, Integer> orderPriorities;
    /** Solo confirmar: huella de la vista previa que el jefe revisó. */
    private String planFingerprint;
    /** Solo confirmar: aplicar aunque la huella ya no coincida. */
    private Boolean acceptChanges;
    private List<Draft> tasks;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Draft {
        /** Identificador que elige el front para reconocer la tarea en la respuesta. */
        private String clientId;
        private Long productionOrderId;
        private List<CreateManualTaskRequest.ManualTaskItemRequest> items;
        /** null = el sistema propone el día. */
        private LocalDate scheduledDate;
        private String observations;
        /** true = la tarea junta ítems de varias OPV de Luis Felipe (la OP base es la cabecera). */
        private Boolean consolidateVendorOrders;
    }
}
