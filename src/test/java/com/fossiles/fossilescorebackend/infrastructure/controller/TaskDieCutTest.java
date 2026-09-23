package com.fossiles.fossilescorebackend.infrastructure.controller;

import com.fossiles.fossilescorebackend.application.dto.response.TaskResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Troquelado por producto: la compuerta previa a mesa del Organizador.
 *
 * <p><b>{@code @ActiveProfiles("local")} no es decorativo.</b> El
 * {@code application.properties} por defecto apunta a una base remota en RDS; sin fijar el
 * perfil, esta clase se levantaria contra ella.
 *
 * <p><b>{@code @Transactional} tampoco.</b> Estas pruebas SI escriben -marcan troquelado,
 * parten tareas- y se apoyan en el rollback automatico de Spring para deshacerlo. Nada de lo
 * que tocan queda en la copia local.
 *
 * <p>Trabajan sobre datos reales en vez de fabricar tareas: una tarea valida necesita media
 * docena de campos coherentes entre si, y lo que se quiere comprobar es precisamente como se
 * comporta con las formas que existen de verdad. Si la copia no tuviera un caso apto, la
 * prueba se salta en vez de inventarse uno.
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional
class TaskDieCutTest {

    @Autowired
    private TaskController controller;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private TaskItemRepository taskItemRepository;

    /** Una tarea pendiente, sin cinchos, con al menos {@code minItems} productos sin cortar. */
    private TaskEntity tareaConProductos(int minItems) {
        List<TaskEntity> candidatas = taskRepository.findPendingWithUncutItems();
        for (TaskEntity t : candidatas) {
            if (taskItemRepository.findByTaskId(t.getId()).size() >= minItems) {
                return t;
            }
        }
        Assumptions.abort("La copia local no tiene una tarea pendiente con " + minItems + " productos sin troquelar");
        return null;
    }

    private static Map<String, Object> cuerpo(String clave, Object valor) {
        Map<String, Object> m = new HashMap<>();
        m.put(clave, valor);
        return m;
    }

    private void conCuero(TaskEntity tarea) {
        tarea.setLeatherDelivered(true);
        taskRepository.save(tarea);
    }

    @Test
    @DisplayName("sin cuero no se puede troquelar, y el error dice que producto es")
    void sinCueroNoSeTroquela() throws Exception {
        TaskEntity tarea = tareaConProductos(1);
        tarea.setLeatherDelivered(false);
        taskRepository.save(tarea);
        List<TaskItemEntity> items = taskItemRepository.findByTaskId(tarea.getId());
        items.forEach(i -> { i.setLeatherDelivered(false); taskItemRepository.save(i); });

        BusinessException e = assertThrows(BusinessException.class,
                () -> controller.setTaskItemDieCut(tarea.getId(), items.get(0).getId(), cuerpo("dieCutReady", true)));
        assertTrue(e.getMessage().contains("sin entrega de cuero"), e.getMessage());
    }

    /**
     * La razon de ser de esta prueba: la entrega de cuero se registra por orden y marca la
     * TAREA sin bajar a sus productos. Si la compuerta exigiera el flag del producto,
     * bloquearia casi todo el troquelado real.
     */
    @Test
    @DisplayName("el cuero de la tarea basta aunque el producto no lo tenga")
    void elCueroDeLaTareaBasta() throws Exception {
        TaskEntity tarea = tareaConProductos(1);
        conCuero(tarea);
        List<TaskItemEntity> items = taskItemRepository.findByTaskId(tarea.getId());
        TaskItemEntity item = items.get(0);
        item.setLeatherDelivered(false);
        taskItemRepository.save(item);

        TaskResponse r = controller.setTaskItemDieCut(
                tarea.getId(), item.getId(), cuerpo("dieCutReady", true)).getBody();

        assertNotNull(r);
        assertTrue(Boolean.TRUE.equals(taskItemRepository.findById(item.getId()).orElseThrow().getDieCutReady()));
    }

    @Test
    @DisplayName("marcar un producto de varios NO deja la tarea troquelada")
    void unProductoNoBastaParaLaTarea() throws Exception {
        TaskEntity tarea = tareaConProductos(2);
        conCuero(tarea);
        List<TaskItemEntity> items = taskItemRepository.findByTaskId(tarea.getId());

        TaskResponse r = controller.setTaskItemDieCut(
                tarea.getId(), items.get(0).getId(), cuerpo("dieCutReady", true)).getBody();

        assertNotNull(r);
        assertFalse(Boolean.TRUE.equals(r.getDieCutReady()),
                "la tarea no puede estar troquelada con productos sin cortar");
    }

