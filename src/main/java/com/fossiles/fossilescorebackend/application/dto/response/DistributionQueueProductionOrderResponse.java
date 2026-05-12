package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DistributionQueueProductionOrderResponse {
    private Long id;
    private String code;
    private String orderType;
    /** OPV | OPK | OPI */
    private String family;
    private Integer schedulingPriority;
    private String customerName;
    /** true si existe al menos una tarea IN_PROGRESS de esta OP */
    private boolean productionStarted;

    /** Fecha de creación de la OP (orden FIFO: más antigua primero). */
    private LocalDateTime createdAt;
}
