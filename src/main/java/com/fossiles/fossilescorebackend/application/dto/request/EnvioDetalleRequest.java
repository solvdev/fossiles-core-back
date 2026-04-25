package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnvioDetalleRequest {
    
    @NotNull(message = "Product ID es requerido")
    private Long productId;
    
    @NotNull(message = "Cantidad es requerida")
    @Positive(message = "Cantidad debe ser mayor a 0")
    private BigDecimal cantidad;
}