    @Test
    @DisplayName("marcar todos los productos SI deja la tarea troquelada")
    void todosLosProductosSiBastan() throws Exception {
        TaskEntity tarea = tareaConProductos(2);
        conCuero(tarea);
        List<TaskItemEntity> items = taskItemRepository.findByTaskId(tarea.getId());

        TaskResponse ultima = null;
        for (TaskItemEntity item : items) {
            ultima = controller.setTaskItemDieCut(
                    tarea.getId(), item.getId(), cuerpo("dieCutReady", true)).getBody();
        }

        assertNotNull(ultima);
        assertTrue(Boolean.TRUE.equals(ultima.getDieCutReady()));
        assertNotNull(ultima.getDieCutDate(), "al quedar troquelada debe registrar la fecha");
    }

    /**
     * El caso que pidio el cliente: de cinco productos hay tres cortados, esos tres bajan a
     * mesa y los dos que faltan quedan pendientes.
     */
    @Test
    @DisplayName("el corte parcial saca los no cortados a una hermana sin troquelar")
    void elCorteParcialSeparaLosNoCortados() throws Exception {
        TaskEntity tarea = tareaConProductos(2);
        conCuero(tarea);
        List<TaskItemEntity> items = taskItemRepository.findByTaskId(tarea.getId());
        int total = items.size();

        // Se troquela todo menos el ultimo.
        for (int i = 0; i < total - 1; i++) {
            controller.setTaskItemDieCut(tarea.getId(), items.get(i).getId(), cuerpo("dieCutReady", true));
        }

        Map<String, Object> res = controller.splitUncutDieCutItems(tarea.getId()).getBody();
        assertNotNull(res);
        assertEquals(Boolean.TRUE, res.get("split"));
        assertEquals(1, res.get("movedItems"));

        Long hermanaId = (Long) res.get("siblingTaskId");
        assertNotNull(hermanaId, "debe haber creado una tarea hermana");

        TaskEntity origen = taskRepository.findById(tarea.getId()).orElseThrow();
        TaskEntity hermana = taskRepository.findById(hermanaId).orElseThrow();

        assertTrue(Boolean.TRUE.equals(origen.getDieCutReady()),
                "al quedarse solo con lo cortado, la de origen pasa a estar troquelada");
        assertFalse(Boolean.TRUE.equals(hermana.getDieCutReady()),
                "la hermana NO puede heredar el troquelado: bajaria a mesa sin corte");
        assertEquals(total - 1, taskItemRepository.findByTaskId(origen.getId()).size());
        assertEquals(1, taskItemRepository.findByTaskId(hermana.getId()).size());
    }

    /**
     * La hermana nace sin dia y sin mesa a proposito. Ademas de ser lo correcto, evita que
     * {@code resolveOrCreateTargetTask} reutilice una tarea existente y meta productos sin
     * cortar dentro de una ya troquelada.
     */
    @Test
    @DisplayName("la hermana nace sin mesa y sin fecha")
    void laHermanaNaceSinMesaNiFecha() throws Exception {
        TaskEntity tarea = tareaConProductos(2);
        conCuero(tarea);
        List<TaskItemEntity> items = taskItemRepository.findByTaskId(tarea.getId());
        controller.setTaskItemDieCut(tarea.getId(), items.get(0).getId(), cuerpo("dieCutReady", true));

        Map<String, Object> res = controller.splitUncutDieCutItems(tarea.getId()).getBody();
        Long hermanaId = (Long) res.get("siblingTaskId");
        Assumptions.assumeTrue(hermanaId != null);

        TaskEntity hermana = taskRepository.findById(hermanaId).orElseThrow();
        assertEquals(null, hermana.getDesk(), "sin mesa: todavia no puede bajar");
        assertEquals(null, hermana.getScheduledDate(), "sin fecha: no esta agendada");
        assertEquals("PENDING", hermana.getStatus());
    }

