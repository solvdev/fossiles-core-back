package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InternalShipmentRequestSlipPrintResponse {
    private List<String> slipNumbers;
    private Integer quantity;
    private String fromSlip;
    private String toSlip;
    private LocalDateTime printedAt;
}
