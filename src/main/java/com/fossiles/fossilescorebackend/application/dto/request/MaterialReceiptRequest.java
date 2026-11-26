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

    // Factura del proveedor. En la edición, nulo = no tocar; vacío = quitarla.
    @Size(max = 20, message = "La serie de la factura no debe pasar de 20 caracteres")
    private String invoiceSeries;

    @Size(max = 40, message = "El número de factura no debe pasar de 40 caracteres")
    private String invoiceNumber;

    private LocalDate invoiceDate;

    // La creación exige cantidades explícitas; la edición sin ítems solo cambia los datos generales.
    @jakarta.validation.Valid
    private List<MaterialReceiptItemRequest> items;
}

