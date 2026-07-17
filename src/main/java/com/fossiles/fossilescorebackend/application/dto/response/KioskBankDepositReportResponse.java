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
public class KioskBankDepositReportResponse {
    private String accountNumber;
    private String accountName;
    private String bankName;
    @Builder.Default
    private List<KioskBankDepositReportRowResponse> rows = new ArrayList<>();
}
