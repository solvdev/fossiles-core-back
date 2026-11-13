package com.fossiles.fossilescorebackend.application.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
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
    /** FACT, FCAM, NCRE o NDEB; por defecto FACT si viene vacío. */
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

    /** Documento origen de una NCRE/NDEB (complemento ReferenciasNota); null en facturas. */
    private NoteReference noteReference;

    @Builder.Default
    private List<Line> lines = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NoteReference {
        /** UUID (número de autorización) de la factura origen. */
        private String originAuthorizationNumber;
        private String originSerie;
        private String originNumero;
        /** Fecha de emisión registrada en SAT para la factura origen (zona Guatemala). */
        private LocalDate originEmissionDate;
        private String adjustmentReason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Line {
        /** Código de producto (parte anterior al {@code |} en {@code dte:Descripcion} para plantilla INFILE). */
        private String productCode;
        private String description;
        private BigDecimal quantity;
        private BigDecimal unitPrice;
        private BigDecimal lineTotal;
        /**
         * Línea B-1 cobrada por paquete ({@code chargedLineTotal}).
         * Solo esas líneas ajustan el unitario del XML FEL.
         */
        private boolean bundlePriced;

        /**
         * Precio unitario editado por debajo del catálogo y guardado como unitario × cantidad.
         * El XML usa ese unitario y el descuento de la línea. Las líneas de lista no lo marcan.
         */
        private boolean editedUnitPrice;

        /**
         * Descuento de la promoción sobre esta línea (kiosk_sale_item.discount_amount). Null en ventas
         * sin promoción dinámica: el descuento del documento se prorratea entre las líneas.
         */
        private BigDecimal lineDiscount;
    }
}
