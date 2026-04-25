package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaterialResponse {
    private Long id;
    private String sku;
    private String name;
    
    // Información de compra
    private Long purchaseUomId;
    private String purchaseUomName;        // "Rollo", "Gruesa", "Caja"
    private String purchaseUomCode;        // "rollo", "gruesa", "caja"
    private BigDecimal purchaseQuantity;    // 25, 144, 100
    private BigDecimal purchasePrice;       // Q 50.00
    
    // Información de manufactura
    private Long manufacturingUomId;
    private String manufacturingUomName;        // "Pulgadas", "Unidades", "Metros"
    private String manufacturingUomCode;        // "in", "uds", "m"
    private BigDecimal unitCost;               // Q 2.00
    
    // Información de stock (en unidades de manufactura)
    private BigDecimal currentStock;
    
    // Texto descriptivo de conversión (calculado)
    private String conversionText; // "1 rollo = 25 pulgadas"
    private String priceBreakdown;  // "Q 50.00/rollo = Q 2.00/pulgada"
    
    // Campos legacy (mantener por compatibilidad)
    private Long uomId;
    private BigDecimal quantity;
    private BigDecimal cost;
    
    // Otros campos
    private Integer min;
    private Integer max;
    private Integer deliveryDays;
    private Long materialColorId;
    private Long supplierId;
    private String supplierName;  // Nombre del proveedor
    private String description;
    private String status;
    private BigDecimal lossPercentage;
    private String imageUrl;
    private Boolean isPrimaryLeather;
    private LocalDateTime createdAt;
    private Long createdBy;
    private LocalDateTime updatedAt;
    private Long updatedBy;
}

