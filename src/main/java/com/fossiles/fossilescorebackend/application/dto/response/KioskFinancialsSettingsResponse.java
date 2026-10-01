package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** GET/PUT /api/kiosk-financials/settings. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskFinancialsSettingsResponse {
    /** RATES (tasas reales de cada kiosco) | FLAT (tasa fija). */
    private String breakEvenMode;
    /** Tasa variable que usa el modo FLAT (0.27). */
    private BigDecimal flatBreakEvenRate;
    /** false si falta ejecutar scripts/migration-kiosk-financials-settings.sql (no se puede guardar). */
    private Boolean persisted;
    private LocalDateTime updatedAt;
}
