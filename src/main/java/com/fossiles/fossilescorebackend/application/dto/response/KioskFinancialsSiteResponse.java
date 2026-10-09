package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskFinancialsSiteResponse {
    private Long id;
    private String name;
    private Long locationId;
    private String locationCode;
    private String status;
    private LocalDate closedOn;
    private LocalDate posGoLiveOverride;
    private LocalDate posGoLiveDetected;
    private LocalDate goLiveEffective;
    private Boolean excludeFromReports;
    /** A, B, C o null (sin categoria). */
    private String salesCategory;
    private List<String> aliases;
}
