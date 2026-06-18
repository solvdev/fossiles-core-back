package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InternalShipmentRequestResponse {
    private Long id;
    private String status;
    private String requestType;
    private String recipientName;
    private String recipientPhone;
    private String recipientTaxId;
    private String notes;
    private String documentDate;
    private java.math.BigDecimal discountPercent;
    private java.math.BigDecimal discountAmount;
    private Long requestedBy;
    private LocalDateTime requestedAt;
    private Long reviewedBy;
    private LocalDateTime reviewedAt;
    private String rejectionReason;
    private Long productShipmentId;
    private String shipmentNumber;
    private List<LineResponse> lines;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LineResponse {
        private Long id;
        private Integer lineOrder;
        private Long productId;
        private String productCode;
        private String productName;
        private Long colorId;
        private String colorName;
        private String size;
        private BigDecimal quantity;
    }
}
