package com.fossiles.fossilescorebackend.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Location {
    private Long id;
    private String code;
    private String name;
    private String departamento;
    private String municipio;
    private String zona;
}

