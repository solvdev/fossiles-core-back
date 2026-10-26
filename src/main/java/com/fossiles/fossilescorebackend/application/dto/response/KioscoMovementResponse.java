package com.fossiles.fossilescorebackend.application.dto.response;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoMovementType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioscoMovementResponse {
    private Long id;
    private Long kioscoStockId;
    private Long locationId;
    private String locationName;
    private Long productId;
    private String productCode;
    private String productName;
    private Long colorId;
    private String colorName;
    /** Dimensión de la fila de stock afectada (marca, SINTETICO:MARCA, NINO/DAMA, NUEVO/VIEJO). */
    private String hardwareCondition;
    /** Etiqueta legible de la dimensión (ej. "LEVIS", "Sintética · LEVIS"). */
    private String hardwareLabel;
    private KioscoMovementType movementType;
    private Integer quantity;
    /** Talla del movimiento si aplica (p. ej. recepción de envío FOSS). */
    private String sizeKey;
    private Integer stockBefore;
    private Integer stockAfter;
    private Long referenceId;
    /** Tipo de documento asociado: SHIPMENT, TRANSFER, INVOICE, TRASLADO, POS_SALE (venta sin factura). */
    private String referenceType;
    /** Número legible del documento (ej. ENVI-00123, TRF-45). */
    private String referenceNumber;
    private String physicalSlipNumber;
    private String reason;
    private Boolean affectsStock;
    private Long userId;
    private String username;
    private Long originLocationId;
    private String originLocationName;
    private String originLocationCode;
    private Long destinationLocationId;
    private String destinationLocationName;
    private String destinationLocationCode;
    private LocalDateTime createdAt;
}
