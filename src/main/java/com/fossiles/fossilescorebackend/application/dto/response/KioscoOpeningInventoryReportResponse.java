package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioscoOpeningInventoryReportResponse {
    private Long id;
    private Long locationId;
    private String locationName;
    private String locationCode;
    private String status;
    private String notes;
    private Long createdBy;
    private String createdByName;
    private Long appliedBy;
    private String appliedByName;
    private LocalDateTime appliedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private int itemCount;
    private List<ItemRow> items;
    /** Advertencias al aplicar (p. ej. movimientos previos al inventario inicial). */
    private List<String> warnings;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ItemRow {
        private Long productId;
        private String productCode;
        private String productName;
        private Long colorId;
        private String colorName;
        private Integer quantity;
        private Map<String, Integer> sizes;
        private String sizesSummary;
        private boolean packaging;
    }
}