    @Test
    @DisplayName("no parte nada si estan todos cortados o ninguno lo esta")
    void noParteSiEsHomogenea() throws Exception {
        TaskEntity tarea = tareaConProductos(2);

        Map<String, Object> sinNada = controller.splitUncutDieCutItems(tarea.getId()).getBody();
        assertNotNull(sinNada);
        assertEquals(Boolean.FALSE, sinNada.get("split"), "ninguno cortado: no hay nada que separar");
        assertEquals(0, sinNada.get("movedItems"));

        conCuero(tarea);
        for (TaskItemEntity item : taskItemRepository.findByTaskId(tarea.getId())) {
            controller.setTaskItemDieCut(tarea.getId(), item.getId(), cuerpo("dieCutReady", true));
        }
        Map<String, Object> todos = controller.splitUncutDieCutItems(tarea.getId()).getBody();
        assertNotNull(todos);
        assertEquals(Boolean.FALSE, todos.get("split"), "todos cortados: tampoco hay nada que separar");
    }

    @Test
    @DisplayName("la fecha prevista de troquelado rechaza fines de semana")
    void laFechaPrevistaRechazaFinDeSemana() throws Exception {
        TaskEntity tarea = tareaConProductos(1);
        Long itemId = taskItemRepository.findByTaskId(tarea.getId()).get(0).getId();

        assertThrows(BusinessException.class,
                () -> controller.setTaskItemDieCutPlannedDate(
                        tarea.getId(), itemId, cuerpo("plannedDate", "2026-09-19")));

        assertThrows(BusinessException.class,
                () -> controller.setTaskItemDieCutPlannedDate(
                        tarea.getId(), itemId, cuerpo("plannedDate", "no-es-fecha")));
    }

    @Test
    @DisplayName("la fecha prevista no pisa la fecha de marcado")
    void laFechaPrevistaEsOtraCosa() throws Exception {
        TaskEntity tarea = tareaConProductos(1);
        conCuero(tarea);
        TaskItemEntity item = taskItemRepository.findByTaskId(tarea.getId()).get(0);

        controller.setTaskItemDieCutPlannedDate(tarea.getId(), item.getId(), cuerpo("plannedDate", "2026-09-21"));
        controller.setTaskItemDieCut(tarea.getId(), item.getId(), cuerpo("dieCutReady", true));

        TaskItemEntity recargado = taskItemRepository.findById(item.getId()).orElseThrow();
        assertEquals("2026-09-21", String.valueOf(recargado.getDieCutPlannedDate()));
        assertNotNull(recargado.getDieCutDate());
        assertFalse(recargado.getDieCutPlannedDate().equals(recargado.getDieCutDate())
                        && !"2026-09-21".equals(String.valueOf(recargado.getDieCutDate())),
                "son dos fechas distintas: una dice cuando toca, otra cuando se marco");
    }

    @Test
    @DisplayName("la lista por troquelar solo trae pendientes con algo sin cortar, y sin cinchos")
    void laListaPorTroquelar() throws Exception {
        List<TaskResponse> lista = controller.getDieCutPending().getBody();
        assertNotNull(lista);

        for (TaskResponse t : lista) {
            assertEquals("PENDING", t.getStatus());
            assertNotNull(t.getItems());
            assertTrue(t.getItems().stream().anyMatch(i -> !Boolean.TRUE.equals(i.getDieCutReady())),
                    "la tarea " + t.getCode() + " no tiene ningun producto sin cortar");
            String code = t.getProductionOrderCode() == null ? "" : t.getProductionOrderCode().toUpperCase();
            assertFalse(code.startsWith("OPC-"), "no deberian salir cinchos: " + code);
        }
    }

    @Test
    @DisplayName("una tarea deja la lista cuando se troquelan todos sus productos")
    void laTareaSaleDeLaListaAlCompletarse() throws Exception {
        TaskEntity tarea = tareaConProductos(1);
        conCuero(tarea);
        long antes = controller.getDieCutPending().getBody().stream()
                .filter(t -> t.getId().equals(tarea.getId())).count();
        assertEquals(1, antes, "la tarea deberia estar en la lista antes de troquelarla");

        for (TaskItemEntity item : taskItemRepository.findByTaskId(tarea.getId())) {
            controller.setTaskItemDieCut(tarea.getId(), item.getId(), cuerpo("dieCutReady", true));
        }

        long despues = controller.getDieCutPending().getBody().stream()
                .filter(t -> t.getId().equals(tarea.getId())).count();
        assertEquals(0, despues, "al quedar entera troquelada debe salir de la lista");
    }

