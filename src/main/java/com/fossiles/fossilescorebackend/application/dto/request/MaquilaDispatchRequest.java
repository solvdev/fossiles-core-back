package com.fossiles.fossilescorebackend.application.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * «Enviar a maquila» de Armar el día: lo elegido de cada OP va a la mesa de maquila ese día,
 * en una tarea por OP, sin tope de horas ni de productos.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaquilaDispatchRequest {

    /** Día de trabajo (lunes a viernes). */
    private LocalDate date;

    /** Mesa de maquila; si no viene, la única mesa especial activa. */
    private Integer desk;

    private String observations;

    /**
     * El cuero sale de nuestra bodega hacia el maquilador: se entrega (y descuenta del kardex por
     * receta) al enviar. {@code null} = sí. Con {@code false} las tareas siguen el camino normal y el
     * cuero se entrega después en «Por troquelar».
     */
    private Boolean deliverLeather;

    private List<Order> orders;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Order {
        private Long productionOrderId;
        private List<CreateManualTaskRequest.ManualTaskItemRequest> items;
    }
}
