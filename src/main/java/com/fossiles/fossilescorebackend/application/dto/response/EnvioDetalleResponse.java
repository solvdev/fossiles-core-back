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
public class EnvioDetalleResponse {
    private Long id;
    private Long envioId;
    private Long productId;
    private String productCode;
    private String productName;
    private BigDecimal cantidad;
}

