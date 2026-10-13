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
    }
}

