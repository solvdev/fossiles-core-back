package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseNumberRequest {
    @Size(max = 50, message = "Purchase number must not exceed 50 characters")
    private String purchaseNumber; // Opcional, se genera automáticamente si no se proporciona

    @Size(max = 30, message = "Status must not exceed 30 characters")
    private String status; // PENDIENTE, PAGADO, TERMINADO

    @Size(max = 500, message = "Description must not exceed 500 characters")
    private String description;

    private java.math.BigDecimal totalAmount; // Cantidad total asignada a liquidar
}

