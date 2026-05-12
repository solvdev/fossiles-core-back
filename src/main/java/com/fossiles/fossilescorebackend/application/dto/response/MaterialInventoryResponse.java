package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Respuesta de inventario de materiales agregado (sin ubicación)
 * Muestra el stock total de cada material sumando todas las ubicaciones
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaterialInventoryResponse {
    private Long materialId;
    private String materialSku;
    private String materialName;
    private BigDecimal totalQuantity; // Suma de todas las ubicaciones
    private Long purchaseUomId;
    private String purchaseUomCode;
    private String purchaseUomName;
    private BigDecimal purchaseQuantity;
    private Long manufacturingUomId;
    private String manufacturingUomCode;
    private String manufacturingUomName;
    private String conversionText;
    private Integer materialMin;
    private Integer materialMax;
    /** Proveedor habitual del material (catálogo) */
    private Long supplierId;
    private String supplierName;
}

