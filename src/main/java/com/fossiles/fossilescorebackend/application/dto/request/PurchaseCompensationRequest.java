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
public class PurchaseCompensationRequest {

    /** Compra de donde sale el sobrante */
    @NotNull(message = "La compra origen es requerida")
    private Long sourcePurchaseId;

    /** Compra que recibe la compensación */
    @NotNull(message = "La compra destino es requerida")
    private Long targetPurchaseId;

    /** Monto a compensar */
    @NotNull(message = "El monto es requerido")
    @Positive(message = "El monto debe ser positivo")
    private BigDecimal amount;

    /** Descripción o motivo */
    private String description;
}

