package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskPosReportsResponse {
    private LocalDate startDate;
    private LocalDate endDate;
    private Integer salesCount;
    private BigDecimal totalItems;
    private BigDecimal totalAmount;
    private BigDecimal averageTicket;
    private List<KioskSummary> kiosks;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KioskSummary {
        private Long kioskId;
        private String kioskCode;
        private String kioskName;
        private Integer salesCount;
        private BigDecimal totalItems;
        private BigDecimal totalAmount;
    }
}
