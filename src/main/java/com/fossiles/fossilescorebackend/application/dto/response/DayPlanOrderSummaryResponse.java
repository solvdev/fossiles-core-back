package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/** Resumen ligero de una OP para el paso 1 de «Armar el día»: sin ítems. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DayPlanOrderSummaryResponse {
    private Long id;
    private String code;
    private String orderType;
    private String family;
    private boolean onlineSale;
    private String status;
    private String customerName;
    /** OPV de Luis Felipe: se puede juntar con otras en una OP consolidada. */
    private boolean luisFelipe;
    /** OP consolidada activa a la que pertenece (null si va suelta). */
    private Long groupId;
    private String groupCode;
    private LocalDate deliveryDate;
    /** Horas de producción de toda la OP (sin cinchos). */
    private double totalHours;
    /** Horas de lo que aún no está en ninguna tarea. */
    private double remainingHours;
    /** Ítems de la OP (sin cinchos). */
    private int itemCount;
    /** Ítems con cantidad restante. */
    private int remainingItemCount;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Page {
        private List<DayPlanOrderSummaryResponse> content;
        private long totalElements;
        private int totalPages;
        private int size;
        private int number;
        private boolean first;
        private boolean last;
    }
}
