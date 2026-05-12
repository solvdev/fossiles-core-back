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
public class KioskKardexBackfillResponse {
    private int salesScanned;
    private int itemsScanned;
    private int kardexInserted;
    private int kardexSkipped;
    private List<String> warnings;
}

