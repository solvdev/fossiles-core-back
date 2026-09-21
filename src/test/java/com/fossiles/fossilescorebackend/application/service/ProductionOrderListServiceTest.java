package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.PageResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ProductionOrderListItemResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Listado paginado de Órdenes de Producción, contra la copia local.
 *
 * <p><b>{@code @ActiveProfiles("local")} no es decorativo.</b> El
 * {@code application.properties} por defecto apunta a una base remota en RDS; sin fijar el
 * perfil, esta clase se levantaría contra ella. Todo el trabajo va contra la copia local en
 * el puerto 5433, y estas pruebas solo leen: no escriben ninguna fila.
 *
 * <p>Las aserciones son sobre invariantes (orden, estabilidad del total, no solaparse), no
 * sobre cifras concretas, para que no se rompan cuando cambien los datos de la copia.
 */
@SpringBootTest
@ActiveProfiles("local")
class ProductionOrderListServiceTest {

    @Autowired
    private ProductionOrderListService service;

    private PageResponse<ProductionOrderListItemResponse> page(int page, int size) throws BusinessException {
        return service.list("ALL", "ALL", "ALL", "", null, null, page, size);
    }

    @Test
    @DisplayName("ordena de la más nueva a la más vieja por fecha de creación")
    void ordenDescendentePorCreacion() throws BusinessException {
        List<ProductionOrderListItemResponse> content = page(0, 50).getContent();
        assertFalse(content.isEmpty(), "la copia local debería tener órdenes");

        LocalDateTime previa = null;
        for (ProductionOrderListItemResponse row : content) {
            assertNotNull(row.getCreatedAt(), "createdAt no debería venir nulo: " + row.getCode());
            if (previa != null) {
                assertFalse(row.getCreatedAt().isAfter(previa),
                        "la fila " + row.getCode() + " es más nueva que la anterior");
            }
            previa = row.getCreatedAt();
        }
    }

    @Test
    @DisplayName("totalElements no cambia al pedir otra página")
    void totalEstableEntrePaginas() throws BusinessException {
        long total0 = page(0, 10).getTotalElements();
        long total3 = page(3, 10).getTotalElements();
        assertEquals(total0, total3, "el total no puede depender de qué página se pida");
    }

    @Test
    @DisplayName("dos páginas seguidas no repiten órdenes")
    void paginasSinSolape() throws BusinessException {
        List<ProductionOrderListItemResponse> p0 = page(0, 10).getContent();
        List<ProductionOrderListItemResponse> p1 = page(1, 10).getContent();

        Set<Long> ids = new HashSet<>();
        p0.forEach(r -> ids.add(r.getId()));
        for (ProductionOrderListItemResponse row : p1) {
            assertTrue(ids.add(row.getId()),
                    "la orden " + row.getCode() + " sale en la página 0 y en la 1");
        }
    }

    @Test
    @DisplayName("first y last describen la posición real")
    void banderasDePagina() throws BusinessException {
        PageResponse<ProductionOrderListItemResponse> primera = page(0, 10);
        assertTrue(primera.isFirst());
        assertTrue(primera.getTotalPages() > 1, "se esperaban varias páginas en la copia local");
        assertFalse(primera.isLast());

        PageResponse<ProductionOrderListItemResponse> ultima = page(primera.getTotalPages() - 1, 10);
        assertTrue(ultima.isLast());
    }

    @Test
    @DisplayName("el filtro por familia solo devuelve esa familia")
    void filtroPorFamilia() throws BusinessException {
        for (String familia : List.of("OPL", "OPK", "OPCK", "OPC")) {
            PageResponse<ProductionOrderListItemResponse> p =
                    service.list(familia, "ALL", "ALL", "", null, null, 0, 50);
            for (ProductionOrderListItemResponse row : p.getContent()) {
                assertEquals(familia, row.getFamily(),
                        "la orden " + row.getCode() + " no es de la familia " + familia);
            }
        }
    }

