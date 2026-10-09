package com.fossiles.fossilescorebackend.application.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/** POST /api/kiosk-financials/sites: crea un sitio historico (sin location). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskFinancialsSiteCreateRequest {
    private String name;
    /** ACTIVE (default) | CLOSED. */
    private String status;
    private LocalDate closedOn;
}
