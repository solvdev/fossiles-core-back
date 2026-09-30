package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskExcelPreviewResponse {

    private List<FilePreview> files;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FilePreview {
        private String fileName;
        private String sha256;
        private Integer year;
        private Integer month;
        private String sheetName;
        private AlreadyImported alreadyImported;
        private List<ColumnMapping> columns;
        private List<KioskExcelIssueDto> issues;
        private KioskExcelDataDto data;
        private Stats stats;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AlreadyImported {
        private Long batchId;
        private LocalDateTime createdAt;
        /** true si el sha256 coincide (mismo archivo); false si sólo coincide el año-mes. */
        private boolean sameFile;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ColumnMapping {
        private String excelName;
        private String normalized;
        private Long matchedSiteId;
        private String matchedSiteName;
        /** MATCHED | UNMATCHED | AMBIGUOUS */
        private String matchStatus;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Stats {
        private int columns;
        private int days;
        private int salesCells;
        /** Suma recalculada de ventas (incluye los valores sugeridos de celdas de texto reparables). */
        private BigDecimal salesTotal;
        /** Suma de las filas "Total" de la hoja (por kiosco). */
        private BigDecimal sheetTotal;
    }
}
