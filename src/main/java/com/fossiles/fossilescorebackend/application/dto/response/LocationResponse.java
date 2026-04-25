package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
}

