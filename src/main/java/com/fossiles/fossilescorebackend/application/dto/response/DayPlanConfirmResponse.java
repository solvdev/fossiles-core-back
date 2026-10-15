package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * Resultado de confirmar «Armar el día». Las tareas que cupieron quedan creadas, sin mesa y sin
 * corte; las que no tuvieron lugar en el horizonte NO se crean: van en {@code notPlaced} y sus
 * ítems en {@code leftovers}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DayPlanConfirmResponse {
    private List<Created> created;
    private List<DayPlanPreviewResponse.NotPlaced> notPlaced;
    /** Ítems y cantidades que no cupieron: no se crearon, quedan para otro día. */
    private List<DayPlanPreviewResponse.Leftover> leftovers;

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
