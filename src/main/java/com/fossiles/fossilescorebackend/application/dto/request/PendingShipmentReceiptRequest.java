package com.fossiles.fossilescorebackend.application.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Resolución de unidades que quedaron en tránsito tras la recepción de un envío:
 * se reciben en el kiosko o se regresan a Bodega PT.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendingShipmentReceiptRequest {

    public static final String ACTION_RECEIVE = "RECEIVE";
    public static final String ACTION_RETURN_TO_WAREHOUSE = "RETURN_TO_WAREHOUSE";

    private List<Item> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        private Long detailId;
        private BigDecimal quantity;
        /** RECEIVE (default) o RETURN_TO_WAREHOUSE. */
        private String action;
        private String lineNotes;
    }
}
