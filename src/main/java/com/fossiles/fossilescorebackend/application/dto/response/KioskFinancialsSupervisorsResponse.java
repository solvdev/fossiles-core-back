package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Supervisoras (módulo "Supervisoras y kioscos") con los sitios de Finanzas de sus kioscos asignados. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskFinancialsSupervisorsResponse {

    private List<Supervisor> supervisors;
    /** Kioscos reales (con POS) que ninguna supervisora tiene asignados. */
    private List<Long> unassignedSiteIds;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Supervisor {
        private Long userId;
        private String name;
        /** {@code kiosk_site.id} de sus kioscos (sin los sitios externos). */
        private List<Long> siteIds;
    }
}
