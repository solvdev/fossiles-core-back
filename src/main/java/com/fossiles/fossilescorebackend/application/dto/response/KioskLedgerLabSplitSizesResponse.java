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
public class KioskLedgerLabSplitSizesResponse {
    private Long stockId;
    private int deletedAggregated;
    private int createdEntradas;
    private List<String> sizeKeysCreated;
    private KioskLedgerLabStockResponse stock;
}
