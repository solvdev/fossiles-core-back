package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DistribucionRequest {
    
    @NotNull(message = "Fecha es requerida")
    private LocalDate fecha;
    
    private String descripcion;
    
    private String estado;
}

