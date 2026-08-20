package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Resumen de ventas por corte de conteo físico (Hoja Principal). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskMainSheetReportResponse {
    private Long physicalCountId;
    private LocalDate periodFrom;
    private LocalDate periodTo;
    /** Inicio inclusive del corte (wall-clock Guatemala), alineado al conteo físico. */
    private LocalDateTime periodFromAt;
    /** Fin inclusive del corte (wall-clock Guatemala), alineado al conteo físico. */
    private LocalDateTime periodToAt;
    private String physicalCountStatus;

    private Long kioskLocationId;
    private String kioskCode;
    private String kioskName;
    private String encargadaName;

    private String invoiceFrom;
    private String invoiceTo;

    private BigDecimal totalSold;
    private BigDecimal cardsTotal;
    private BigDecimal depositsTotal;
    private BigDecimal expensesTotal;
    private BigDecimal reconciledTotal;
    private BigDecimal difference;

    /** REVISADO Y CERTIFICADO POR (nombre del supervisor). */
    private String mainSheetCertifiedBy;
    /** REVISADO POR (segunda revisión). */
    private String mainSheetReviewedBy;
    /** INVENTARIO DIGITAL: fecha/hora de certificación. */
    private LocalDateTime mainSheetCertifiedAt;
    private LocalDate mainSheetInventoryFrom;
    private LocalDate mainSheetInventoryTo;
    private LocalDate mainSheetSalesFrom;
    private LocalDate mainSheetSalesTo;

    private List<DailySaleRow> dailySales;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DailySaleRow {
        private LocalDate saleDate;
        private BigDecimal amount;
    }
}
