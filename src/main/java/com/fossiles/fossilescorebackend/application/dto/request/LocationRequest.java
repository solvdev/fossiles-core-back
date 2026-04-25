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
}

