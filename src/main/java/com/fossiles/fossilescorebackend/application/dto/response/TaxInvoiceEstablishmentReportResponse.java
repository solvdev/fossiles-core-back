package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Reporte de facturas FEL por establecimiento (formato tipo reporte SAT: una fila por documento, sin detalle).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaxInvoiceEstablishmentReportResponse {

    private List<Row> rows;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Row {
        private Long id;
        /** Código FEL del establecimiento emisor (ej. "1", "41"); vacío si no se pudo determinar. */
        private String establishmentCode;
        /** "1 - CUEROGLAM". */
        private String establishmentLabel;
        private String felSerie;
        private String felNumero;
        /** Número interno (columna «adendas» del reporte SAT). */
        private String internalNumber;
        /** FACT, FCAM, NCRE, NDEB. */
        private String documentType;
        /** FACTURA, FACTURA CAMBIARIA, NOTA DE CRÉDITO, NOTA DE DÉBITO. */
        private String documentTypeLabel;
        private String customerTaxId;
        private String customerName;
        /** EMITIDO o ANULADO. */
        private String status;
        private LocalDateTime issuedAt;
        private BigDecimal totalAmount;
        private String sourceType;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EstablishmentOption {
        private String code;
        private String name;
        private String label;
    }
}