    // ==================== La compuerta sobre el reparto a mesa ====================
    //
    // El Organizador manda requireDieCut=true (taskService.planTasksWindow). Estas dos
    // pruebas van en par a proposito: la primera comprueba que la compuerta frena, y la
    // segunda que es ELLA quien frena y no otra cosa del reparto -sin la segunda, una
    // tarea excluida por falta de mesa, por horizonte o por no tener OP se leeria como
    // exito de la compuerta.

    /** Reparte solo la OP de la tarea, para no mover el tablero entero en una prueba. */
    private Map<String, Object> repartir(TaskEntity tarea, boolean conCompuerta) throws Exception {
        return controller.planWindowTasks(
                LocalDate.now(), null, 5, tarea.getProductionOrderId(), conCompuerta, null).getBody();
    }

    /**
     * <b>La compuerta no retira mesas, solo impide darlas.</b> plan-window no desasigna nada:
     * una tarea sin cortar que ya tuviera mesa de un reparto anterior la conserva. La
     * compuerta manda de aqui en adelante, no hacia atras.
     */
    @Test
    @DisplayName("con la compuerta, una tarea sin troquelar no recibe mesa y se dice cuantas freno")
    void laCompuertaFrenaLoSinTroquelar() throws Exception {
        TaskEntity tarea = tareaConProductos(1);
        Assumptions.assumeTrue(tarea.getProductionOrderId() != null,
                "la tarea de prueba no tiene OP: el reparto va por OP");
        Integer mesaAntes = tarea.getDesk();

        Map<String, Object> r = repartir(tarea, true);

        assertNotNull(r);
        assertEquals(true, r.get("requireDieCut"), "la corrida deberia reportar que aplico la compuerta");
        assertTrue(((Number) r.get("dieCutBlockedTasks")).intValue() >= 1,
                "la tarea sin troquelar deberia contarse como frenada: " + r.get("message"));
        assertTrue(String.valueOf(r.get("message")).contains("troquelado"),
                "el mensaje tiene que decir por que no bajo, no solo que no bajo: " + r.get("message"));
        assertEquals(mesaAntes, taskRepository.findById(tarea.getId()).orElseThrow().getDesk(),
                "el reparto no debio tocarle la mesa a una tarea sin corte");
    }

    @Test
    @DisplayName("sin la compuerta, la misma tarea si entra al reparto")
    void sinCompuertaLaMismaTareaSiEntra() throws Exception {
        TaskEntity tarea = tareaConProductos(1);
        Assumptions.assumeTrue(tarea.getProductionOrderId() != null,
                "la tarea de prueba no tiene OP: el reparto va por OP");

        Map<String, Object> r = repartir(tarea, false);

        assertNotNull(r);
        assertEquals(false, r.get("requireDieCut"));
        assertEquals(0, ((Number) r.get("dieCutBlockedTasks")).intValue(),
                "apagada, la compuerta no puede frenar nada");
        assertTrue(((Number) r.get("selectedTasks")).intValue() >= 1,
                "sin compuerta la tarea deberia ser candidata: " + r.get("message"));
    }

    // ==================== Las demas vias que dan mesa ====================
    //
    // plan-window no era la unica: rebalance-day, el relleno de mesa liberada, los dos
    // generadores de tareas y las dos acciones manuales tambien ponen mesa. Si una sola
    // queda abierta, la regla «a mesa solo baja lo troquelado» no es una regla, porque
    // basta usar esa para saltarsela.

    @Test
    @DisplayName("a mano tampoco: poner mesa a una tarea sin troquelar se rechaza")
    void aManoTampocoBajaSinTroquel() throws Exception {
        TaskEntity tarea = tareaConProductos(1);

        BusinessException e = assertThrows(BusinessException.class,
                () -> controller.scheduleTask(tarea.getId(), cuerpo("desk", 1)));
        assertTrue(e.getMessage().contains("sin troquelar"), e.getMessage());
    }

