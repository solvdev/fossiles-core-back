package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Resumen de una sesion de conteo fisico para el historial por kiosko. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioscoPhysicalCountSessionSummaryResponse {
    private Long id;
    private LocalDate periodFrom;
    private LocalDate periodTo;
    private LocalDateTime periodFromAt;
    private LocalDateTime periodToAt;
    private String status;
    private String notes;
    private String observations;
    private String generatedByName;
    private LocalDateTime generatedAt;
    private String reviewedByName;
    private LocalDateTime reviewedAt;
    private String closedByName;
    private LocalDateTime closedAt;
    private int maxAbsDiff;
}
