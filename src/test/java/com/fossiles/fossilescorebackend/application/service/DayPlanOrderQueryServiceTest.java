package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.DayPlanOrderItemsResponse;
import com.fossiles.fossilescorebackend.application.dto.response.DayPlanOrderSummaryResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** Resumen de OPs sin ítems y paginación de los ítems de una sola OP. */
class DayPlanOrderQueryServiceTest {

    private OrganizerTestKit kit;
    private DayPlanOrderQueryService service;

    @BeforeEach
    void setUp() {
        kit = new OrganizerTestKit();
        kit.product(10, 0.5);
        service = new DayPlanOrderQueryService(kit.productionOrderRepository, kit.productionOrderItemRepository,
                kit.productRepository, kit.colorRepository, kit.taskItemRepository);
        when(kit.productionOrderRepository.findActiveOrders()).thenAnswer(inv -> List.copyOf(kit.orders.values()));
        when(kit.productionOrderItemRepository.findByProductionOrderIdIn(any())).thenAnswer(inv -> {
            java.util.Collection<?> ids = inv.getArgument(0);
            return kit.items.values().stream().filter(i -> ids.contains(i.getProductionOrderId())).toList();
        });
    }

    @Test
    void resumenConHorasTotalesYRestantesSinItems() throws Exception {
        kit.order(1);
        kit.item(100, 1, 10, 20);   // 10 h
        kit.item(101, 1, 10, 10);   // 5 h
        kit.assigned.put(100L, 20); // cubierto por completo
        kit.assigned.put(101L, 4);  // restan 6 -> 3 h

        DayPlanOrderSummaryResponse.Page page = service.listOrders("ALL", null, 0, 30);

        assertThat(page.getContent()).singleElement().satisfies(o -> {
            assertThat(o.getCode()).isEqualTo("OPK-1");
            assertThat(o.getItemCount()).isEqualTo(2);
            assertThat(o.getRemainingItemCount()).isEqualTo(1);
            assertThat(o.getTotalHours()).isEqualTo(15.0);
            assertThat(o.getRemainingHours()).isEqualTo(3.0);
        });
    }

    @Test
    void omiteOrdenesSinRestanteBorradorYCinchos() throws Exception {
        kit.order(1);
        kit.item(100, 1, 10, 4);
        kit.assigned.put(100L, 4);                    // sin restante
        kit.order(2, "OPI-2", "INTERNA", "DRAFT");
        kit.item(200, 2, 10, 4);                      // borrador
        kit.order(3, "OPC-3", "CINCHOS", "PENDING");
        kit.item(300, 3, 10, 4);                      // cinchos
        kit.order(4);
        kit.item(400, 4, 10, 4);

        assertThat(service.listOrders("ALL", null, 0, 30).getContent()).extracting("id").containsExactly(4L);
    }

    @Test
    void filtraPorTipoYBuscaPorCodigoOCliente() throws Exception {
        kit.order(1).setCustomerName("Tienda Norte");
        kit.item(100, 1, 10, 4);
        kit.opl(2);
        kit.item(200, 2, 10, 4);

        assertThat(service.listOrders("OPL", null, 0, 30).getContent()).extracting("id").containsExactly(2L);
        assertThat(service.listOrders("ALL", "norte", 0, 30).getContent()).extracting("id").containsExactly(1L);
        assertThatThrownBy(() -> service.listOrders("XYZ", null, 0, 30)).isInstanceOf(BusinessException.class);
    }

    @Test
    void paginaElResumen() throws Exception {
        for (long i = 1; i <= 5; i++) {
            kit.order(i);
            kit.item(i * 100, i, 10, 4);
        }
        DayPlanOrderSummaryResponse.Page p = service.listOrders("ALL", null, 1, 2);
        assertThat(p.getContent()).hasSize(2);
        assertThat(p.getTotalElements()).isEqualTo(5);
        assertThat(p.getTotalPages()).isEqualTo(3);
        assertThat(p.isLast()).isFalse();
    }

    @Test
    void itemsDeUnaOpPaginadosYSoloConRestante() throws Exception {
        kit.order(1);
        for (long i = 1; i <= 5; i++) {
            kit.item(100 + i, 1, 10, 10);
        }
        kit.assigned.put(101L, 10); // cubierto: no sale

        DayPlanOrderItemsResponse first = service.listItems(1L, false, 0, 2);
        assertThat(first.getTotalElements()).isEqualTo(4);
        assertThat(first.getContent()).extracting("productionOrderItemId").containsExactly(102L, 103L);
        assertThat(first.getContent().get(0).getRemainingHours()).isEqualTo(5.0);
        assertThat(first.isLast()).isFalse();

        DayPlanOrderItemsResponse last = service.listItems(1L, false, 1, 2);
        assertThat(last.getContent()).extracting("productionOrderItemId").containsExactly(104L, 105L);
        assertThat(last.isLast()).isTrue();

        assertThat(service.listItems(1L, true, 0, 50).getTotalElements()).isEqualTo(5);
    }
}
