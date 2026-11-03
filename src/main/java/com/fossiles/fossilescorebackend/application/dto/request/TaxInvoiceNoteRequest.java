package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Emisión de Nota de Crédito (NCRE) o Débito (NDEB) FEL sobre una factura certificada. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaxInvoiceNoteRequest {

    @NotBlank(message = "Indique si es nota de crédito (NCRE) o débito (NDEB).")
    @Pattern(regexp = "(?i)NCRE|NDEB", message = "El tipo de nota debe ser NCRE o NDEB.")
    private String documentType;

    /** MotivoAjuste del complemento ReferenciasNota. */
    @NotBlank(message = "El motivo del ajuste es obligatorio.")
    @Size(max = 255, message = "El motivo del ajuste admite máximo 255 caracteres.")
    private String reason;

    private String notes;

    /** Montos con IVA incluido, igual que en facturas manuales. */
    @NotEmpty(message = "Debe incluir al menos una línea.")
    @Valid
    private List<ManualTaxInvoiceRequest.LineRequest> lines;
}
