package com.fossiles.fossilescorebackend.application.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Bodega de salida elegida por Contabilidad al autorizar un envío interno. Opcional: sin ella sale de Devoluciones. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InternalShipmentApproveRequest {
    private List<LineSource> lines;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LineSource {
        private Long productId;
        private Long colorId;
        private String size;
        /** DEVOLUCIONES, BODEGA_PT o AMBAS (Devoluciones primero y el resto de Bodega PT). */
        private String source;
    }
}
