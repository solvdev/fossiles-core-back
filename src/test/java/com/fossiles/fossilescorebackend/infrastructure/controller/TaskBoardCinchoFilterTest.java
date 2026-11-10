package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.response.TaskResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** El tablero del servidor quita cinchos con la misma regla que el front (taskForTableCenterView). */
class TaskBoardCinchoFilterTest {

    private static TaskResponse.TaskItemDTO item(String code, String name, int qty, double hours) {
        return TaskResponse.TaskItemDTO.builder().productCode(code).productName(name).quantity(qty).estimatedHours(hours).build();
    }

    private static TaskResponse task(String orderCode, List<TaskResponse.TaskItemDTO> items) {
        return TaskResponse.builder().id(1L).productionOrderCode(orderCode).items(items).quantity(99).estimatedHours(99.0).build();
    }

    @Test
    void tareaDeOpcSeExcluye() {
        assertThat(TaskController.withoutCinchoLines(task("OPC-12", List.of(item("A", "Billetera", 1, 1))))).isNull();
        assertThat(TaskController.withoutCinchoLines(task("OPCF-3", List.of(item("A", "Billetera", 1, 1))))).isNull();
        assertThat(TaskController.withoutCinchoLines(task("OPCM-3", List.of(item("A", "Billetera", 1, 1))))).isNull();
    }

    @Test
    void tareaSoloDeCinchosOPulserasSeExcluye() {
        assertThat(TaskController.withoutCinchoLines(task("OPK-1", List.of(
                item("C1", "Cincho adulto", 2, 1), item("P1", "Pulsera cuero", 1, 0.5))))).isNull();
    }

    @Test
    void tareaMixtaPierdeLasLineasCinchoYRecalculaCantidadYHoras() {
        TaskResponse out = TaskController.withoutCinchoLines(task("OPK-1", List.of(
                item("B1", "Billetera", 3, 1.5), item("C1", "Cincho adulto", 2, 1), item("B2", "Cartera", 2, 0.75))));

        assertThat(out.getItems()).extracting(TaskResponse.TaskItemDTO::getProductCode).containsExactly("B1", "B2");
        assertThat(out.getQuantity()).isEqualTo(5);
        assertThat(out.getEstimatedHours()).isEqualTo(2.25);
    }

    @Test
    void tareaRegularNoCambia() {
        TaskResponse t = task("OPK-1", List.of(item("B1", "Billetera", 3, 1.5)));
        assertThat(TaskController.withoutCinchoLines(t)).isSameAs(t);
        assertThat(t.getQuantity()).isEqualTo(99); // ni cantidad ni horas se tocan
    }

    @Test
    void tareaSinItemsUsaSuPropioProducto() {
        TaskResponse cincho = TaskResponse.builder().productionOrderCode("OPK-1").productName("Cincho niño").build();
        TaskResponse normal = TaskResponse.builder().productionOrderCode("OPK-1").productName("Billetera").build();
        assertThat(TaskController.withoutCinchoLines(cincho)).isNull();
        assertThat(TaskController.withoutCinchoLines(normal)).isSameAs(normal);
    }
}
