package com.fossiles.fossilescorebackend.application.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** PUT /api/kiosk-financials/settings. Los campos null no se tocan. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskFinancialsSettingsRequest {
    /** RATES | FLAT. */
    private String breakEvenMode;
}
