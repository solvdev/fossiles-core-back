package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountingEntryResponse {
    private Long id;
    private String documentType;
    private Long documentId;
    private LocalDateTime entryDate;
    private BigDecimal debitAmount;
    private BigDecimal creditAmount;
    private String accountCode;
    private String accountName;
    private String description;
    private Long costCenterId;
    private String costCenterName;
    private String referenceNumber;
    private LocalDateTime createdAt;
    private Long createdBy;
}

