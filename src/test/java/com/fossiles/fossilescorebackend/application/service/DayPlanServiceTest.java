package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.CreateManualTaskRequest.ManualTaskItemRequest;
import com.fossiles.fossilescorebackend.application.dto.request.DayPlanRequest;
import com.fossiles.fossilescorebackend.application.dto.response.DayPlanConfirmResponse;
import com.fossiles.fossilescorebackend.application.dto.response.DayPlanPreviewResponse;
import com.fossiles.fossilescorebackend.application.dto.response.DayPlanPreviewResponse.Status;
import com.fossiles.fossilescorebackend.application.dto.response.DayPlanPreviewResponse.TaskPreview;
import com.fossiles.fossilescorebackend.application.dto.response.DeskCountDayResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.DayPlanConflictException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.ProductionPlanningLock;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskItemEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * «Armar el día»: vista previa y confirmación. Servicios reales sobre repositorios simulados
 * con datos en memoria ({@link OrganizerTestKit}); lo que no se puede probar aquí (candado
 * real de Postgres, transacción) lo cubre la prueba de integración con Testcontainers.
 */
class DayPlanServiceTest {

    private static final LocalDate JUEVES = LocalDate.of(2026, 10, 15);
    private static final LocalDate VIERNES = LocalDate.of(2026, 10, 16);
    private static final LocalDate SABADO = LocalDate.of(2026, 10, 17);
    private static final LocalDate LUNES = LocalDate.of(2026, 10, 19);

    private OrganizerTestKit kit;
    private ProductionDeskCountService deskCount;
    private LeatherRequirementService leather;
    private ProductionPlanningLock planningLock;
    private EntityManager entityManager;
    private DayPlanService service;

    private int numDesks = 2;
    private final Map<LocalDate, List<TaskEntity>> existing = new HashMap<>();
    private final List<TaskEntity> carryOver = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        kit = new OrganizerTestKit();
        kit.order(1);
        kit.order(2);
        kit.product(10, 1.0);   // 1 h por unidad
        kit.item(100, 1, 10, 20);
        kit.item(200, 2, 10, 20);

        deskCount = mock(ProductionDeskCountService.class);
        when(deskCount.getDay(any())).thenAnswer(inv -> DeskCountDayResponse.builder()
                .effectiveDate(inv.getArgument(0)).numDesks(numDesks).build());

        when(kit.taskRepository.findByScheduledDate(any()))
                .thenAnswer(inv -> existing.getOrDefault(inv.<LocalDate>getArgument(0), List.of()));
        when(kit.taskRepository.findUnfinishedCarryOver(any())).thenAnswer(inv -> carryOver);

        leather = mock(LeatherRequirementService.class);
        when(leather.resolveNeed(any(), any(), anyInt()))
                .thenReturn(LeatherRequirementService.LeatherNeed.ok(null, BigDecimal.ZERO));
        when(leather.canCover(any(), any())).thenReturn(true);

