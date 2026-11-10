package com.fossiles.fossilescorebackend.infrastructure.persistence;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.test.context.ContextConfiguration;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Consultas del Tablero (GET /tasks/board) y sus conteos, sobre Hibernate + H2 para validar el
 * JPQL de verdad y qué tareas entran.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:task_board;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = TaskBoardQueriesTest.Cfg.class)
class TaskBoardQueriesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableJpaRepositories(
            basePackageClasses = TaskRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                    classes = {TaskRepository.class, TaskItemRepository.class, ProductionOrderRepository.class}))
    static class Cfg {
        @Bean
        PersistenceManagedTypes managedTypes() {
            return PersistenceManagedTypes.of(TaskEntity.class.getName(), TaskItemEntity.class.getName(),
                    ProductionOrderEntity.class.getName(), ProductionOrderItemEntity.class.getName(),
                    // Lo usan las consultas de cinchos de ProductionOrderRepository.
                    ProductEntity.class.getName());
        }
    }

    private static final LocalDate HOY = LocalDate.of(2026, 10, 15);
    private static final LocalDate AYER = HOY.minusDays(1);
    private static final LocalDate MANANA = HOY.plusDays(1);

    @Autowired TaskRepository tasks;
    @Autowired TaskItemRepository items;
    @Autowired ProductionOrderRepository orders;

    private long normalOrder;
    private long cinchoOrder;

    @BeforeEach
    void setUp() {
        items.deleteAll();
        tasks.deleteAll();
        orders.deleteAll();
        normalOrder = orders.save(ProductionOrderEntity.builder().code("OPK-1").orderType("NORMAL").status("PENDING").build()).getId();
        cinchoOrder = orders.save(ProductionOrderEntity.builder().code("OPC-1").orderType("CINCHOS").status("PENDING").build()).getId();
    }

    private TaskEntity task(String code, long orderId, String status, Integer desk, LocalDate date) {
        return tasks.save(TaskEntity.builder().code(code).productionOrderId(orderId).status(status)
                .desk(desk).scheduledDate(date).quantity(1).estimatedHours(1.0).build());
    }

    private List<String> board(LocalDate day) {
        return board(day, null);
    }

    private List<String> board(LocalDate day, LocalDate carryFrom) {
        return tasks.findBoardTasks(day, carryFrom).stream().map(TaskEntity::getCode).toList();
    }

    @Test
    void elTableroTraeLasActivasDelDiaYLasActivasSinMesa() {
        task("hoy-mesa", normalOrder, "PENDING", 1, HOY);
        task("hoy-curso", normalOrder, "IN_PROGRESS", 2, HOY);
        task("hoy-bodega", normalOrder, "AWAITING_WAREHOUSE", null, HOY);
        task("hoy-cerrada", normalOrder, "COMPLETED", 1, HOY);
        task("hoy-cancelada", normalOrder, "CANCELLED", 1, HOY);
        task("sin-mesa-ayer", normalOrder, "PENDING", null, AYER);
        task("sin-mesa-sin-fecha", normalOrder, "PENDING", null, null);
        task("sin-mesa-futura", normalOrder, "PENDING", null, MANANA);
        task("mesa-manana", normalOrder, "PENDING", 3, MANANA);
        task("mesa-ayer", normalOrder, "PENDING", 3, AYER);
        // Esperando bodega de otro día no inunda el tablero (nunca tiene mesa).
        task("bodega-ayer", normalOrder, "AWAITING_WAREHOUSE", null, AYER);

        assertThat(board(HOY)).containsExactlyInAnyOrder(
                "hoy-mesa", "hoy-curso", "hoy-bodega", "sin-mesa-ayer", "sin-mesa-sin-fecha");
    }

    @Test
    void lasEmpezadasAyerSeRetomanHoyEnSuMesa() {
        task("hoy-mesa", normalOrder, "PENDING", 1, HOY);
        task("ayer-curso", normalOrder, "IN_PROGRESS", 3, AYER);
        task("ayer-curso-sin-mesa", normalOrder, "IN_PROGRESS", null, AYER);
        task("ayer-pendiente", normalOrder, "PENDING", 3, AYER);
        task("ayer-cerrada", normalOrder, "COMPLETED", 3, AYER);
        task("ayer-curso-cincho", cinchoOrder, "IN_PROGRESS", 2, AYER);
        // Solo lo del día anterior: lo de más atrás confunde y ya está en «No terminadas».
        task("antier-curso", normalOrder, "IN_PROGRESS", 4, AYER.minusDays(1));

        assertThat(board(HOY, AYER)).containsExactlyInAnyOrder("hoy-mesa", "ayer-curso", "ayer-curso-sin-mesa");
        // Viendo un día pasado no se arrastra nada: se ve como fue.
        assertThat(board(HOY, null)).containsExactlyInAnyOrder("hoy-mesa", "ayer-curso-sin-mesa");
    }

    @Test
    void elTableroExcluyeLasOpDeCinchos() {
        task("normal", normalOrder, "PENDING", 1, HOY);
        task("cincho", cinchoOrder, "PENDING", 1, HOY);
        task("cincho-sin-mesa", cinchoOrder, "PENDING", null, null);

        assertThat(board(HOY)).containsExactly("normal");
    }

    @Test
    void conteosDeLasPestanas() {
        TaskEntity cortar = task("por-cortar", normalOrder, "PENDING", null, HOY);
        items.save(TaskItemEntity.builder().taskId(cortar.getId()).quantity(1).dieCutReady(false).build());
        TaskEntity cortada = task("cortada", normalOrder, "PENDING", 1, HOY);
        items.save(TaskItemEntity.builder().taskId(cortada.getId()).quantity(1).dieCutReady(true).build());
        TaskEntity cincho = task("cincho", cinchoOrder, "PENDING", null, HOY);
        items.save(TaskItemEntity.builder().taskId(cincho.getId()).quantity(1).dieCutReady(false).build());
        task("atrasada", normalOrder, "PENDING", 1, AYER);
        task("no-terminada", normalOrder, "IN_PROGRESS", 1, AYER);
        task("no-terminada-cincho", cinchoOrder, "IN_PROGRESS", 1, AYER);

        assertThat(tasks.countPendingWithUncutItems()).isEqualTo(1);
        assertThat(tasks.countPendingWithUncutItems()).isEqualTo(tasks.findPendingWithUncutItems().size());
        // Backlog: la sin mesa (por-cortar) y la atrasada; la de cincho no cuenta.
        assertThat(tasks.countPendingBacklog(HOY)).isEqualTo(2);
        assertThat(tasks.countUnfinishedCarryOver(HOY)).isEqualTo(1);
        assertThat(tasks.countUnfinishedCarryOver(HOY))
                .isEqualTo(tasks.findUnfinishedCarryOver(HOY).stream()
                        .filter(t -> t.getProductionOrderId() == normalOrder).count());
    }
}
