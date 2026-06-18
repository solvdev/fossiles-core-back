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
    private String customerTaxId;
    private String customerName;
    private String address;
    private String phone;
    private String email;
    private BigDecimal subtotal;
    private BigDecimal discountAmount;
    private BigDecimal totalAmount;

    /** Overrides emisor FEL por kiosko (CodigoEstablecimiento, direccion). */
    private String emitterEstablishmentCode;
    private String emitterAddressLine;
    private String emitterMunicipio;
    private String emitterDepartamento;

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
