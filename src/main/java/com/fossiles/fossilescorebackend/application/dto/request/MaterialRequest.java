package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaterialRequest {
    @Size(max = 30, message = "SKU must not exceed 30 characters")
    private String sku;

    @Size(max = 150, message = "Name must not exceed 150 characters")
    private String name;

    // Información de compra
    @NotNull(message = "Purchase UOM ID is required")
    private Long purchaseUomId; // Unidad en que se COMPRA (ej: Rollo, Gruesa, Caja)
    
    @DecimalMin(value = "0.0", inclusive = false, message = "Purchase quantity must be greater than 0")
    private BigDecimal purchaseQuantity; // Cantidad en manufacturing_uom que contiene 1 purchase_uom
    
    @DecimalMin(value = "0.0", message = "Purchase price must be positive")
    private BigDecimal purchasePrice; // Precio de 1 unidad de compra completa
    
    // Información de manufactura
    @NotNull(message = "Manufacturing UOM ID is required")
    private Long manufacturingUomId; // Unidad en que se USA en producción
    
    // Campos legacy (mantener por compatibilidad)
    private Long uomId;
    private BigDecimal quantity;
    private BigDecimal cost;

    private Integer min;

    private Integer max;

    private Integer deliveryDays;

    private Long materialColorId;

    private Long supplierId;

    @Size(max = 255, message = "Description must not exceed 255 characters")
    private String description;

    @Size(max = 20, message = "Status must not exceed 20 characters")
    private String status;

    @DecimalMin(value = "0.0", message = "Cost must be positive")
    private BigDecimal lossPercentage; // Costo unitario (opcional, se calcula automáticamente: purchasePrice / quantity)

    @Size(max = 500, message = "Image URL must not exceed 500 characters")
    private String imageUrl;

    private Boolean isPrimaryLeather;
}

