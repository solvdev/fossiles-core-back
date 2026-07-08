package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpvShipmentCatalogRowResponse {
    private Long productionOrderId;
    private String productionOrderCode;
    private String orderStatus;
    private Long customerId;
    private String customerName;
    private String customerLegacyCode;
    private String vendorShipmentNumber;
    private boolean vendorShipmentVoided;
    private LocalDate startDate;
    private LocalDate deliveryDate;
    private Long partialReleaseId;
    private String partialReleaseLabel;
    private Long productShipmentId;
    private String shipmentNumber;
    private String shipmentStatus;
    /** ORDER o SHIPMENT */
    private String documentLevel;
    private BigDecimal itemsSubtotal;
    private BigDecimal packingSubtotal;
    private BigDecimal shippingCost;
    private BigDecimal estimatedTotal;
    private String chargeStatus;
    private boolean hasCharge;
    private List<OpvShipmentCatalogLineResponse> lines;
}
