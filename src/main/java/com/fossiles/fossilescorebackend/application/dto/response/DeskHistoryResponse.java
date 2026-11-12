package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Historial de mesas: qué tareas (y de qué órdenes) pasaron por cada mesa y cuánto tardaron.
 * La pantalla arma los resúmenes con estas filas, así los filtros nunca desfasan los totales.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeskHistoryResponse {

    private LocalDate from;
    private LocalDate to;
    private List<DeskInfo> desks;
    private List<Entry> tasks;

    /** Una mesa del rango (de planta, especial o que solo aparece en el historial). */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeskInfo {
        private Integer desk;
        /** Nombre de la mesa especial («Maquila»); null en las de planta. */
        private String name;
        private boolean special;
        /** Encargado vigente al final del rango. */
        private String supervisorName;
    }

    /** Una tarea en una mesa. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Entry {
        private Long taskId;
        private String code;
        /** Mesa donde se trabajó (la actual si sigue abierta, o la que quedó al completar). */
        private Integer desk;
        /** Día al que cuenta: el del cierre; si no cerró, el programado. */
        private LocalDate day;
        private String supervisorName;
        private Long productionOrderId;
        private String productionOrderCode;
        private String status;
        private LocalDate scheduledDate;
        private LocalDateTime startedAt;
        private LocalDateTime completedAt;
        /** Pausada ahora mismo (sigue en proceso pero el reloj no corre). */
        private boolean paused;
        /** Minutos de jornada trabajados; null si todavía no se empezó. */
        private Integer workedMinutes;
        /** Minutos que se esperaban según las horas estimadas de las líneas. */
        private Integer expectedMinutes;
        private Integer delayMinutes;
        private boolean delayed;
        private boolean delayForgiven;
        private Integer pieces;
        private Integer wasteQuantity;
        private List<Item> items;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        private String productCode;
        private String productName;
        private String colorName;
        private Integer quantity;
    }
}
