package com.fossiles.fossilescorebackend.application.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaxInvoiceDocument {

    private String transactionId;
    private LocalDateTime issuedAt;
    /** FACT (factura) o FCAM (factura cambiaria); por defecto FACT si viene vacío. */
    private String documentType;
    /** Número de control interno ya asignado (serie de ubicación + correlativo); se refleja en la Adenda del DTE. */
    private String internalNumber;
    private String customerTaxId;
    private String customerName;
    private String address;
    private String phone;
    private String email;
    private BigDecimal subtotal;
    private BigDecimal discountAmount;
    private BigDecimal totalAmount;

    /** Overrides emisor FEL por kiosko / establecimiento (RTU INFILE). */
    private String emitterEstablishmentCode;
    private String emitterCommercialName;
    private String emitterAddressLine;
    private String emitterMunicipio;
    private String emitterDepartamento;
    /** Código de serie de control interno de la ubicación emisora (ej. "A1"), usado para generar internalNumber. */
    private String locationInternalSeriesCode;

    @Builder.Default
    private List<Line> lines = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Line {
        private String description;
        private BigDecimal quantity;
        private BigDecimal unitPrice;
        private BigDecimal lineTotal;
    }
}
