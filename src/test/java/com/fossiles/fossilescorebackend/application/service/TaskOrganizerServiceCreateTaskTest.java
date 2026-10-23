package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.CreateManualTaskRequest;
import com.fossiles.fossilescorebackend.application.dto.request.CreateManualTaskRequest.ManualTaskItemRequest;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.QuantityUnavailableException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Reglas de creación de una tarea manual (Organizador). Antes de dividir {@code createTask}
 * en {@code prepareDraft} + {@code persistDraft} no había ninguna prueba del tope de 5 h ni
 * de la OP base única: estas pruebas fijan el comportamiento.
 */
class TaskOrganizerServiceCreateTaskTest {

    /** Jueves; el lunes siguiente es 2026-10-19 y el sábado 2026-10-17. */
    private static final LocalDate JUEVES = LocalDate.of(2026, 10, 15);
    private static final LocalDate SABADO = LocalDate.of(2026, 10, 17);

    private OrganizerTestKit kit;

    @BeforeEach
    void setUp() {
        kit = new OrganizerTestKit();
        kit.order(1);
        kit.product(10, 1.0);          // 1 h por unidad: las horas = las unidades
        kit.item(100, 1, 10, 20);
    }

    private static ManualTaskItemRequest line(long itemId, int qty) {
        return ManualTaskItemRequest.builder().productionOrderItemId(itemId).quantity(qty).build();
    }

    private static CreateManualTaskRequest request(long orderId, ManualTaskItemRequest... lines) {
        return CreateManualTaskRequest.builder()
                .productionOrderId(orderId).items(List.of(lines)).scheduledDate(JUEVES).desk(3).build();
    }

    // ==================== tope de horas ====================