    /**
     * La razón de ser de esta prueba: en los ítems de cincho {@code quantity} viene nula y la
     * cantidad vive dentro del JSON de {@code sizes_data}. Si la consulta sumara solo la
     * columna, estas órdenes saldrían con total 0 y el filtro de proceso las clasificaría mal.
     */
    @Test
    @DisplayName("las órdenes de cincho suman cantidad desde las tallas, no desde quantity")
    void cinchosSumanDesdeLasTallas() throws BusinessException {
        List<ProductionOrderListItemResponse> cinchos =
                service.list("OPC", "ALL", "ALL", "", null, null, 0, 50).getContent();
        assertFalse(cinchos.isEmpty(), "la copia local debería tener órdenes de cincho");

        boolean algunaConCantidad = cinchos.stream()
                .anyMatch(r -> r.getItemCount() > 0 && r.getTotalQty() > 0);
        assertTrue(algunaConCantidad,
                "ninguna orden de cincho con ítems reporta cantidad: se está leyendo quantity "
                        + "en vez de sumar las tallas del JSON");
    }

    @Test
    @DisplayName("el rango de fechas respeta ambos extremos, incluido el último día completo")
    void rangoDeFechas() throws BusinessException {
        List<ProductionOrderListItemResponse> todas = page(0, 1).getContent();
        assertFalse(todas.isEmpty());
        LocalDate dia = todas.get(0).getCreatedAt().toLocalDate();

        PageResponse<ProductionOrderListItemResponse> p =
                service.list("ALL", "ALL", "ALL", "", dia, dia, 0, 200);
        assertTrue(p.getTotalElements() > 0,
                "filtrar por el día de la orden más nueva no debería devolver nada");
        for (ProductionOrderListItemResponse row : p.getContent()) {
            assertEquals(dia, row.getCreatedAt().toLocalDate());
        }
    }

    @Test
    @DisplayName("los agregados son coherentes entre sí")
    void agregadosCoherentes() throws BusinessException {
        for (ProductionOrderListItemResponse row : page(0, 50).getContent()) {
            assertAll("agregados de " + row.getCode(),
                    () -> assertTrue(row.getTotalQty() >= 0),
                    () -> assertTrue(row.getReceivedQty() >= 0),
                    () -> assertTrue(row.getReceivedQty() <= row.getTotalQty(),
                            "lo recibido no puede superar lo planificado"),
                    () -> assertEquals(Math.max(row.getTotalQty() - row.getReceivedQty(), 0),
                            row.getPendingQty()),
                    () -> assertTrue(row.getProgressPct() >= 0 && row.getProgressPct() <= 100),
                    () -> assertNotNull(row.getProcessStage()),
                    () -> assertNotNull(row.getProcessStage().getKey()));
        }
    }

    /** Ninguna orden COMPLETED con algo por recibir puede salir como lista para despacho. */
    @Test
    @DisplayName("el filtro de proceso separa bodega de lista para despacho")
    void filtroDeProceso() throws BusinessException {
        for (ProductionOrderListItemResponse row :
                service.list("ALL", "ALL", "BODEGA", "", null, null, 0, 50).getContent()) {
            assertEquals("IN_BODEGA", row.getProcessStage().getKey());
            assertTrue(row.getPendingQty() > 0);
        }
        for (ProductionOrderListItemResponse row :
                service.list("ALL", "ALL", "READY", "", null, null, 0, 50).getContent()) {
            assertEquals("READY_DISPATCH", row.getProcessStage().getKey());
            assertEquals(0, row.getPendingQty());
        }
    }

    @Test
    @DisplayName("un valor desconocido en un filtro es un error, no 'sin filtro'")
    void filtrosInvalidos() {
        assertThrows(BusinessException.class,
                () -> service.list("OPX", "ALL", "ALL", "", null, null, 0, 10));
        assertThrows(BusinessException.class,
                () -> service.list("ALL", "ENTREGADA", "ALL", "", null, null, 0, 10));
        assertThrows(BusinessException.class,
                () -> service.list("ALL", "ALL", "TODO", "", null, null, 0, 10));
        assertThrows(BusinessException.class,
                () -> service.list("ALL", "ALL", "ALL", "", LocalDate.of(2026, 9, 1),
                        LocalDate.of(2026, 8, 1), 0, 10));
    }

    @Test
    @DisplayName("el tamaño de página se recorta a 200")
    void topeDeTamano() throws BusinessException {
        PageResponse<ProductionOrderListItemResponse> p =
                service.list("ALL", "ALL", "ALL", "", null, null, 0, 5000);
        assertEquals(200, p.getSize());
    }
}
