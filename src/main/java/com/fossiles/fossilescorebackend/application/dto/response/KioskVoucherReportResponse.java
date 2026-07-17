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
public class KioskVoucherReportResponse {
    private String kioskName;
    private String kioskCode;
    @Builder.Default
    private List<KioskVoucherReportRowResponse> rows = new ArrayList<>();
}
