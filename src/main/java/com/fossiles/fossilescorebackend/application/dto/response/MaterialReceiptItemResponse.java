package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaterialReceiptItemResponse {
    private Long materialId;
    private String materialSku;
    private String materialName;
    private BigDecimal quantityOrdered;
    private BigDecimal quantityReceived;
    private BigDecimal unitPriceOrdered;
    private BigDecimal unitPriceReceived;
    private BigDecimal subtotal;
    private BigDecimal quantityVariation;
    private BigDecimal priceVariation;
    private Long supplierId; // Proveedor asignado para este item
    private String supplierName; // Nombre del proveedor
    private LocalDate receiptDate; // Fecha específica de recepción para este item
}

