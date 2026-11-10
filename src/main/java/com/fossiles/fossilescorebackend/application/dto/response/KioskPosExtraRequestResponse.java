package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskPosExtraRequestResponse {
    private Long id;
    /** Número visible, p. ej. CEX-00012. */
    private String requestNumber;
    private Long kioskLocationId;
    private String kioskName;
    private String kioskCode;
    private Long productId;
    private String productCode;
    private String productName;
    private Long colorId;
    private String colorName;
    private String size;
    private String hardwareCondition;
    private BigDecimal quantity;
    /** Extra por pieza. */
    private BigDecimal extraAmount;
    /** Extra × piezas. */
    private BigDecimal extraTotal;
    private BigDecimal catalogUnitPrice;
    /** Precio con extra por pieza (catálogo + extra), si se conoce el catálogo. */
    private BigDecimal resultingUnitPrice;
    private String description;
    /** PENDING, APPROVED, REJECTED, USED o CANCELLED. */
    private String status;
    private LocalDate requestDate;
    private Long requestedByUserId;
    private String requestedByName;
    private LocalDateTime requestedAt;
    private Long authorizedByUserId;
    private String authorizedByName;
    private LocalDateTime authorizedAt;
    private String rejectionReason;
    private Long saleId;
}
