package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.infrastructure.persistence.ProductionPlanningLock;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Fija el turno único para repartir mesas.
 *
 * <p>Lo que se rompía: el candado era un {@code ReentrantLock} tomado dentro del
 * método transaccional, o sea que se soltaba antes del commit. El segundo hilo
 * entraba, leía la carga de las mesas sin ver las tareas que el primero acababa de
 * crear, y volvía a llenar la misma mesa. Encima, las entradas "silenciosas"
 * llamaban al método transaccional con {@code this}, saltándose el proxy de Spring:
 * el auto-plan del cron corría sin transacción propia.
 *
 * <p>Estas pruebas no tocan la base. Lo que verifican es lo que hace falta para que
 * el candado de Postgres sirva: que se pase por el proxy, que las anotaciones sigan
 * puestas y que el turno se pida antes de leer nada. El comportamiento del candado
 * en sí ({@code pg_advisory_xact_lock} bloquea hasta el commit) se probó contra
 * Postgres a mano; no cabe en una prueba sin base de datos.
 */
@ExtendWith(MockitoExtension.class)
class ProductionAutoPlannerServiceLockTest {

    @Mock private ProductionOrderRepository productionOrderRepository;
    @Mock private ProductionOrderItemRepository productionOrderItemRepository;
    @Mock private ProductRepository productRepository;
    @Mock private TaskItemRepository taskItemRepository;
    @Mock private TaskRepository taskRepository;
    @Mock private TaskOrganizerService taskOrganizerService;
    @Mock private LeatherRequirementService leatherRequirementService;
    @Mock private ProductionDeskCountService productionDeskCountService;
    @Mock private SmartMaterialRequestService smartMaterialRequestService;
    @Mock private ProductionPlanningLock productionPlanningLock;
    @Mock private TaskDeskHoursService taskDeskHoursService;
    @Mock private ObjectProvider<ProductionAutoPlannerService> selfProvider;
    @Mock private ProductionAutoPlannerService proxy;

    private ProductionAutoPlannerService service;

    @BeforeEach
    void setUp() {
        service = new ProductionAutoPlannerService(
                productionOrderRepository,
                productionOrderItemRepository,
                productRepository,
                taskItemRepository,
                taskRepository,
                taskOrganizerService,
                leatherRequirementService,
                productionDeskCountService,
                smartMaterialRequestService,
                productionPlanningLock,
                taskDeskHoursService,
                selfProvider);
    }

    @Test
    void elAutoPlanGlobalDelegaEnElProxyEnVezDeEjecutarseEnSitio() throws Exception {
        when(selfProvider.getObject()).thenReturn(proxy);

        service.planAllQuietly();

        verify(proxy).planPending();
        // Si se ejecutara con `this`, aquí ya se habrían leído las órdenes activas
        // sin transacción y, por tanto, sin candado.
        verifyNoInteractions(productionOrderRepository, productionPlanningLock);
    }

    @Test
    void elAutoPlanDeUnaOrdenDelegaEnElProxy() throws Exception {
        when(selfProvider.getObject()).thenReturn(proxy);

        service.planQuietly(77L);

        verify(proxy).planOrder(77L);
        verifyNoInteractions(productionOrderRepository, productionPlanningLock);
    }

    @Test
    void sinOrdenNoSeMolestaANadie() {
        service.planQuietly(null);

        verifyNoInteractions(selfProvider, productionOrderRepository, productionPlanningLock);
    }

    @Test
    void elTurnoSePideAntesDeLeerLaCargaDeLasMesas() {
        ProductionOrderEntity orden = ProductionOrderEntity.builder().id(5L).status("PENDING").build();
        when(productionOrderRepository.findById(5L)).thenReturn(Optional.of(orden));
        doThrow(new IllegalStateException("turno ocupado")).when(productionPlanningLock).acquire();

        assertThatThrownBy(() -> service.planOrder(5L)).isInstanceOf(IllegalStateException.class);

        verify(productionPlanningLock).acquire();
        // Nada de leer la agenda ni de crear tareas antes de tener el turno: si se
        // lee antes, la foto puede quedar vieja aunque el candado llegue después.
        verifyNoInteractions(taskRepository, taskOrganizerService, productionDeskCountService);
    }

    @Test
    void lasEntradasQueTomanElTurnoSiguenSiendoTransaccionales() throws Exception {
        assertThat(ProductionAutoPlannerService.class.getMethod("planPending").getAnnotation(Transactional.class))
                .as("sin transacción, pg_advisory_xact_lock no tiene a qué amarrarse")
                .isNotNull();
        assertThat(ProductionAutoPlannerService.class.getMethod("planOrder", Long.class)
                .getAnnotation(Transactional.class))
                .as("sin transacción, pg_advisory_xact_lock no tiene a qué amarrarse")
                .isNotNull();
    }
}
