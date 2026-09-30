package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Respuesta de PUT /config/bulk. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskFinancialsBulkResponse {
    private int updatedMonths;
    private int updatedCells;
}
