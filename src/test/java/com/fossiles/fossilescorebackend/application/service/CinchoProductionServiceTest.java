package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.CinchoProductionResponse;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionCinchoDayStatusEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionCinchoDayStatusRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductionPlanningConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Lecturas de cinchos sobre H2: qué líneas entran, tallas, estados y resumen. */
@SpringBootTest
@Transactional
class CinchoProductionServiceTest {

    @Autowired CinchoProductionService service;
    @Autowired ProductionOrderRepository orders;
    @Autowired ProductionOrderItemRepository items;
    @Autowired ProductRepository products;
    @Autowired ColorRepository colors;
    @Autowired ProductionCinchoDayStatusRepository statuses;

    private final LocalDate today = GuatemalaDateTime.today();
    /** Un día hábil futuro: el arrastre solo aplica de hoy en adelante y de lunes a viernes. */
    private final LocalDate dia = nextWorkday(today.plusDays(7));

    private static LocalDate nextWorkday(LocalDate d) {
        while (d.getDayOfWeek() == java.time.DayOfWeek.SATURDAY || d.getDayOfWeek() == java.time.DayOfWeek.SUNDAY) {
            d = d.plusDays(1);
        }
        return d;
    }
    private ProductEntity cincho;
    private ProductEntity pulsera;
    private ProductEntity billetera;
    private ColorEntity negro;

    @BeforeEach
    void setUp() {
        cincho = products.save(ProductEntity.builder().code("CIN-T1").name("Cincho trenzado").build());
        pulsera = products.save(ProductEntity.builder().code("PUL-1").name("Pulsera cuero").build());
        billetera = products.save(ProductEntity.builder().code("BIL-1").name("Billetera").build());
        negro = colors.save(ColorEntity.builder().name("Negro").build());
    }

    private ProductionOrderEntity order(String code, String type, String status, LocalDate start, LocalDate delivery) {
        return orders.save(ProductionOrderEntity.builder().code(code).orderType(type).status(status)
                .startDate(start).deliveryDate(delivery).customerName("Cliente " + code).build());
    }

    private ProductionOrderItemEntity item(ProductionOrderEntity o, ProductEntity p, int qty, String sizes) {
        return items.save(ProductionOrderItemEntity.builder().productionOrderId(o.getId()).productId(p.getId())
                .colorId(negro.getId()).quantity(qty).sizesData(sizes).build());
    }

    @Test
    @DisplayName("el día trae las líneas de cincho y pulsera; de la OPL solo esas y marca que es mixta")
    void cuadroDelDia() throws Exception {
        ProductionOrderEntity opcf = order("OPCF-1", "CINCHOS_FOSSILES", "PENDING", dia, dia.plusDays(3));
        item(opcf, cincho, 0, "{\"40\":2,\"32\":3,\"XL\":1,\"S\":0}");
        ProductionOrderEntity opl = order("OPL-1", "VENTA_EN_LINEA", "IN_PROGRESS", dia, dia);
        ProductionOrderItemEntity linea = item(opl, pulsera, 4, null);
        item(opl, billetera, 1, null);
        order("OPL-2", "VENTA_EN_LINEA", "IN_PROGRESS", dia, dia); // sin cinchos: no sale
        statuses.save(ProductionCinchoDayStatusEntity.builder().workDate(dia).productionOrderId(opl.getId())
                .productionOrderItemId(linea.getId()).delivered(false).workStatus("IN_PROGRESS").build());

        CinchoProductionResponse.Day day = service.day(dia);

        assertThat(day.getOrders()).extracting(CinchoProductionResponse.Order::getCode).containsExactly("OPCF-1", "OPL-1");
        CinchoProductionResponse.Order f = day.getOrders().get(0);
        assertThat(f.getFamily()).isEqualTo("OPCF");
        assertThat(f.getLines().get(0).getSizes()).containsExactly(Map.entry("32", 3), Map.entry("40", 2), Map.entry("XL", 1));
        assertThat(f.getTotalQty()).isEqualTo(6);
        CinchoProductionResponse.Order l = day.getOrders().get(1);
        assertThat(l.isMixedOrder()).isTrue();
        assertThat(l.getLines()).hasSize(1);
        assertThat(l.getLines().get(0).getWorkStatus()).isEqualTo("IN_PROGRESS");
        assertThat(l.getLines().get(0).getColorName()).isEqualTo("Negro");
    }

