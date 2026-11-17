package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.ProductLedgerLabLocationResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.util.ProductLedgerLabGuard;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductInventoryLocationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * La reserva de Luis Felipe se puede consultar en Inventario Productos, pero no editar: las lecturas aceptan
 * Devoluciones, Bodega PT y la reserva; las operaciones que cambian datos siguen limitadas a las de despacho.
 */
@ExtendWith(MockitoExtension.class)
class ProductLedgerLabReserveVisibilityTest {

    private static final long PT = 1L;
    private static final long DEV = 2L;
    private static final long RESERVE = 3L;

    @Mock private ProductLedgerLabGuard guard;
    @Mock private ProductInventoryService productInventoryService;
    @Mock private ProductInventoryLocationRepository stockRepository;
    @Mock private LocationRepository locationRepository;
    @InjectMocks private ProductLedgerLabService service;

    private static LocationEntity location(long id, String code, String name) {
        return LocationEntity.builder().id(id).code(code).name(name).build();
    }

    private void dispatchWarehouses() throws BusinessException {
        when(productInventoryService.getDispatchSourceWarehouses())
                .thenReturn(List.of(location(DEV, "DEVOLUCIONES", "Devoluciones"), location(PT, "BODEGA_PT", "Bodega PT")));
    }

    private LocationEntity reserve() {
        return location(RESERVE, ProductInventoryService.RESERVE_LOCATION_CODE, "Reserva Luis Felipe (vendido)");
    }

    @Test
    void laReservaApareceEnElListadoDeConsulta() throws Exception {
        dispatchWarehouses();
        when(locationRepository.findByCode(ProductInventoryService.RESERVE_LOCATION_CODE))
                .thenReturn(Optional.of(reserve()));

        List<ProductLedgerLabLocationResponse> out = service.listReadableLocations();

        assertEquals(List.of(DEV, PT, RESERVE), out.stream().map(ProductLedgerLabLocationResponse::getId).toList());
        assertEquals("RESERVA_LF", out.get(2).getCode());
    }

    @Test
    void siLaReservaTodaviaNoExisteElListadoNoCambia() throws Exception {
        dispatchWarehouses();
        when(locationRepository.findByCode(ProductInventoryService.RESERVE_LOCATION_CODE))
                .thenReturn(Optional.empty());

        assertEquals(2, service.listReadableLocations().size());
    }

    @Test
    void elListadoDeEdicionSigueSinLaReserva() throws Exception {
        dispatchWarehouses();

        assertEquals(List.of(DEV, PT),
                service.listAllowedLocations().stream().map(ProductLedgerLabLocationResponse::getId).toList());
    }

    @Test
    void sePuedenLeerLasExistenciasDeLaReserva() throws Exception {
        dispatchWarehouses();
        when(locationRepository.findByCode(ProductInventoryService.RESERVE_LOCATION_CODE))
                .thenReturn(Optional.of(reserve()));
        when(stockRepository.findByLocationId(RESERVE)).thenReturn(List.of());

        assertTrue(service.listStocks(RESERVE, null, null, null, null).isEmpty());
    }

    @Test
    void otraUbicacionNoSePuedeLeer() throws Exception {
        dispatchWarehouses();
        when(locationRepository.findByCode(ProductInventoryService.RESERVE_LOCATION_CODE))
                .thenReturn(Optional.of(reserve()));

        assertThrows(BusinessException.class, () -> service.listStocks(99L, null, null, null, null));
    }

    @Test
    void laReservaNoSePuedeEditar() throws Exception {
        dispatchWarehouses();

        BusinessException ex = assertThrows(BusinessException.class, () -> service.replayAllStocks(RESERVE));
        assertTrue(ex.getMessage().contains("Ubicación no permitida"));
    }
}
