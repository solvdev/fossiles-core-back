package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaxInvoiceSummaryResponse {
    private long total;
    private long certified;
    private long unsigned;
    private long failed;
    private long draft;
    private long skipped;
    private long voided;
}