        planningLock = mock(ProductionPlanningLock.class);
        entityManager = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);

        service = new DayPlanService(kit.service, kit.productionOrderRepository, kit.productionOrderItemRepository,
                kit.taskRepository, kit.taskItemRepository, kit.productRepository, deskCount,
                new TaskDeskHoursService(kit.taskItemRepository), leather, planningLock, entityManager);
    }

    // ==================== helpers ====================

    private static ManualTaskItemRequest line(long itemId, int qty) {
        return ManualTaskItemRequest.builder().productionOrderItemId(itemId).quantity(qty).build();
    }

    private static DayPlanRequest.Draft draft(String clientId, long orderId, ManualTaskItemRequest... lines) {
        return DayPlanRequest.Draft.builder().clientId(clientId).productionOrderId(orderId)
                .items(List.of(lines)).build();
    }

    private static DayPlanRequest plan(LocalDate anchor, DayPlanRequest.Draft... drafts) {
        return DayPlanRequest.builder().anchorDate(anchor).horizonDays(5).tasks(List.of(drafts)).build();
    }

    private static TaskPreview task(DayPlanPreviewResponse preview, String clientId) {
        return preview.getTasks().stream().filter(t -> t.getClientId().equals(clientId)).findFirst().orElseThrow();
    }

    private TaskEntity existingTask(long id, String status, Integer desk, LocalDate date, double hours) {
        return TaskEntity.builder().id(id).status(status).desk(desk).scheduledDate(date)
                .estimatedHours(hours).productionOrderCode("OPK-9").build();
    }

    private DayPlanConfirmResponse confirmed(DayPlanRequest request) throws Exception {
        DayPlanRequest withFp = copyWithFingerprint(request, service.preview(request).getPlanFingerprint());
        return service.confirm(withFp);
    }

    private static DayPlanRequest copyWithFingerprint(DayPlanRequest r, String fingerprint) {
        return DayPlanRequest.builder().anchorDate(r.getAnchorDate()).horizonDays(r.getHorizonDays())
                .orderPriorities(r.getOrderPriorities()).tasks(r.getTasks())
                .planFingerprint(fingerprint).acceptChanges(r.getAcceptChanges()).build();
    }

    // ==================== vista previa ====================

    @Test
    @DisplayName("La vista previa no guarda nada ni bloquea ítems")
    void vistaPreviaNoPersiste() throws Exception {
        DayPlanRequest request = plan(JUEVES, draft("t1", 1, line(100, 3)));
        request.setOrderPriorities(Map.of("1", 5));

        DayPlanPreviewResponse preview = service.preview(request);

        assertThat(task(preview, "t1").getStatus()).isEqualTo(Status.OK);
        verify(kit.taskRepository, never()).save(any());
        verify(kit.taskItemRepository, never()).save(any());
        verify(kit.productionOrderRepository, never()).save(any());
        verify(kit.productionOrderRepository, never()).saveAll(any());
        verify(kit.productionOrderItemRepository, never()).findAllByIdForUpdate(any());
        verify(planningLock, never()).acquire();
        assertThat(kit.orders.get(1L).getSchedulingPriority()).isNull();
        assertThat(kit.orders.get(1L).getStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("Devuelve límites, un solo asOf, huella y cargas por día y mesa")
    void contratoDeLaRespuesta() throws Exception {
        existing.put(JUEVES, List.of(existingTask(1, "PENDING", 1, JUEVES, 1.5)));

        DayPlanPreviewResponse preview = service.preview(plan(JUEVES, draft("t1", 1, line(100, 2))));

        assertThat(preview.getAsOf()).isNotNull();
        assertThat(preview.getPlanFingerprint()).hasSize(64);
        assertThat(preview.getLimits().getIdealHours()).isEqualTo(4.0);
        assertThat(preview.getLimits().getMaxHours()).isEqualTo(5.0);
        assertThat(preview.getLimits().getNumDesks()).isEqualTo(2);
        assertThat(preview.isBlocked()).isFalse();
        // 2 mesas x 5 días hábiles; la mesa 2 (vacía) recibe las 2 h.
        assertThat(preview.getDayLoads()).hasSize(10);
        assertThat(preview.getDayLoads()).anySatisfy(l -> {
            assertThat(l.getDate()).isEqualTo(JUEVES);
            assertThat(l.getDesk()).isEqualTo(1);
            assertThat(l.getCurrentHours()).isEqualTo(1.5);
            assertThat(l.getAddedHours()).isZero();
        });
        TaskPreview t1 = task(preview, "t1");
        assertThat(t1.getProposedDate()).isEqualTo(JUEVES);
        assertThat(t1.getIndicativeDesk()).isEqualTo(2);
        assertThat(t1.getBaseHours()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("La mesa orientativa es determinista: el empate lo gana la mesa menor")
    void mesaDeterminista() throws Exception {
        numDesks = 6;
        DayPlanRequest request = plan(JUEVES, draft("t1", 1, line(100, 1)));
        for (int i = 0; i < 30; i++) {
            assertThat(task(service.preview(request), "t1").getIndicativeDesk()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("Tope: 4,5 h avisa, 5 h pasa, más de 5 h bloquea")
    void topeDeCincoHoras() throws Exception {
        kit.item(101, 1, 10, 50);

        DayPlanPreviewResponse preview = service.preview(plan(JUEVES,
                draft("ok4", 1, line(100, 4)), draft("warn", 1, line(101, 5)), draft("over", 1, line(101, 6))));

        assertThat(task(preview, "ok4").getStatus()).isEqualTo(Status.OK);
        assertThat(task(preview, "warn").getStatus()).isEqualTo(Status.WARN);
        assertThat(task(preview, "over").getStatus()).isEqualTo(Status.BLOCKED);
        assertThat(task(preview, "over").getMessages()).anyMatch(m -> m.contains("excede el máximo"));
        assertThat(preview.isBlocked()).isTrue();
    }

    @Test
    @DisplayName("OPL va primero y no cuenta contra el límite")
    void oplPrimeroYSinCupo() throws Exception {
        numDesks = 1;
        kit.opl(3);
        kit.item(300, 3, 10, 30);

        // La OPL llega al final de la lista pero se reparte antes y no ocupa la mesa.
        DayPlanPreviewResponse preview = service.preview(plan(JUEVES,
                draft("normal", 1, line(100, 4)), draft("opl", 3, line(300, 20))));

        assertThat(task(preview, "opl").getBaseHours()).isZero();
        assertThat(task(preview, "opl").getTotalHours()).isEqualTo(20.0);
        assertThat(task(preview, "opl").getProposedDate()).isEqualTo(JUEVES);
        assertThat(task(preview, "normal").getProposedDate()).isEqualTo(JUEVES);
        assertThat(task(preview, "normal").getIndicativeDesk()).isEqualTo(1);
    }

    @Test
    @DisplayName("Las prioridades recibidas ordenan el reparto; las de OPL se ignoran")
    void prioridadesOrdenanElReparto() throws Exception {
        numDesks = 1;
        kit.opl(3);
        kit.item(300, 3, 10, 30);
        // Sin prioridades gana la OP 1 (más antigua). Con 2 -> prioridad 2, la OP 2 pasa primero.
        DayPlanRequest request = plan(JUEVES, draft("a", 1, line(100, 4)), draft("b", 2, line(200, 4)));
        assertThat(task(service.preview(request), "a").getProposedDate()).isEqualTo(JUEVES);
        assertThat(task(service.preview(request), "b").getProposedDate()).isEqualTo(VIERNES);

        // OPL 3 con prioridad 99 no cambia su lugar (0): sigue primero.
        request.setOrderPriorities(Map.of("2", 2, "1", 3, "3", 99));
        DayPlanPreviewResponse preview = service.preview(request);
        assertThat(task(preview, "b").getProposedDate()).isEqualTo(JUEVES);
        assertThat(task(preview, "a").getProposedDate()).isEqualTo(VIERNES);
    }

    @Test
    @DisplayName("Sábado y domingo: el ancla pasa al lunes y una fecha de fin de semana bloquea")
    void soloDiasHabiles() throws Exception {
        DayPlanRequest request = plan(SABADO, draft("auto", 1, line(100, 2)));
        assertThat(task(service.preview(request), "auto").getProposedDate()).isEqualTo(LUNES);

        DayPlanRequest.Draft fixed = draft("sab", 1, line(100, 2));
        fixed.setScheduledDate(SABADO);
        DayPlanPreviewResponse preview = service.preview(plan(JUEVES, fixed));
        assertThat(task(preview, "sab").getStatus()).isEqualTo(Status.BLOCKED);
        assertThat(task(preview, "sab").getMessages()).anyMatch(m -> m.contains("fin de semana"));
    }

    @Test
    @DisplayName("Un ítem repartido en dos tareas descuenta el restante acumulado")
    void itemPartidoEnDosTareas() throws Exception {
        kit.assigned.put(100L, 12); // restan 8
        DayPlanPreviewResponse ok = service.preview(plan(JUEVES,
                draft("a", 1, line(100, 4)), draft("b", 1, line(100, 4))));
        assertThat(ok.isBlocked()).isFalse();

        DayPlanPreviewResponse short_ = service.preview(plan(JUEVES,
                draft("a", 1, line(100, 4)), draft("b", 1, line(100, 5))));
        assertThat(task(short_, "a").getStatus()).isEqualTo(Status.OK);
        assertThat(task(short_, "b").getStatus()).isEqualTo(Status.BLOCKED);
        assertThat(task(short_, "b").getMessages()).anyMatch(m -> m.contains("restante 4 de 20"));
    }

    @Test
    @DisplayName("Las PENDING sin mesa cuentan carga en una mesa virtual; las de solo cinchos no")
    void pendientesSinMesaCuentanCarga() throws Exception {
        numDesks = 1;
        existing.put(JUEVES, List.of(existingTask(1, "PENDING", null, JUEVES, 3.0)));

        // 3 h sin mesa + 2 h nuevas > 4 h en la única mesa: pasa al viernes.
        DayPlanPreviewResponse preview = service.preview(plan(JUEVES, draft("t", 1, line(100, 2))));
        assertThat(task(preview, "t").getProposedDate()).isEqualTo(VIERNES);
        assertThat(preview.getDayLoads()).anySatisfy(l -> {
            assertThat(l.getDate()).isEqualTo(JUEVES);
            assertThat(l.getCurrentHours()).isEqualTo(3.0);
        });

        // Si esa tarea es solo de cinchos, no ocupa el cupo del centro.
        kit.product(11, 1.0).setCinchoType("FOSSILES");
        when(kit.taskItemRepository.findByTaskIdIn(anyList()))
                .thenReturn(List.of(TaskItemEntity.builder().taskId(1L).productId(11L).build()));
        assertThat(task(service.preview(plan(JUEVES, draft("t", 1, line(100, 2)))), "t").getProposedDate())
                .isEqualTo(JUEVES);
    }

    @Test
    @DisplayName("Las IN_PROGRESS de días previos se pliegan al primer día hábil")
    void carryOverSePliegaAlPrimerDia() throws Exception {
        numDesks = 1;
        carryOver.add(existingTask(7, "IN_PROGRESS", 1, JUEVES.minusDays(2), 4.0));

        DayPlanPreviewResponse preview = service.preview(plan(JUEVES, draft("t", 1, line(100, 1))));

        assertThat(task(preview, "t").getProposedDate()).isEqualTo(VIERNES);
        assertThat(preview.getDayLoads()).anySatisfy(l -> {
            assertThat(l.getDate()).isEqualTo(JUEVES);
            assertThat(l.getCurrentHours()).isEqualTo(4.0);
        });
    }

    @Test
    @DisplayName("Sin lugar en el horizonte: notPlaced con el día siguiente")
    void sinLugarEnElHorizonte() throws Exception {
        numDesks = 1;
        existing.put(JUEVES, List.of(existingTask(1, "PENDING", 1, JUEVES, 4.0)));
        DayPlanRequest request = plan(JUEVES, draft("t", 1, line(100, 2)));
        request.setHorizonDays(1);

        DayPlanPreviewResponse preview = service.preview(request);

        assertThat(preview.getNotPlaced()).singleElement().satisfies(n -> {
            assertThat(n.getClientId()).isEqualTo("t");
            assertThat(n.getNextDayCandidate()).isEqualTo(VIERNES);
        });
        assertThat(task(preview, "t").getIndicativeDesk()).isNull();
        assertThat(task(preview, "t").getStatus()).isEqualTo(Status.WARN);
        assertThat(preview.isBlocked()).isFalse();
    }

    @Test
    @DisplayName("Falta de cuero es solo un aviso")
    void faltaDeCueroAvisa() throws Exception {
        LeatherRequirementService.LeatherNeed need = LeatherRequirementService.LeatherNeed.ok(5L, BigDecimal.TEN);
        when(leather.resolveNeed(any(), any(), anyInt())).thenReturn(need);
        when(leather.canCover(any(), any())).thenReturn(false);
        when(leather.shortageMessage(any(), any())).thenReturn("Cuero insuficiente");

        TaskPreview t = task(service.preview(plan(JUEVES, draft("t", 1, line(100, 2)))), "t");

        assertThat(t.getStatus()).isEqualTo(Status.WARN);
        assertThat(t.getMessages()).anyMatch(m -> m.contains("Cuero insuficiente"));
    }

    @Test
    @DisplayName("Las observaciones no pueden empezar por «Auto-plan»")
    void observacionAutoPlanBloquea() throws Exception {
        DayPlanRequest.Draft d = draft("t", 1, line(100, 1));
        d.setObservations("Auto-plan jefe");
        assertThat(task(service.preview(plan(JUEVES, d)), "t").getStatus()).isEqualTo(Status.BLOCKED);
    }

    @Test
    @DisplayName("OPL/OPCK que pasan del cupo el día ancla se marcan overcapAnchor")
    void overcapEnElDiaAncla() throws Exception {
        numDesks = 1;
        kit.order(5, "OPCK-5", "CLIENTE_KIOSKO", "PENDING");
        kit.item(500, 5, 10, 10);
        existing.put(JUEVES, List.of(existingTask(1, "PENDING", 1, JUEVES, 4.0)));

        TaskPreview t = task(service.preview(plan(JUEVES, draft("k", 5, line(500, 2)))), "k");

        assertThat(t.isOvercapAnchor()).isTrue();
        assertThat(t.getProposedDate()).isEqualTo(JUEVES);
        assertThat(t.getStatus()).isEqualTo(Status.WARN);
    }

    // ==================== confirmar ====================

    @Test
    @DisplayName("Confirmar crea sin mesa y sin corte, con la fecha propuesta, y sube la OP a IN_PROGRESS")
    void confirmarCreaSinMesaYSinCorte() throws Exception {
        numDesks = 1;
        existing.put(JUEVES, List.of(existingTask(1, "PENDING", 1, JUEVES, 3.0)));

        DayPlanConfirmResponse response = confirmed(plan(JUEVES,
                draft("a", 1, line(100, 3)), draft("b", 2, line(200, 2))));

        assertThat(response.getCreated()).extracting("clientId").containsExactly("a", "b");
        assertThat(kit.savedTasks).hasSize(2);
        assertThat(kit.savedTasks).allSatisfy(t -> {
            assertThat(t.getDesk()).isNull();
            assertThat(t.getDieCutReady()).isFalse();
            assertThat(t.getStatus()).isEqualTo("PENDING");
        });
        // La mesa única ya tiene 3 h el jueves: «a» (3 h) pasa al viernes y «b» (2 h) ya no cabe
        // con ella ese día, así que va al lunes.
        assertThat(kit.savedTasks).extracting(TaskEntity::getScheduledDate).containsExactly(VIERNES, LUNES);
        assertThat(response.getCreated().get(0).getScheduledDate()).isEqualTo(VIERNES);
        assertThat(response.getCreated().get(0).getTaskId()).isNotNull();
        assertThat(response.getCreated().get(0).getCode()).startsWith("TK-");
        assertThat(kit.orders.get(1L).getStatus()).isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("Todo o nada: si una tarea no es válida no se crea ninguna")
    void todoONada() throws Exception {
        kit.item(101, 1, 10, 50);
        DayPlanRequest request = plan(JUEVES, draft("ok", 1, line(100, 2)), draft("mala", 1, line(101, 6)));

        assertThatThrownBy(() -> service.confirm(copyWithFingerprint(request, "x")))
                .isInstanceOfSatisfying(DayPlanConflictException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(DayPlanConflictException.PLAN_BLOCKED);
                    assertThat(e.getFailures()).extracting("clientId").containsExactly("mala");
                    assertThat(e.getPreview().isBlocked()).isTrue();
                });
        verify(kit.taskRepository, never()).save(any());
        verify(kit.taskCodeGenerator, never()).generateTaskCode();
    }

    @Test
    @DisplayName("Cantidad que ya tomó otro: 409 QTY_UNAVAILABLE y no se crea nada")
    void cantidadNoDisponible() throws Exception {
        DayPlanRequest request = plan(JUEVES, draft("a", 1, line(100, 4)), draft("b", 1, line(100, 4)));
        String fingerprint = service.preview(request).getPlanFingerprint();
        kit.assigned.put(100L, 15); // alguien tomó 15 de 20: queda 5, pero la propuesta pide 8

        assertThatThrownBy(() -> service.confirm(copyWithFingerprint(request, fingerprint)))
                .isInstanceOfSatisfying(DayPlanConflictException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(DayPlanConflictException.QTY_UNAVAILABLE);
                    assertThat(e.getFailures()).extracting("clientId").containsExactly("b");
                });
        verify(kit.taskRepository, never()).save(any());
    }

    @Test
    @DisplayName("Si la carga cambió: 409 PLAN_CHANGED con vista previa nueva; acceptChanges la aplica")
    void huellaDistintaYAcceptChanges() throws Exception {
        DayPlanRequest request = plan(JUEVES, draft("a", 1, line(100, 2)));
        String fingerprint = service.preview(request).getPlanFingerprint();
        existing.put(JUEVES, List.of(existingTask(1, "PENDING", 1, JUEVES, 2.5))); // alguien cargó una mesa

        assertThatThrownBy(() -> service.confirm(copyWithFingerprint(request, fingerprint)))
                .isInstanceOfSatisfying(DayPlanConflictException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(DayPlanConflictException.PLAN_CHANGED);
                    assertThat(e.getPreview().getPlanFingerprint()).isNotEqualTo(fingerprint);
                });
        verify(kit.taskRepository, never()).save(any());

        request.setAcceptChanges(true);
        DayPlanConfirmResponse response = service.confirm(copyWithFingerprint(request, fingerprint));
        assertThat(response.getCreated()).hasSize(1);
        assertThat(kit.savedTasks).hasSize(1);
    }

    @Test
    @DisplayName("Los restantes de los ítems y las prioridades también cambian la huella")
    void huellaCubreRestantesYPrioridades() throws Exception {
        DayPlanRequest request = plan(JUEVES, draft("a", 1, line(100, 2)));
        String base = service.preview(request).getPlanFingerprint();

        assertThat(service.preview(request).getPlanFingerprint()).isEqualTo(base);

        kit.assigned.put(100L, 1);
        String conRestante = service.preview(request).getPlanFingerprint();
        assertThat(conRestante).isNotEqualTo(base);

        request.setOrderPriorities(Map.of("1", 7));
        assertThat(service.preview(request).getPlanFingerprint()).isNotEqualTo(conRestante);
    }

    @Test
    @DisplayName("Doble confirmación: la segunda no duplica tareas")
    void dobleConfirmacionNoDuplica() throws Exception {
        DayPlanRequest request = plan(JUEVES, draft("a", 1, line(100, 5)), draft("b", 1, line(100, 5)));
        String fingerprint = service.preview(request).getPlanFingerprint();
        DayPlanRequest first = copyWithFingerprint(request, fingerprint);

        service.confirm(first);
        assertThat(kit.savedTasks).hasSize(2);

        // La base ya refleja las unidades tomadas por la primera confirmación.
        kit.assigned.put(100L, 10);
        assertThatThrownBy(() -> service.confirm(first)).isInstanceOf(DayPlanConflictException.class);
        assertThat(kit.savedTasks).hasSize(2);
    }

    @Test
    @DisplayName("Confirmar guarda las prioridades; la vista previa no")
    void confirmarGuardaPrioridades() throws Exception {
        DayPlanRequest request = plan(JUEVES, draft("a", 1, line(100, 2)), draft("b", 2, line(200, 2)));
        request.setOrderPriorities(Map.of("2", 2, "1", 3, "99999", 4, "2x", 5));

        confirmed(request);

        assertThat(kit.orders.get(2L).getSchedulingPriority()).isEqualTo(2);
        assertThat(kit.orders.get(1L).getSchedulingPriority()).isEqualTo(3);
        verify(kit.productionOrderRepository).saveAll(anyList());
    }

    @Test
    @DisplayName("Orden de bloqueos: candado de planificación y luego los ítems")
    void ordenDeBloqueos() throws Exception {
        confirmed(plan(JUEVES, draft("a", 1, line(100, 2)), draft("b", 2, line(200, 2))));

        InOrder order = inOrder(planningLock, kit.productionOrderItemRepository);
        order.verify(planningLock).acquire();
        order.verify(kit.productionOrderItemRepository).findAllByIdForUpdate(List.of(100L, 200L));
    }

    @Test
    @DisplayName("Sin candado a tiempo: 409 LOCK_TIMEOUT y no se crea nada")
    void lockTimeout() throws Exception {
        DayPlanRequest request = plan(JUEVES, draft("a", 1, line(100, 2)));
        String fingerprint = service.preview(request).getPlanFingerprint();
        doThrow(new LockTimeoutException("lock timeout")).when(planningLock).acquire();

        assertThatThrownBy(() -> service.confirm(copyWithFingerprint(request, fingerprint)))
                .isInstanceOfSatisfying(DayPlanConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo(DayPlanConflictException.LOCK_TIMEOUT));
        verify(kit.taskRepository, never()).save(any());
    }

    @Test
    @DisplayName("Confirmar exige la huella y un cuerpo bien formado")
    void validaElCuerpo() {
        DayPlanRequest sinHuella = plan(JUEVES, draft("a", 1, line(100, 1)));
        assertThatThrownBy(() -> service.confirm(sinHuella)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("planFingerprint");

        DayPlanRequest repetido = plan(JUEVES, draft("a", 1, line(100, 1)), draft("a", 1, line(100, 1)));
        assertThatThrownBy(() -> service.preview(repetido)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("repetido");

        assertThatThrownBy(() -> service.preview(plan(null, draft("a", 1, line(100, 1)))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("anchorDate");
    }
}
