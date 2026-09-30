package com.fossiles.fossilescorebackend.application.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** POST /api/kiosk-financials/config/copy. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskFinancialsConfigCopyRequest {
    private Integer fromYear;
    private Integer fromMonth;
    private Integer toYear;
    private List<Integer> toMonths;
    /** null o vacio = todos los sitios. */
    private List<Long> siteIds;
    /** COSTS, RATES, GOALS. null o vacio = todo. */
    private List<String> include;
    /** false (default) no pisa celdas ya llenas. */
    private Boolean overwrite;
}
