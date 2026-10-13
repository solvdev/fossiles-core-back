package com.fossiles.fossilescorebackend.application.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Cuerpo de {@code POST /api/sales/online/ad-spend/bulk}. {@code amount = null} borra la captura de ese día. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OnlineAdSpendBulkRequest {
    private List<Entry> entries;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Entry {
        private LocalDate date;
        private BigDecimal amount;
        private String notes;
    }
}
