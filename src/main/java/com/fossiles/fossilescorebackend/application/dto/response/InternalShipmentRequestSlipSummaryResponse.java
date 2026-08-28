package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InternalShipmentRequestSlipSummaryResponse {
    private String nextSlipNumber;
    private String lastPrintedSlipNumber;
    private long totalPrinted;
    private long totalAvailable;
    private long totalUsed;
}