    @Test
    @DisplayName("Hasta 5 h base se crea; sobre 5 h no se crea nada")
    void topeDuroDeCincoHoras() throws Exception {
        TaskEntity ok = kit.service.createManualTask(request(1, line(100, 5)));
        assertThat(ok.getEstimatedHours()).isEqualTo(5.0);

        assertThatThrownBy(() -> kit.service.createManualTask(request(1, line(100, 6))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("excede el máximo de 5.0 horas");
        assertThat(kit.savedTasks).hasSize(1);
    }

    @Test
    @DisplayName("Entre 4 h y 5 h se permite: 4 h es ideal, no tope")
    void cuatroHorasEsIdealNoTope() throws Exception {
        assertThat(kit.service.prepareDraft(request(1, line(100, 4)), TaskOrganizerService.CreateMode.MANUAL,
                false, Map.of()).baseHours()).isEqualTo(4.0);
        assertThat(kit.service.prepareDraft(request(1, line(100, 5)), TaskOrganizerService.CreateMode.MANUAL,
                false, Map.of()).baseHours()).isEqualTo(5.0);
    }

    @Test
    @DisplayName("El tope solo aplica a la creación manual, no a la del autoplan")
    void autoCentroNoTieneTopeManual() throws Exception {
        TaskEntity t = kit.service.createAutoCentroTask(request(1, line(100, 6)));
        assertThat(t.getEstimatedHours()).isEqualTo(6.0);
    }

    // ==================== OP base única y OPL ====================

    @Test
    @DisplayName("Una tarea no mezcla OPs base distintas")
    void noMezclaOpsBase() {
        kit.order(2);
        kit.item(200, 2, 10, 5);
        assertThatThrownBy(() -> kit.service.createManualTask(request(1, line(100, 2), line(200, 2))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("distinta a la orden base");
        assertThat(kit.savedTasks).isEmpty();
    }

    @Test
    @DisplayName("Las líneas OPL entran como extra y no cuentan contra el tope")
    void oplComoExtraNoCuenta() throws Exception {
        kit.opl(3);
        kit.item(300, 3, 10, 30);
        // 4 h base + 30 h de OPL: el cliente no marca daySaleExtra y el servidor lo fuerza.
        TaskEntity t = kit.service.createManualTask(request(1, line(100, 4), line(300, 30)));

        assertThat(t.getEstimatedHours()).isEqualTo(34.0);
        assertThat(kit.savedTaskItems).extracting("daySaleExtra").containsExactly(false, true);
    }

    @Test
    @DisplayName("Una tarea de OPL entera pesa cero contra el tope")
    void tareaOplEnteraPesaCero() throws Exception {
        kit.opl(3);
        kit.item(300, 3, 10, 30);
        assertThat(kit.service.prepareDraft(request(3, line(300, 30)), TaskOrganizerService.CreateMode.MANUAL,
                false, Map.of()).baseHours()).isZero();
    }

    @Test
    @DisplayName("Marcar extra una línea que no es OPL se rechaza")
    void extraSoloParaOpl() {
        ManualTaskItemRequest fake = ManualTaskItemRequest.builder()
                .productionOrderItemId(100L).quantity(1).daySaleExtra(true).build();
        assertThatThrownBy(() -> kit.service.createManualTask(request(1, fake)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Solo los productos de órdenes VENTA_EN_LINEA");
    }

    // ==================== restante, cinchos, calendario ====================

    @Test
    @DisplayName("No se puede pedir más de lo que resta del ítem")
    void respetaElRestante() throws Exception {
        kit.assigned.put(100L, 17); // quedan 3 de 20
        assertThatThrownBy(() -> kit.service.createManualTask(request(1, line(100, 4))))
                .isInstanceOf(QuantityUnavailableException.class)
                .hasMessageContaining("restante 3 de 20");
        assertThat(kit.service.createManualTask(request(1, line(100, 3))).getQuantity()).isEqualTo(3);
    }

    @Test
    @DisplayName("Lo que otros borradores del mismo reparto ya piden se resta del restante")
    void restaLoReservadoPorOtrosBorradores() throws Exception {
        // Quedan 20; otros borradores ya piden 16: caben 4, no 5.
        kit.service.prepareDraft(request(1, line(100, 4)), TaskOrganizerService.CreateMode.MANUAL, false,
                Map.of(100L, 16));
        assertThatThrownBy(() -> kit.service.prepareDraft(request(1, line(100, 5)),
                TaskOrganizerService.CreateMode.MANUAL, false, Map.of(100L, 16)))
                .isInstanceOf(QuantityUnavailableException.class);
    }

    @Test
    @DisplayName("Los productos cincho no se crean en el centro")
    void cinchosRechazados() {
        kit.products.get(10L).setCinchoType("FOSSILES");
        assertThatThrownBy(() -> kit.service.createManualTask(request(1, line(100, 1))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cincho");
    }

    @Test
    @DisplayName("Una OP en borrador no admite tareas")
    void opEnBorrador() {
        kit.order(4, "OPI-4", "INTERNA", "DRAFT");
        kit.item(400, 4, 10, 5);
        assertThatThrownBy(() -> kit.service.createManualTask(request(4, line(400, 1))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("borrador");
    }

    @Test
    @DisplayName("Sábado y domingo no se programan")
    void soloLunesAViernes() {
        CreateManualTaskRequest req = request(1, line(100, 1));
        req.setScheduledDate(SABADO);
        assertThatThrownBy(() -> kit.service.createManualTask(req))
                .isInstanceOf(BusinessException.class).hasMessageContaining("fin de semana");
    }

    // ==================== lo que quedó tras dividir createTask ====================

    @Test
    @DisplayName("createManualTask conserva mesa, fecha, estado y sin corte; la OP pasa a IN_PROGRESS")
    void createManualTaskIntacto() throws Exception {
        TaskEntity t = kit.service.createManualTask(request(1, line(100, 2)));

        assertThat(t.getDesk()).isEqualTo(3);
        assertThat(t.getScheduledDate()).isEqualTo(JUEVES);
        assertThat(t.getStatus()).isEqualTo("PENDING");
        assertThat(t.getDieCutReady()).isFalse();
        assertThat(t.getQuantity()).isEqualTo(2);
        assertThat(kit.orders.get(1L).getStatus()).isEqualTo("IN_PROGRESS");
        assertThat(kit.savedTaskItems).hasSize(1);
        assertThat(kit.savedTaskItems.get(0).getTaskId()).isEqualTo(t.getId());
    }

    @Test
    @DisplayName("Crear bloquea los ítems (FOR UPDATE); la vista previa los lee sin bloquear")
    void bloqueoSoloAlCrear() throws Exception {
        kit.service.prepareDraft(request(1, line(100, 1)), TaskOrganizerService.CreateMode.MANUAL, false, Map.of());
        verify(kit.productionOrderItemRepository, never()).findAllByIdForUpdate(any());

        kit.service.createManualTask(request(1, line(100, 1)));
        verify(kit.productionOrderItemRepository).findAllByIdForUpdate(List.of(100L));
    }

    @Test
    @DisplayName("prepareDraft no guarda nada")
    void prepareDraftNoPersiste() throws Exception {
        kit.service.prepareDraft(request(1, line(100, 2)), TaskOrganizerService.CreateMode.MANUAL, false, Map.of());

        verify(kit.taskRepository, never()).save(any());
        verify(kit.taskItemRepository, never()).save(any());
        verify(kit.productionOrderRepository, never()).save(any());
        assertThat(kit.orders.get(1L).getStatus()).isEqualTo("PENDING");
    }

    // ==================== consolidada de Luis Felipe ====================

    /** OPV de Luis Felipe (tipo NORMAL, como casi todas en prod) con un ítem de 1 h por unidad. */
    private void lfOpv(long id, long itemId, int qty) {
        kit.order(id, "OPV-" + id, "NORMAL", "PENDING").setSellerName("Luis Felipe");
        kit.item(itemId, id, 10, qty);
    }

    private static CreateManualTaskRequest consolidated(long orderId, ManualTaskItemRequest... lines) {
        return CreateManualTaskRequest.builder().productionOrderId(orderId).items(List.of(lines))
                .scheduledDate(JUEVES).observations("Urgente").consolidateVendorOrders(true).build();
    }

    @Test
    @DisplayName("Las OPV de una OP consolidada van en una tarea; cada línea lleva su OP")
    void consolidaOpvDeLuisFelipe() throws Exception {
        lfOpv(5, 500, 2);
        lfOpv(6, 600, 1);
        lfOpv(7, 700, 1);
        kit.group(1, "LF-OPV-001", 5, 6, 7);

        TaskEntity t = kit.service.createManualTask(consolidated(5, line(500, 2), line(600, 1), line(700, 1)));

        assertThat(t.getProductionOrderId()).isEqualTo(5L);
        assertThat(t.getEstimatedHours()).isEqualTo(4.0);
        assertThat(t.getObservations()).isEqualTo("Consolidada LF-OPV-001: OPV-5, OPV-6, OPV-7 | Urgente");
        assertThat(kit.savedTaskItems).extracting("observations").containsExactly("OPV-5", "OPV-6", "OPV-7");
        assertThat(kit.savedTaskItems).extracting("daySaleExtra").containsOnly(false);
        // Las tres OP pasan a IN_PROGRESS, no solo la cabecera.
        assertThat(kit.orders.get(6L).getStatus()).isEqualTo("IN_PROGRESS");
        assertThat(kit.orders.get(7L).getStatus()).isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("La consolidada sigue con el tope de 5 h")
    void consolidadaRespetaTope() {
        lfOpv(5, 500, 4);
        lfOpv(6, 600, 4);
        kit.group(1, "LF-OPV-001", 5, 6);
        assertThatThrownBy(() -> kit.service.createManualTask(consolidated(5, line(500, 3), line(600, 3))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("excede el máximo de 5.0 horas");
    }

    @Test
    @DisplayName("Solo se mezclan órdenes de la misma OP consolidada activa")
    void soloMismaConsolidada() {
        lfOpv(5, 500, 2);
        lfOpv(6, 600, 2);
        lfOpv(7, 700, 2);
        kit.group(1, "LF-OPV-001", 5, 6);
        kit.group(2, "LF-OPV-002", 7);

        // OPV-7 es de otra consolidada.
        assertThatThrownBy(() -> kit.service.createManualTask(consolidated(5, line(500, 1), line(700, 1))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("distinta a la orden base");
        // Cabecera fuera de toda consolidada.
        assertThatThrownBy(() -> kit.service.createManualTask(consolidated(1, line(100, 1))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("no está en ninguna OP consolidada activa");
        // Consolidada desagrupada: ya no vale.
        kit.groups.get(1L).setStatus("DISSOLVED");
        assertThatThrownBy(() -> kit.service.createManualTask(consolidated(5, line(500, 1), line(600, 1))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("no está en ninguna OP consolidada activa");
        assertThat(kit.savedTasks).isEmpty();
    }

    @Test
    @DisplayName("Sin la marca de consolidar, dos OPV de la misma consolidada no se mezclan")
    void sinMarcaNoMezcla() {
        lfOpv(5, 500, 2);
        lfOpv(6, 600, 1);
        kit.group(1, "LF-OPV-001", 5, 6);
        assertThatThrownBy(() -> kit.service.createManualTask(request(5, line(500, 1), line(600, 1))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("distinta a la orden base");
    }

    @Test
    @DisplayName("persistDraft guarda con la fecha y la mesa que se le indiquen (mesa NULL = sin asignar)")
    void persistDraftSinMesa() throws Exception {
        var draft = kit.service.prepareDraft(request(1, line(100, 2)), TaskOrganizerService.CreateMode.MANUAL,
                true, Map.of());
        TaskEntity t = kit.service.persistDraft(draft, LocalDate.of(2026, 10, 19), null);

        assertThat(t.getDesk()).isNull();
        assertThat(t.getScheduledDate()).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(t.getDieCutReady()).isFalse();
    }
}
