package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaterialReceiptRequest {
    @NotNull(message = "Purchase Order ID is required")
    private Long purchaseOrderId;

    private LocalDate receiptDate;

    @Size(max = 1000, message = "Observations must not exceed 1000 characters")
    private String observations;

    // Items recibidos (opcional, si no se envía se usa la cantidad ordenada)
    private List<MaterialReceiptItemRequest> items;
}

