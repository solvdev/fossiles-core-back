package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Una tarea empezada un día anterior y aún en proceso se termina en la misma mesa: la boleta
 * impresa lleva esa mesa y el encargado de esa mesa en esa fecha.
 *
 * <p>Mismo arreglo que {@link TaskDieCutTest}: rollback de cada prueba.
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional
class TaskCarryOverScheduleTest {

    @Autowired
    private TaskController controller;

    @Autowired
    private TaskRepository taskRepository;

    /** Una tarea «empezada ayer en la mesa 3» que sigue en proceso. */
    private TaskEntity empezadaAyer() {
        return taskRepository.save(TaskEntity.builder()
                .code("TK-ARRASTRE").status("IN_PROGRESS").desk(3).dieCutReady(true)
                .quantity(1).estimatedHours(1.0)
                .scheduledDate(GuatemalaDateTime.today().minusDays(1))
                .build());
    }

    private static Map<String, Object> cuerpo(String clave, Object valor) {
        Map<String, Object> m = new HashMap<>();
        m.put(clave, valor);
        return m;
    }

    @Test
    @DisplayName("no se le cambia la mesa a una tarea empezada ayer")
    void noSeMueveDeMesa() {
        TaskEntity tarea = empezadaAyer();

        BusinessException e = assertThrows(BusinessException.class,
                () -> controller.scheduleTask(tarea.getId(), cuerpo("desk", 4)));
        assertTrue(e.getMessage().contains("misma mesa"), e.getMessage());
        assertEquals(3, taskRepository.findById(tarea.getId()).orElseThrow().getDesk());
    }

    @Test
    @DisplayName("ni se le cambia la fecha: el encargado de la boleta sale de mesa + fecha")
    void noSeMueveDeFecha() {
        TaskEntity tarea = empezadaAyer();

        assertThrows(BusinessException.class, () -> controller.scheduleTask(tarea.getId(),
                cuerpo("scheduledDate", GuatemalaDateTime.today().toString())));
    }

    @Test
    @DisplayName("mandar la misma mesa no es mover y se deja pasar")
    void laMismaMesaPasa() throws Exception {
        TaskEntity tarea = empezadaAyer();

        controller.scheduleTask(tarea.getId(), cuerpo("desk", 3));

        assertEquals(3, taskRepository.findById(tarea.getId()).orElseThrow().getDesk());
    }
}
