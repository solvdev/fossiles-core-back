package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartialReleaseResponse {
    private Long id;
    private Long productionOrderId;
    private Integer sequence;
    private String label;
    private String status;
    private String notes;
    private Long shipmentId;
    private String shipmentNumber;
    /** Estado real del envío ligado (DRAFT, CONFIRMED, SENT, …). */
    private String shipmentStatus;
    private List<PartialReleaseLineResponse> lines;
    /** Filas en production_order_partial_release_line. */
    private Integer lineCount;
    /** Filas con cantidad o tallas > 0 (listas para envío). */
    private Integer savedLineCount;
    /** Suma de unidades de esas líneas. */
    private Integer totalUnits;
    private Long createdBy;
    private String createdByName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
