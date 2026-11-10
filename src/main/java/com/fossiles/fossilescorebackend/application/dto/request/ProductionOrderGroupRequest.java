package com.fossiles.fossilescorebackend.application.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Crear una OP consolidada: las OPV (o las OPC) de Luis Felipe que se producen juntas. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductionOrderGroupRequest {
    private List<Long> productionOrderIds;
    private String observations;
}
