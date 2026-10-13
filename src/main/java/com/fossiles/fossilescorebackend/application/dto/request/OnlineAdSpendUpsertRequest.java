package com.fossiles.fossilescorebackend.application.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Cuerpo de {@code PUT /api/sales/online/ad-spend/{date}}. Las reglas del monto y las notas se validan en el servicio. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OnlineAdSpendUpsertRequest {
    private BigDecimal amount;
    private String notes;
}
