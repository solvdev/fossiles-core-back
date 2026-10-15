package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Consulta del kardex archivado por limpiezas de inventario (solo lectura). */
public final class ProductKardexHistoricoResponse {

    private ProductKardexHistoricoResponse() {
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Row {
        private Long id;
        private LocalDateTime movementDate;
        private Long locationId;
        private String locationCode;
        private String locationName;
        private Long productId;
        private String productCode;
        private String productName;
        private Long colorId;
        private String colorName;
        private String sizeLabel;
        private String movementType;
        private BigDecimal quantity;
        private BigDecimal quantityBefore;
        private BigDecimal quantityAfter;
        private BigDecimal unitCost;
        private BigDecimal totalCost;
        private String referenceType;
        private Long referenceId;
        private String referenceNumber;
        private String description;
        private String archiveReference;
        private LocalDateTime archivedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Page {
        private List<Row> content;
        private long totalElements;
        private int totalPages;
        private int size;
        private int number;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Archive {
        private String archiveReference;
        private LocalDateTime archivedAt;
        private long movements;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Filters {
        private List<Archive> archives;
        private List<String> movementTypes;
    }
}
