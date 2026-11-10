package com.fossiles.fossilescorebackend.application.dto;

import com.fossiles.fossilescorebackend.application.dto.request.ProductShipmentRequest;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/** Peticiones y respuestas de las cajas de armado de Bodega PT (OPV/OPC de Luis Felipe). */
public final class LfPackingDtos {

    private LfPackingDtos() {}

    /** Una fila de la lista «Pedidos LF». */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderSummary {
        private Long productionOrderId;
        private String code;
        /** OPV | OPC. */
        private String family;
        private String customerName;
        private String status;
        private LocalDate deliveryDate;
        private int orderedUnits;
        private int receivedUnits;
        private int missingReceiptUnits;
        /** Piezas en cajas abiertas o cerradas, sin despachar. */
        private int inBoxesUnits;
        /** Piezas ya liberadas o enviadas (por cajas o por el ERP). */
        private int dispatchedUnits;
        private int boxCount;
        private int openBoxes;
        private int closedBoxes;
        private int dispatchedBoxes;
        /**
         * Estado en bodega: NOT_RECEIVED | RECEIVING | RECEIVED | PACKING | READY | DISPATCHED_PARTIAL | DISPATCHED_ALL.
         */
        private String warehouseState;
        private List<String> shipmentNumbers;
    }

    /** Una línea de la orden (producto + color + talla) con sus cuentas. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LineRow {
        private Long productionOrderItemId;
        /** Vacío cuando la línea no tiene tallas. */
        private String sizeKey;
        private String productCode;
        private String productName;
        private String colorName;
        private int ordered;
        private int received;
        private int inBoxes;
        private int dispatched;
        /** Pedido − recibido. */
        private int missingReceipt;
        /** Lo que todavía se puede poner en cajas: pedido − liberado/enviado − cajas. */
        private int availableToPack;
        /** Piezas en cajas que aún no están recibidas en reserva (estorban al despachar). */
        private int boxesNotYetReceived;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BoxLine {
        private Long productionOrderItemId;
        private String sizeKey;
        private String productCode;
        private String productName;
        private String colorName;
        private int quantity;
        /** Cuántas de esas piezas faltan por recibir en reserva (solo mientras la caja no se despacha). */
        private int missingReceipt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Box {
        private Long id;
        private Long productionOrderId;
        private String orderCode;
        private int boxNo;
        /** Cajas totales de la orden (para «caja 2 de 4»). */
        private int totalBoxes;
        /** OPEN | CLOSED | DISPATCHED. */
        private String status;
        private Long partialReleaseId;
        private Long shipmentId;
        private String shipmentNumber;
        private String notes;
        private int totalPieces;
        /** true cuando todo lo de la caja ya está recibido en reserva. */
        private boolean allReceived;
        private List<BoxLine> lines;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ShipmentRef {
        private Long id;
        private String shipmentNumber;
        private String status;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderDetail {
        private OrderSummary order;
        private List<LineRow> lines;
        private List<Box> boxes;
        private List<ShipmentRef> shipments;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CreateBoxRequest {
        private String notes;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class UpdateBoxRequest {
        private String notes;
    }

    /**
     * Cambia las piezas de una línea/talla dentro de la caja: {@code quantity} fija la cantidad (0 quita la línea)
     * y {@code delta} suma o resta (escaneos desde varios teléfonos). Se usa uno de los dos.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class BoxLineRequest {
        private Long productionOrderItemId;
        private String sizeKey;
        private Integer quantity;
        private Integer delta;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DispatchRequest {
        private List<Long> boxIds;
        /** Material de empaque SUM- opcional de este envío. */
        private List<ProductShipmentRequest.PackingItemRequest> packingItems;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DispatchResult {
        private Long productionOrderId;
        private String orderCode;
        private Long shipmentId;
        private String shipmentNumber;
        private String shipmentStatus;
        private Long partialReleaseId;
        private int totalPieces;
        /** Las cajas que se despacharon ahora. */
        private List<Box> boxes;
        /** La orden ya actualizada (cuentas, cajas y envíos). */
        private OrderDetail detail;
    }
}
