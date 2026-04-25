package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaterialRequestItemResponse {
    private Long id;
    private Long materialRequestId;
    private Long materialId;
    private String materialSku;
    private String materialName;
    private BigDecimal quantityRequested;
    private Long uomId;
    private String uomName;
    private Long supplierId; // Proveedor asignado para este item
    private String supplierName; // Nombre del proveedor
}

