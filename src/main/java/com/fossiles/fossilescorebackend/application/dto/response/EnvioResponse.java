package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnvioResponse {
    private Long id;
    private Long distribucionId;
    private String numeroDistribucion;
    private String numeroEnvio;
    private Long locationId;
    private String locationCode;
    private String locationName;
    private String estado;
    private LocalDate fechaEnvio;
    private String observaciones;
    private Integer cantidadProductos;
    private List<EnvioDetalleResponse> productos;
    private LocalDateTime createdAt;
    private Long createdBy;
    private LocalDateTime updatedAt;
    private Long updatedBy;
}

