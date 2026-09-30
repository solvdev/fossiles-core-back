package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** GET /api/kiosk-financials/config. Porcentajes decimales (0.18 = 18 %). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskFinancialsConfigResponse {

    private Integer year;
    private List<Category> categories;
    private List<Site> sites;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Category {
        private String code;
        private String name;
        private Integer sortOrder;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Site {
        private Long siteId;
        private String name;
        private String status;
        /** true: kiosco real, su meta se administra en el modulo Metas de kioscos (solo lectura aqui). */
        private Boolean goalManagedExternally;
        private List<Month> months;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Month {
        private Integer month;
        /** Meta efectiva: modulo Metas de kioscos primero; si no hay, la de respaldo de Finanzas. */
        private BigDecimal goal;
        /** METAS_KIOSCOS | CONFIG | null (sin meta). */
        private String goalSource;
        private BigDecimal productCostPct;
        private BigDecimal salesCommissionPct;
        private BigDecimal cardCommissionPct;
        private BigDecimal taxPct;
        /** EXCEL | COPIED | MANUAL; null si el mes no tiene configuracion. */
        private String source;
        private Map<String, BigDecimal> costs;
        private Boolean complete;
    }
}
