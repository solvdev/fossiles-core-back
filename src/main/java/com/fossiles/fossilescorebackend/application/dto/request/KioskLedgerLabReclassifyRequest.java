package com.fossiles.fossilescorebackend.application.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioskLedgerLabReclassifyRequest {
    private List<Long> stockIds;
    private String hardwareCondition;
    /** Si ya existe la dimensión en el mismo color, suma cantidades y mueve movimientos. */
    private Boolean mergeIfExists;
}
