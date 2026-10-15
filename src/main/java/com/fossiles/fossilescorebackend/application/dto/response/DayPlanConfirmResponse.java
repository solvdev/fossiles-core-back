package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * Resultado de confirmar «Armar el día». Todas las tareas de la propuesta quedan creadas, sin
 * mesa y sin corte; {@code notPlaced} lista las que no tuvieron mesa libre en el horizonte
 * (creadas igual, en {@code nextDayCandidate}).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DayPlanConfirmResponse {
    private List<Created> created;
    private List<DayPlanPreviewResponse.NotPlaced> notPlaced;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Created {
        private String clientId;
        private Long taskId;
        private String code;
        private LocalDate scheduledDate;
        /** Mesa orientativa: la tarea queda con mesa NULL hasta marcar el corte. */
        private Integer indicativeDesk;
    }
}
