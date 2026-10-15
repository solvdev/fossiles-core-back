package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Vista previa de «Armar el día». La mesa de cada tarea es orientativa. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DayPlanPreviewResponse {
    /** Un solo reloj para toda la vista previa (descuento de las tareas IN_PROGRESS). */
    private LocalDateTime asOf;
    /** Huella de las cargas, restantes y prioridades con las que se calculó. */
    private String planFingerprint;
    private Limits limits;
    private List<TaskPreview> tasks;
    private List<DayLoad> dayLoads;
    private List<NotPlaced> notPlaced;
    /** true si alguna tarea es BLOCKED: no se puede confirmar. */
    private boolean blocked;

    public enum Status { OK, WARN, BLOCKED }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Limits {
        private double idealHours;
        private double maxHours;
        private int numDesks;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaskPreview {
        private String clientId;
        private Status status;
        private List<String> messages;
        private double baseHours;
        private double totalHours;
        /** OPL/OPCK que pasan del cupo el día ancla (solo informativo). */
        private boolean overcapAnchor;
        private LocalDate proposedDate;
        private LocalDate nextDayCandidate;
        /** Mesa orientativa; la real la pone el corte. null = ninguna mesa tiene lugar. */
        private Integer indicativeDesk;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DayLoad {
        private LocalDate date;
        private int desk;
        /** Horas que la mesa ya tiene ese día (incluye tareas sin mesa y las IN_PROGRESS). */
        private double currentHours;
        /** Horas que sumarían las tareas de esta propuesta. */
        private double addedHours;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NotPlaced {
        private String clientId;
        private String reason;
        private LocalDate nextDayCandidate;
    }
}
