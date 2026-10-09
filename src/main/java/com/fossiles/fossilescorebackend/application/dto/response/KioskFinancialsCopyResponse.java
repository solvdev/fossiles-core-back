package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Respuesta de POST /config/copy. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskFinancialsCopyResponse {
    private int copiedMonths;
    private int copiedCells;
    private int skippedCells;
}
