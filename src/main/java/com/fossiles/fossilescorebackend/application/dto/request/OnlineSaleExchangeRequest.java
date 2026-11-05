package com.fossiles.fossilescorebackend.application.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OnlineSaleExchangeRequest {
    /** Si viene, los items se agregan a este envío existente en vez de crear uno nuevo. */
    private Long targetOnlineSaleId;
    private List<ExchangeItemRequest> items;
    private String shippingCarrier;
    private String guideNumber;
    private String observations;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExchangeItemRequest {
        private Long productId;
        private Long colorId;
        private String size;
        private Integer quantity;
        /**
         * Opcional. Si viene, lo que se envía es OTRO artículo: productId/colorId/size son lo que el
         * cliente devuelve (regresa a Devoluciones) y estos tres campos son lo que sale en el envío Q0.
         * Sin replacementProductId se envía el mismo artículo.
         */
        private Long replacementProductId;
        private Long replacementColorId;
        private String replacementSize;
    }
}

