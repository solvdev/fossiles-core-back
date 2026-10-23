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
    /** Capacidad del primer día de la propuesta (atajo de {@code capacities[0]}). */
    private Capacity capacity;
    /** Capacidad de cada día que toca la propuesta. */
    private List<Capacity> capacities;
    /**
     * Lo que no cupo en el horizonte: no se convierte en tareas ni se crea. Una fila por ítem
     * con su cantidad, para ofrecerlo otro día.
     */
    private List<Leftover> leftovers;
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
        /** OPL que se combinó en otra tarea: clientId de la tarea que la lleva (no se crea aparte). */
        private String mergedInto;
        /** clientId de las OPL que esta tarea lleva combinadas, como primeras líneas. */
        private List<String> includes;
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

    /**
     * Capacidad de un día = mesas activas de esa fecha × 5 h (tope duro; 4 h es lo ideal por mesa).
     * Las horas son base: las de OPL no cuentan.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Capacity {
        private LocalDate date;
        private int activeDesks;
        private double idealHoursPerDesk;
        private double hardHoursPerDesk;
        /** Horas ya planificadas ese día (tareas existentes, incluidas las sin mesa). */
        private double plannedHours;
        /** activeDesks × hardHoursPerDesk. */
        private double totalHours;
        /** totalHours − plannedHours, antes de esta propuesta (mínimo 0). */
        private double remainingHours;
        /** Horas base que esta propuesta pone ese día. */
        private double requestedHours;
        /** Lo que se pasa de la capacidad: plannedHours + requestedHours − totalHours (mínimo 0). */
        private double excessHours;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Leftover {
        private String clientId;
        private Long productionOrderId;
        private Long productionOrderItemId;
        private int quantity;
        private double hours;
        private String reason;
        private LocalDate nextDayCandidate;
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
