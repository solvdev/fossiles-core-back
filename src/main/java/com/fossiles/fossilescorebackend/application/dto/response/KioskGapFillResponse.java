package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Vista previa de la corrección de "días sin sistema" (POST /imports/gap-fill/preview). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskGapFillResponse {

    private String fileName;
    private String sha256;
    private Integer year;
    private Integer month;
    /** DATES | FILE_NAME | OVERRIDE. */
    private String periodSource;
    private Boolean periodEditable;
    /** Kioscos con algo que informar (candidatos, diferencias o huecos posteriores al go-live). */
    private List<Site> sites;
    /** Columnas del Excel que no entran (sin sitio, externas, históricas o sin ventas en el POS). */
    private List<IgnoredColumn> ignoredColumns;
    /** Celdas de texto no numérico que se ignoraron. */
    private List<SkippedCell> skippedCells;
    private Totals totals;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Site {
        private Long siteId;
        private String name;
        private String excelName;
        private LocalDate goLive;
        private List<Day> candidates;
        private BigDecimal candidateTotal;
        private List<Difference> differences;
        private List<Day> afterGoLiveGaps;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Day {
        private LocalDate date;
        private BigDecimal excelAmount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Difference {
        private LocalDate date;
        private BigDecimal excelAmount;
        private BigDecimal systemAmount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IgnoredColumn {
        private String excelName;
        private String reason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkippedCell {
        private String excelName;
        private LocalDate date;
        private String cell;
        private String rawValue;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Totals {
        private int sitesWithCandidates;
        private int candidateDays;
        private BigDecimal candidateAmount;
    }

    /** Resultado del commit (POST /imports/gap-fill/commit). */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Applied {
        private Long batchId;
        private Integer year;
        private Integer month;
        private int salesRows;
        private BigDecimal amount;
        private List<AppliedSite> sites;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AppliedSite {
        private Long siteId;
        private String name;
        private int days;
        private BigDecimal amount;
    }
}
