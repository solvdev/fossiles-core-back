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
public class KioskExcelBatchSummaryResponse {
    private Long id;
    private String fileName;
    private String sha256;
    private int year;
    private int month;
    /** APPLIED | REVERTED */
    private String status;
    private int salesRows;
    private int configRows;
    private int costRows;
    private List<String> warnings;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long revertedBy;
    private LocalDateTime revertedAt;
}
