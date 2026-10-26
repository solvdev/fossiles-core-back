package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.service.TaskDeskAssignerService;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * «Cortar todo»: el corte de toda la tarea de una vez, con o sin entregar el cuero que falte.
 * El asignador de mesa va simulado: usa {@code pg_advisory_xact_lock}, que H2 no tiene.
 */
@SpringBootTest
@Transactional
class TaskDieCutAllTest {

    @Autowired private TaskController controller;
    @Autowired private TaskRepository taskRepository;
    @Autowired private TaskItemRepository taskItemRepository;
    @MockBean private TaskDeskAssignerService taskDeskAssignerService;

    /** Pendiente, con un producto con cuero y otro sin cuero, ninguno cortado. */
    private TaskEntity tarea() {
        TaskEntity t = taskRepository.save(TaskEntity.builder().code("TK-CORTE").status("PENDING")
                .quantity(2).estimatedHours(1.0).scheduledDate(GuatemalaDateTime.today())
                .leatherDelivered(false).dieCutReady(false).build());
        taskItemRepository.save(TaskItemEntity.builder().taskId(t.getId()).quantity(1)
                .leatherDelivered(true).dieCutReady(false).build());
        taskItemRepository.save(TaskItemEntity.builder().taskId(t.getId()).quantity(1)
                .leatherDelivered(false).dieCutReady(false).build());
        return t;
    }

    @Test
    @DisplayName("sin entregar cuero corta lo que puede y dice cuántos se saltó")
    void sinCueroSeSalta() throws Exception {
        TaskEntity t = tarea();

        Map<String, Object> r = controller.setTaskDieCutAll(t.getId(), Map.of()).getBody();

        assertEquals(1, r.get("cutItems"));
        assertEquals(1, r.get("skippedWithoutLeather"));
        assertFalse(Boolean.TRUE.equals(taskRepository.findById(t.getId()).orElseThrow().getDieCutReady()));
    }

    @Test
    @DisplayName("entregando el cuero corta todo y la tarea queda troquelada")
    void conCueroCortaTodo() throws Exception {
        TaskEntity t = tarea();

        Map<String, Object> r = controller.setTaskDieCutAll(t.getId(), Map.of("deliverLeather", true)).getBody();

        assertEquals(2, r.get("cutItems"));
        assertEquals(1, r.get("leatherDeliveredItems"));
        TaskEntity after = taskRepository.findById(t.getId()).orElseThrow();
        assertTrue(Boolean.TRUE.equals(after.getDieCutReady()));
        assertTrue(Boolean.TRUE.equals(after.getLeatherDelivered()));
        assertTrue(taskItemRepository.findByTaskId(t.getId()).stream().allMatch(i -> Boolean.TRUE.equals(i.getDieCutReady())));
    }

    @Test
    @DisplayName("una tarea ya empezada no se toca")
    void empezadaNo() {
        TaskEntity t = tarea();
        t.setStatus("IN_PROGRESS");
        taskRepository.save(t);

        assertThrows(BusinessException.class, () -> controller.setTaskDieCutAll(t.getId(), Map.of()));
    }
}
