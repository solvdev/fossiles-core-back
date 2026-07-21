package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LocationResponse {
    private Long id;
    private String code;
    private String name;
    private String departamento;
    private String municipio;
    private String zona;
    private String categoria;
    private Long encargadoId;
    private String encargadoNombre;
    private String felEstablishmentCode;
    private String felEstablishmentName;
    private String felAddressLine;
    private String felMunicipio;
    private String felDepartamento;
    private Boolean posTestMode;
    /** Fondo inicial de caja POS al abrir turno. */
    private BigDecimal posOpeningCashAmount;
    /** Código de serie de control interno (ej. A45, B). */
    private String internalSeriesCode;
}

