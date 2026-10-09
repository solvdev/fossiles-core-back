package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** GET /api/kiosk-financials/completeness. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskFinancialsCompletenessResponse {

    private Integer year;
    private List<Site> sites;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Site {
        private Long siteId;
        private String name;
        private List<Month> months;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Month {
        private Integer month;
        private Boolean hasSales;
        private Boolean hasCosts;
        private Boolean hasGoal;
    }
}
