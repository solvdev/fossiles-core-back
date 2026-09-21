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
public class KioskLedgerLabReclassifyResponse {
    private int updated;
    private int merged;
    private int skipped;
    @Builder.Default
    private List<String> conflicts = new ArrayList<>();
}
