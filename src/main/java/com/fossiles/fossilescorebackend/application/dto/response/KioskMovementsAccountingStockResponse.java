package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskMovementsAccountingStockResponse {
    private Long id;
    private Long locationId;
    private String kiosko;
    private String codigoKiosko;
    private Long productId;
    private String codigoProducto;
    private String producto;
    private Long colorId;
    private String color;
    private Integer cantidad;
    private Integer minimo;
    private String herraje;
    private Map<String, Integer> tallas;
}
