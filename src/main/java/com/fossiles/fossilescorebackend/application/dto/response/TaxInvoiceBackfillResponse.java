package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaxInvoiceBackfillResponse {
    private boolean dryRun;
    private int candidates;
    private int created;
    private int skipped;
    private int failed;

    @Builder.Default
    private List<String> errors = new ArrayList<>();

    @Builder.Default
    private List<Item> samples = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        private Long saleId;
        private String saleNumber;
        private Long invoiceId;
        private String internalNumber;
        private String status;
        private String message;
    }
}
