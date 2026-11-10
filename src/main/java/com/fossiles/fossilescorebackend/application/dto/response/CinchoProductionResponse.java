package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Lo que necesitan las pantallas de cinchos, ya armado y sin el resto de las órdenes.
 *
 * <p>Antes las dos pantallas bajaban {@code GET /api/production-orders} entero (todas las
 * órdenes, con sus productos uno por uno: 6 MB y de 12 a 34 s) para quedarse con unas pocas.
 */
public final class CinchoProductionResponse {

    private CinchoProductionResponse() {}

    /** Una línea de cincho o pulsera de una orden. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Line {
        private Long productionOrderItemId;
        private Long productId;
        private String productCode;
        private String productName;
        private String colorName;
        /** Tallas con unidades, en orden de talla. Vacío si la línea no lleva tallas. */
        private Map<String, Integer> sizes;
        private int totalQty;
        private String observations;
        /** PENDING | IN_PROGRESS | COMPLETED: en el día (cuadro) o lo último marcado (listado). */
        private String workStatus;
        private boolean delivered;
        /** Último día en que se marcó algo en la línea (solo listado). */
        private LocalDate lastWorkDate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Order {
        private Long id;
        private String code;
        /** OPL | OPCK | OPC | OPCF | OPCM */
        private String family;
        private String orderType;
        private String status;
        private String customerName;
        private String sellerName;
        private LocalDate startDate;
        private LocalDate deliveryDate;
        private String observations;
        /** La OP de venta también lleva productos que no son cincho (van a las mesas). */
        private boolean mixedOrder;
        private Long groupId;
        private String groupCode;
        /**
         * Solo en el cuadro del día: la orden es de un día anterior (esta es su fecha) y se
         * arrastra porque tiene líneas sin terminar. Solo trae esas líneas.
         */
        private LocalDate carriedFrom;
        private int totalQty;
        private int lineCount;
        private int linesDone;
        private int linesInProgress;
        private List<Line> lines;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Summary {
        private int orders;
        private int pending;
        private int inProgress;
        /** Activas con entrega anterior a hoy. */
        private int late;
        /** Activas que se entregan de hoy a 7 días. */
        private int dueSoon;
        private int units;
        private int unitsDone;
    }

    /** Cuadro de cinchos de un día del Tablero. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Day {
        private LocalDate date;
        private List<Order> orders;
    }

    /** Listado de «Cinchos en producción». */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Listing {
        private String scope;
        private String source;
        /** Para cerradas se devuelven las más recientes, con tope: true si había más. */
        private boolean truncated;
        private Summary summary;
        private List<Order> orders;
    }
}
