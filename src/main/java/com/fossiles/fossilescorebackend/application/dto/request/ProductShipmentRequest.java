package com.fossiles.fossilescorebackend.application.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductShipmentRequest {
    private Long shipmentId;

    /** Obligatorio para CLIENTE_KIOSKO (OPCK). Opcional para INTERNA (OPI) y OPC (cinchos). */
    private Long locationId;

    /** Destino libre para envíos OPC sin kiosko (también se persiste en notes como DESTINO: …). */
    private String destinationAddress;

    private String notes;

    /** Fecha impresa en documento (YYYY-MM-DD); se persiste en notes como DOCUMENT_DATE:… */
    private String documentDate;

    /** Liberación parcial LF que origina este envío. */
    private Long partialReleaseId;
    
    private List<ProductShipmentDetailRequest> products;
    private List<PackingItemRequest> packingItems;
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProductShipmentDetailRequest {
        @NotNull(message = "Product ID is required")
        private Long productId;

        private Long colorId;
        private String size;
        /** NUEVO | VIEJO */
        private String hardwareCondition;
        
        @NotNull(message = "Quantity is required")
        private java.math.BigDecimal quantity;

        /** Precio unitario de la línea (opcional; cinchos con precio por talla). */
        private java.math.BigDecimal unitPrice;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PackingItemRequest {
        @NotNull(message = "Material ID is required")
        private Long materialId;

        @NotNull(message = "Quantity is required")
        private java.math.BigDecimal quantity;

        private java.math.BigDecimal unitPrice;
    }
}

