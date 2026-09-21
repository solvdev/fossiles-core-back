package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskItemRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * Cuántas horas de mesa pesa una tarea, y cuántas le siguen pesando cuando ya está
 * empezada.
 *
 * <p>Estos casos no se ven en ninguna pantalla: la única señal de que la fórmula está
 * mal es que el reparto sale torcido dos días después. Por eso están aquí.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TaskDeskHoursServiceTest {

    /** Martes 07:00, dentro de la jornada. */
    private static final LocalDateTime MARTES_7 = LocalDateTime.of(2026, 9, 15, 7, 0);

    @Mock private TaskItemRepository taskItemRepository;
    @InjectMocks private TaskDeskHoursService service;

    private TaskEntity tarea(String estado, Double estimadas, LocalDateTime inicio, String codigoOp) {
        return TaskEntity.builder()
                .id(1L)
                .status(estado)
                .estimatedHours(estimadas)
                .startedAt(inicio)
                .productionOrderCode(codigoOp)
                .build();
    }

    // ==================== horas base ====================

    @Test
    @DisplayName("Una tarea de venta en línea no consume cupo de mesa")
    void ventaEnLineaPesaCero() {
        assertThat(service.baseHours(tarea("PENDING", 4.0, null, "OPL-900"), Map.of()))
                .isZero();
    }

    @Test
    @DisplayName("Las horas de venta del día se descuentan del cupo")
    void descuentaLasHorasDeVentaDelDia() {
        TaskEntity t = tarea("PENDING", 4.0, null, "OPK-16");
        assertThat(service.baseHours(t, Map.of(1L, 1.5))).isEqualTo(2.5);
    }

    @Test
    @DisplayName("El mapa en lote suma por tarea y descarta lo que no es venta del día")
    void mapaEnLoteAgrupaPorTarea() {
        when(taskItemRepository.findByTaskIdIn(anyList())).thenReturn(List.of(
                TaskItemEntity.builder().taskId(1L).daySaleExtra(true).estimatedHours(1.0).build(),
                TaskItemEntity.builder().taskId(1L).daySaleExtra(true).estimatedHours(0.5).build(),
                TaskItemEntity.builder().taskId(1L).daySaleExtra(false).estimatedHours(9.0).build(),
                TaskItemEntity.builder().taskId(2L).daySaleExtra(true).estimatedHours(2.0).build()));

        Map<Long, Double> mapa = service.daySaleExtraByTaskId(List.of(1L, 2L));

        assertThat(mapa).containsOnlyKeys(1L, 2L);
        assertThat(mapa.get(1L)).isEqualTo(1.5);
        assertThat(mapa.get(2L)).isEqualTo(2.0);
    }

    @Test
    @DisplayName("Sin tareas que consultar no se va a la base")
    void listaVaciaNoConsulta() {
        assertThat(service.daySaleExtraByTaskId(List.of())).isEmpty();
        assertThat(service.daySaleExtraByTaskId(null)).isEmpty();
    }

    // ==================== horas restantes ====================

    @Test
    @DisplayName("Una tarea pendiente pesa su estimado completo, no se le descuenta nada")
    void pendienteNoDescuenta() {
        TaskEntity t = tarea("PENDING", 4.0, null, "OPK-16");
        assertThat(service.remainingDeskHours(t, MARTES_7, Map.of())).isEqualTo(4.0);
    }

    @Test
    @DisplayName("Una tarea empezada hace un momento todavía pesa casi todo")
    void recienEmpezadaPesaCasiTodo() {
        TaskEntity t = tarea("IN_PROGRESS", 4.0, MARTES_7, "OPK-16");
        double restante = service.remainingDeskHours(t, MARTES_7.plusMinutes(30), Map.of());
        assertThat(restante).isEqualTo(3.5);
    }

    @Test
    @DisplayName("A media jornada le queda la mitad")
    void aMediaJornadaLeQuedaLaMitad() {
        TaskEntity t = tarea("IN_PROGRESS", 4.0, MARTES_7, "OPK-16");
        double restante = service.remainingDeskHours(t, MARTES_7.plusHours(2), Map.of());
        assertThat(restante).isEqualTo(2.0);
    }

    @Test
    @DisplayName("Una tarea abierta desde hace semanas se queda en el piso, nunca en cero")
    void tareaViejaSeQuedaEnElPiso() {
        // El caso real: las cinco tareas abiertas desde el 18/08. Si se restara el tiempo
        // transcurrido a secas darían negativo, y la mesa que siguen ocupando aparecería
        // libre para recibir cuatro horas nuevas encima.
        TaskEntity t = tarea("IN_PROGRESS", 3.75, LocalDateTime.of(2026, 8, 18, 8, 0), "OPK-16");
        double restante = service.remainingDeskHours(t, LocalDateTime.of(2026, 9, 15, 10, 0), Map.of());

        assertThat(restante).isEqualTo(TaskDeskHoursService.MIN_IN_PROGRESS_DESK_HOURS);
        assertThat(restante).isPositive();
    }

    @Test
    @DisplayName("El descuento nunca sube por encima del estimado")
    void nuncaSubePorEncimaDelEstimado() {
        // Fin anterior al inicio: workingMinutesBetween devuelve 0, así que no descuenta.
        TaskEntity t = tarea("IN_PROGRESS", 4.0, MARTES_7, "OPK-16");
        assertThat(service.remainingDeskHours(t, MARTES_7.minusDays(3), Map.of())).isEqualTo(4.0);
    }

    @Test
    @DisplayName("Una tarea de venta en línea empezada sigue pesando cero, sin piso")
    void ventaEnLineaEmpezadaNoRecibePiso() {
        // Si se le aplicara el piso pesaría MÁS estando a medias que recién creada, y
        // rompería el cupo de mesa, que cuenta con que las OPL no ocupan.
        TaskEntity t = tarea("IN_PROGRESS", 4.0, MARTES_7, "OPL-900");
        assertThat(service.remainingDeskHours(t, MARTES_7.plusHours(1), Map.of())).isZero();
    }

    @Test
    @DisplayName("Sin hora de inicio no hay nada que descontar")
    void sinHoraDeInicioNoDescuenta() {
        TaskEntity t = tarea("IN_PROGRESS", 4.0, null, "OPK-16");
        assertThat(service.remainingDeskHours(t, MARTES_7, Map.of())).isEqualTo(4.0);
    }

    @Test
    @DisplayName("La noche y el fin de semana no cuentan como trabajo")
    void laNocheYElFinDeSemanaNoCuentan() {
        // Viernes 16:00 a lunes 08:00: de reloj son más de 64 horas, pero de jornada es
        // una hora del viernes más una del lunes.
        TaskEntity t = tarea("IN_PROGRESS", 4.0, LocalDateTime.of(2026, 9, 11, 16, 0), "OPK-16");
        double restante = service.remainingDeskHours(t, LocalDateTime.of(2026, 9, 14, 8, 0), Map.of());
        assertThat(restante).isEqualTo(2.0);
    }
}
