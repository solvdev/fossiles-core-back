package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskExcelCommitResponse {

    private List<BatchResult> batches;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BatchResult {
        private Long batchId;
        private String fileName;
        private int year;
        private int month;
        private int salesRows;
        private int configRows;
        private int costRows;
        private int replacedRows;
        private List<String> warnings;
    }
}