    @Test
    @DisplayName("lo que quedó sin terminar del día hábil anterior sigue saliendo, con su último estado")
    void arrastreDelDiaAnterior() throws Exception {
        LocalDate anterior = ProductionPlanningConstants.previousWorkday(dia);
        ProductionOrderEntity vieja = order("OPCF-5", "CINCHOS_FOSSILES", "IN_PROGRESS", anterior, dia.plusDays(2));
        ProductionOrderItemEntity enMesa = item(vieja, cincho, 4, null);
        ProductionOrderItemEntity terminada = item(vieja, cincho, 6, null);
        item(vieja, cincho, 2, null); // nunca se marcó: sale pendiente
        statuses.save(ProductionCinchoDayStatusEntity.builder().workDate(dia.minusDays(3)).productionOrderId(vieja.getId())
                .productionOrderItemId(enMesa.getId()).delivered(false).workStatus("IN_PROGRESS").build());
        statuses.save(ProductionCinchoDayStatusEntity.builder().workDate(dia.minusDays(5)).productionOrderId(vieja.getId())
                .productionOrderItemId(terminada.getId()).delivered(true).workStatus("COMPLETED").build());
        // Del día anterior, una ya toda terminada no se arrastra; una cerrada tampoco.
        ProductionOrderEntity lista = order("OPCF-6", "CINCHOS_FOSSILES", "IN_PROGRESS", anterior, dia);
        ProductionOrderItemEntity l6 = item(lista, cincho, 1, null);
        statuses.save(ProductionCinchoDayStatusEntity.builder().workDate(dia.minusDays(4)).productionOrderId(lista.getId())
                .productionOrderItemId(l6.getId()).delivered(true).workStatus("COMPLETED").build());
        item(order("OPCF-7", "CINCHOS_FOSSILES", "COMPLETED", anterior, dia), cincho, 1, null);
        // Ni lo de más atrás, aunque esté sin terminar: eso confunde.
        item(order("OPCF-8", "CINCHOS_FOSSILES", "IN_PROGRESS", ProductionPlanningConstants.previousWorkday(anterior), dia),
                cincho, 3, null);

        CinchoProductionResponse.Day day = service.day(dia);

        assertThat(day.getOrders()).extracting(CinchoProductionResponse.Order::getCode).containsExactly("OPCF-5");
        CinchoProductionResponse.Order o = day.getOrders().get(0);
        assertThat(o.getCarriedFrom()).isEqualTo(anterior);
        assertThat(o.getLines()).extracting(CinchoProductionResponse.Line::getWorkStatus).containsExactly("IN_PROGRESS", "PENDING");
        assertThat(o.getTotalQty()).isEqualTo(6);

        // Un día pasado o un sábado se ven sin arrastres.
        assertThat(service.day(today.minusDays(1)).getOrders()).isEmpty();
        LocalDate sabado = dia.with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.SATURDAY));
        assertThat(service.day(sabado).getOrders()).isEmpty();
    }

    @Test
    @DisplayName("el listado de activas da avance por línea y el resumen cuenta atrasadas")
    void listadoDeActivas() throws Exception {
        ProductionOrderEntity atrasada = order("OPCF-10", "CINCHOS_FOSSILES", "IN_PROGRESS", today.minusDays(9), today.minusDays(2));
        ProductionOrderItemEntity a = item(atrasada, cincho, 10, null);
        item(atrasada, cincho, 5, null);
        item(order("OPCF-11", "CINCHOS_FOSSILES", "COMPLETED", today.minusDays(9), today.minusDays(2)), cincho, 3, null);
        ProductionOrderEntity pronto = order("OPCF-12", "CINCHOS_FOSSILES", "PENDING", today, today.plusDays(3));
        item(pronto, cincho, 7, null);
        // Entregada otro día: cuenta como hecha aunque después nadie la marcara.
        statuses.save(ProductionCinchoDayStatusEntity.builder().workDate(today.minusDays(5)).productionOrderId(atrasada.getId())
                .productionOrderItemId(a.getId()).delivered(true).workStatus("COMPLETED").build());

        CinchoProductionResponse.Listing list = service.list(null, "OPC", null);

        assertThat(list.getOrders()).extracting(CinchoProductionResponse.Order::getCode).containsExactly("OPCF-10", "OPCF-12");
        assertThat(list.getOrders().get(0).getLinesDone()).isEqualTo(1);
        assertThat(list.getSummary().getLate()).isEqualTo(1);
        assertThat(list.getSummary().getDueSoon()).isEqualTo(1);
        assertThat(list.getSummary().getUnits()).isEqualTo(22);
        assertThat(list.getSummary().getUnitsDone()).isEqualTo(10);

        assertThat(service.list("COMPLETED", "OPC", null).getOrders())
                .extracting(CinchoProductionResponse.Order::getCode).containsExactly("OPCF-11");
        assertThat(service.list(null, null, "cliente opcf-12").getOrders())
                .extracting(CinchoProductionResponse.Order::getCode).containsExactly("OPCF-12");
    }

    @Test
    void ordenDeTallas() {
        assertThat(List.of("XL", "10", "S", "M", "8", "Única").stream().sorted(CinchoProductionService::compareSizes).toList())
                .containsExactly("8", "10", "S", "M", "XL", "Única");
    }
}
