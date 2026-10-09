package com.fossiles.fossilescorebackend.application.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * PUT /api/kiosk-financials/sites/{id}. Los campos null no se tocan.
 * {@code posGoLiveOverride} solo se limpia con {@code clearGoLiveOverride = true}.
 * {@code aliases}, si viene, reemplaza la lista completa (se normalizan).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskFinancialsSiteUpdateRequest {
    private String name;
    private String status;
    private LocalDate closedOn;
    private LocalDate posGoLiveOverride;
    private Boolean clearGoLiveOverride;
    /** true = sitio externo, fuera de todos los reportes; false = vuelve a entrar. null no se toca. */
    private Boolean excludeFromReports;
    /** "A", "B" o "C"; cadena vacia = quitar la categoria. null no se toca. */
    private String salesCategory;
    private List<String> aliases;
}
