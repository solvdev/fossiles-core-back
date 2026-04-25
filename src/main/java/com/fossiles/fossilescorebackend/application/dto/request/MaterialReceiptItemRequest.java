package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.NotNull;
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
public class MaterialReceiptItemRequest {
    @NotNull(message = "Material ID is required")
    private Long materialId;

    @NotNull(message = "Quantity received is required")
    private BigDecimal quantityReceived;

    private BigDecimal unitPriceReceived; // Si no se envía, se usa el precio de la orden
    
    private Long supplierId; // Proveedor asignado para este item (opcional)
    
    private LocalDate receiptDate; // Fecha específica de recepción para este item (opcional)
}

