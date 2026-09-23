package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.infrastructure.persistence.ProductionPlanningLock;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
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

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Fija el turno único para repartir mesas y que el plan no se dispare solo.
 */
@ExtendWith(MockitoExtension.class)
class ProductionAutoPlannerServiceLockTest {

    @Mock private ProductionOrderRepository productionOrderRepository;
    @Mock private ProductionOrderItemRepository productionOrderItemRepository;
    @Mock private ProductRepository productRepository;
    @Mock private ColorRepository colorRepository;
    @Mock private TaskItemRepository taskItemRepository;
    @Mock private TaskRepository taskRepository;
    @Mock private TaskOrganizerService taskOrganizerService;
    @Mock private LeatherRequirementService leatherRequirementService;
    @Mock private ProductionDeskCountService productionDeskCountService;
    @Mock private SmartMaterialRequestService smartMaterialRequestService;
    @Mock private ProductionPlanningLock productionPlanningLock;
    @Mock private TaskDeskHoursService taskDeskHoursService;
    @Mock private ObjectProvider<ProductionAutoPlannerService> selfProvider;
    @Mock private ProductionTaskLifecycleService productionTaskLifecycleService;
    @Mock private ProductionAutoPlannerService proxy;

    private ProductionAutoPlannerService service;

    @BeforeEach
    void setUp() {
        service = new ProductionAutoPlannerService(
                productionOrderRepository,
                productionOrderItemRepository,
                productRepository,
                colorRepository,
                taskItemRepository,
                taskRepository,
                taskOrganizerService,
                leatherRequirementService,
                productionDeskCountService,
                smartMaterialRequestService,
                productionPlanningLock,
                taskDeskHoursService,
                selfProvider,
                productionTaskLifecycleService);
    }

    @Test
    void planQuietlyYaNoDisparaElPlanAutomatico() {
        service.planQuietly(77L);
        service.planAllQuietly();
        verifyNoInteractions(selfProvider, productionOrderRepository, productionPlanningLock);
    }

    @Test
    void elTurnoSePideAntesDeLeerLaCargaDeLasMesas() {
        ProductionOrderEntity orden = ProductionOrderEntity.builder().id(5L).status("PENDING").build();
        when(productionOrderRepository.findById(5L)).thenReturn(Optional.of(orden));
        doThrow(new IllegalStateException("turno ocupado")).when(productionPlanningLock).acquire();

        assertThatThrownBy(() -> service.planOrder(5L)).isInstanceOf(IllegalStateException.class);

        verify(productionPlanningLock).acquire();
        verifyNoInteractions(taskOrganizerService, productionDeskCountService);
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
        assertThat(ProductionAutoPlannerService.class.getMethod("regenerate", Long.class, LocalDate.class)
                .getAnnotation(Transactional.class))
                .isNotNull();
    }
}
