package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductionOrderItemRequest {
    private Long id; // Para updates

    @NotNull(message = "Product ID is required")
    private Long productId;

    private Long colorId;

    @Size(max = 100, message = "Brand name must not exceed 100 characters")
    private String brandName;

    private Integer quantity; // Para productos normales

    // Para cinchos: mapa de talla -> cantidad
    // Ejemplo: {"16": 3, "18": 5, "20": 2, ...}
    private Map<String, Integer> sizes; // Talla -> Cantidad

    @Size(max = 500, message = "Observations must not exceed 500 characters")
    private String observations;

    /** Precio unitario acordado (OPV/MARCAS). */
    private BigDecimal unitPrice;
}

