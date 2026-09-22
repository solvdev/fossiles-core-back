package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/** Orden cincho con materiales pendientes. Sin líneas ni receta: solo la tarjeta del listado. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaterialsCinchoOrderResponse {
    private Long id;
    private String code;
    private String orderType;
    private String status;
    private String customerName;
    private LocalDate startDate;
    private LocalDate deliveryDate;
}
