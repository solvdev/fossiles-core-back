package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Resumen de un traslado ya registrado (para anexar productos a la misma boleta). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioscoTrasladoBoletaResponse {
    private boolean exists;
    private String physicalSlipNumber;
    private Long referenceId;
    private Long locationOriginId;
    private Long locationDestinationId;
    private String locationOriginName;
    private String locationDestinationName;
    private List<Line> lines;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Line {
        private Long productId;
        private String productCode;
        private String productName;
        private Long colorId;
        private String colorName;
        private String sizeKey;
        private Integer quantity;
        private String movementType;
    }
}
