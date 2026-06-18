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
public class ProductInventoryOutflowReportResponse {
    private List<ProductInventoryOutflowReportRowResponse> rows;
    private int totalCount;
    private boolean truncated;
    private String message;
}
