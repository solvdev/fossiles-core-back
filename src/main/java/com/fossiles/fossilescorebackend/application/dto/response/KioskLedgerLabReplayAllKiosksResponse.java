package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskLedgerLabReplayAllKiosksResponse {
    private int locationCount;
    private int stockCount;
    private List<LocationResult> locations;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LocationResult {
        private Long locationId;
        private String locationName;
        private int stockCount;
    }
}