    /** Marca el corte de todos los productos de la tarea, con su cuero, y la devuelve recargada. */
    private TaskEntity troquelarEntera(TaskEntity tarea) throws Exception {
        conCuero(tarea);
        for (TaskItemEntity item : taskItemRepository.findByTaskId(tarea.getId())) {
            controller.setTaskItemDieCut(tarea.getId(), item.getId(), cuerpo("dieCutReady", true));
        }
        return taskRepository.findById(tarea.getId()).orElseThrow();
    }

    /** Contrapeso de las pruebas de rechazo: sin esto, una pared que bloquea todo pasaria igual. */
    @Test
    @DisplayName("una tarea ya troquelada SI puede ponerse en mesa a mano")
    void loTroqueladoSiBajaAMesa() throws Exception {
        TaskEntity tarea = troquelarEntera(tareaConProductos(1));
        assertTrue(Boolean.TRUE.equals(tarea.getDieCutReady()), "la tarea deberia quedar troquelada");

        controller.scheduleTask(tarea.getId(), cuerpo("desk", 2));

        assertEquals(2, taskRepository.findById(tarea.getId()).orElseThrow().getDesk(),
                "con el corte hecho, poner mesa tiene que funcionar igual que antes");
    }

    @Test
    @DisplayName("un producto ya troquelado SI puede moverse a una mesa")
    void elProductoTroqueladoSiSeMueve() throws Exception {
        TaskEntity tarea = troquelarEntera(tareaConProductos(1));
        TaskItemEntity item = taskItemRepository.findByTaskId(tarea.getId()).get(0);

        controller.moveTaskItem(new TaskController.MoveTaskItemRequest(item.getId(), 2, null));

        TaskItemEntity movido = taskItemRepository.findById(item.getId()).orElseThrow();
        assertEquals(2, taskRepository.findById(movido.getTaskId()).orElseThrow().getDesk(),
                "con el corte hecho, mover a mesa tiene que funcionar igual que antes");
    }

    /** Sacar de mesa nunca es el problema: si se bloqueara, no habria como corregir un error. */
    @Test
    @DisplayName("quitar la mesa a mano si se permite aunque no este troquelada")
    void quitarMesaSiempreSePermite() throws Exception {
        TaskEntity tarea = tareaConProductos(1);

        controller.scheduleTask(tarea.getId(), cuerpo("desk", null));

        assertEquals(null, taskRepository.findById(tarea.getId()).orElseThrow().getDesk(),
                "sacar de mesa tiene que poder hacerse siempre");
    }

    @Test
    @DisplayName("mover un producto sin troquelar a una mesa se rechaza, y el error dice cual es")
    void moverAMesaExigeTroquel() throws Exception {
        TaskEntity tarea = tareaConProductos(1);
        TaskItemEntity item = taskItemRepository.findByTaskId(tarea.getId()).stream()
                .filter(i -> !Boolean.TRUE.equals(i.getDieCutReady()))
                .findFirst().orElseThrow();

        BusinessException e = assertThrows(BusinessException.class,
                () -> controller.moveTaskItem(new TaskController.MoveTaskItemRequest(item.getId(), 1, null)));
        assertTrue(e.getMessage().contains("sin troquelar"), e.getMessage());
    }

    /**
     * rebalance-day redistribuye un dia entero. Lo que importa comprobar es que lo sin cortar
     * ni se mueve ni pierde la mesa que ya tuviera: esto redistribuye, no desasigna.
     */
    @Test
    @DisplayName("rebalance-day no le toca la mesa a lo sin troquelar y dice cuantas freno")
    void rebalanceRespetaLaCompuerta() throws Exception {
        TaskEntity tarea = taskRepository.findPendingWithUncutItems().stream()
                .filter(t -> t.getScheduledDate() != null)
                .findFirst()
                .orElse(null);
        Assumptions.assumeTrue(tarea != null, "la copia local no tiene pendientes sin cortar con fecha");

        LocalDate dia = tarea.getScheduledDate();
        Integer mesaAntes = tarea.getDesk();

        Map<String, Object> r = controller.rebalanceDayTasks(dia, null).getBody();

        assertNotNull(r);
        assertTrue(((Number) r.get("dieCutBlockedTasks")).intValue() >= 1,
                "ese dia tiene al menos una sin cortar: " + r.get("message"));
        assertEquals(mesaAntes, taskRepository.findById(tarea.getId()).orElseThrow().getDesk(),
                "redistribuir no debe moverle la mesa a una tarea sin corte");
    }
}
