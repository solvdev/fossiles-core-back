package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.DecimalMin;
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
public class LocationRequest {
    @Size(max = 15, message = "Code must not exceed 15 characters")
    private String code;

    @Size(max = 255, message = "Name must not exceed 255 characters")
    private String name;

    @Size(max = 255, message = "Departamento must not exceed 255 characters")
    private String departamento;

    @Size(max = 255, message = "Municipio must not exceed 255 characters")
    private String municipio;

    @Size(max = 10, message = "Zona must not exceed 10 characters")
    private String zona;

    @Size(max = 100, message = "Categoria must not exceed 100 characters")
    private String categoria;

    private Long encargadoId;

    @Size(max = 10, message = "FEL establishment code must not exceed 10 characters")
    private String felEstablishmentCode;

    @Size(max = 255, message = "FEL establishment name must not exceed 255 characters")
    private String felEstablishmentName;

    @Size(max = 500, message = "FEL address must not exceed 500 characters")
    private String felAddressLine;

    @Size(max = 255, message = "FEL municipio must not exceed 255 characters")
    private String felMunicipio;

    @Size(max = 255, message = "FEL departamento must not exceed 255 characters")
    private String felDepartamento;

    /** Kiosko en piloto POS: ventas no suman en dashboard de producción. */
    private Boolean posTestMode;

    /** Fondo inicial de caja POS al abrir turno. */
    @DecimalMin(value = "0.01", message = "El fondo inicial POS debe ser mayor a cero")
    private BigDecimal posOpeningCashAmount;

    /** Código de serie de control interno (ej. A45, B). Prefijo de tax_invoice.internal_number. */
    @Size(max = 10, message = "Internal series code must not exceed 10 characters")
    private String internalSeriesCode;
}

