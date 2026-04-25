package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnvioRequest {
    
    @NotNull(message = "Location ID es requerido")
    private Long locationId;
    
    private LocalDate fechaEnvio;
    
    private String observaciones;
    
    @NotEmpty(message = "Debe incluir al menos un producto")
    private List<EnvioDetalleRequest> productos;
}

