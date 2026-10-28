package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.OnlineSaleExchangeRequest;
import com.fossiles.fossilescorebackend.application.dto.request.WarehouseUnitReceiptRequest;
import com.fossiles.fossilescorebackend.application.dto.response.WarehouseWorkspaceResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.*;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Toda devolución o anulación de una venta en línea deja la pieza en Devoluciones, una sola vez, y
 * cuando la pieza vuelve a salir se descuenta de donde quedó.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OnlineSaleReturnsToDevolucionesTest {

    @Autowired private OnlineSaleService onlineSaleService;
    @Autowired private OnlineSaleProductionOrderService onlineSaleProductionOrderService;
    @Autowired private CustomerShipmentDispatchService customerShipmentDispatchService;
    @Autowired private ProductionOrderWarehouseUnitService warehouseUnitService;
    @Autowired private ProductionOrderRepository productionOrderRepository;
    @Autowired private ProductionOrderItemRepository productionOrderItemRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ColorRepository colorRepository;
    @Autowired private InventoryLocationTypeRepository inventoryLocationTypeRepository;
    @Autowired private LocationRepository locationRepository;
    @Autowired private ProductInventoryLocationRepository inventoryLocationRepository;
    @Autowired private OnlineSaleRepository onlineSaleRepository;
    @Autowired private OnlineSaleItemRepository onlineSaleItemRepository;

    @MockBean private SecurityUtil securityUtil;

    private LocationEntity bodegaPt;
    private LocationEntity devoluciones;
    private ProductEntity product;
    private ColorEntity color;

    @BeforeEach
    void setUp() {
        when(securityUtil.getCurrentUserId()).thenReturn(7L);
        inventoryLocationTypeRepository.save(InventoryLocationTypeEntity.builder()
                .code("BODEGA_PT").name("Bodega PT").isActive(true).build());
        inventoryLocationTypeRepository.save(InventoryLocationTypeEntity.builder()
                .code("BODEGA_DEV").name("Bodega Devoluciones").isActive(true).build());
        bodegaPt = locationRepository.save(LocationEntity.builder()
                .code("BODEGA_PT").name("Bodega Producto Terminado").categoria("BODEGA_PT").build());
        devoluciones = locationRepository.save(LocationEntity.builder()
                .code("BODEGA_DEV").name("Bodega Devoluciones").categoria("BODEGA_DEV").build());
        color = colorRepository.save(ColorEntity.builder().name("NEGRO").build());
        product = productRepository.save(ProductEntity.builder()
                .code("DEV-RET-01").name("Producto devolución").requiresMaterials(false).build());
    }

    @Test
    void devolucion_ingresaADevoluciones() throws Exception {
        OnlineSaleEntity sale = saveSale("ENVIADO", false);
        saveSaleItem(sale, 2);

        onlineSaleService.markAsReturn(sale.getId(), "No le quedó", "BUENO");

        assertThat(stockAt(devoluciones)).isEqualByComparingTo("2");
    }

    @Test
    void devolucionDeVentaConCambioPrevio_noAcreditaDosVecesLoYaCambiado() throws Exception {
        OnlineSaleEntity sale = saveSale("ENVIADO", false);
        OnlineSaleItemEntity item = saveSaleItem(sale, 2);
        OnlineSaleExchangeRequest exchange = new OnlineSaleExchangeRequest();
        OnlineSaleExchangeRequest.ExchangeItemRequest line = new OnlineSaleExchangeRequest.ExchangeItemRequest();
        line.setProductId(item.getProductId());
        line.setColorId(item.getColorId());
        line.setQuantity(1);
        exchange.setItems(List.of(line));
        onlineSaleService.createExchange(sale.getId(), exchange);
        assertThat(stockAt(devoluciones)).isEqualByComparingTo("1");

        onlineSaleService.markAsReturn(sale.getId(), "Devolvió el resto", "BUENO");

        assertThat(stockAt(devoluciones)).isEqualByComparingTo("2");
    }

    @Test
    void anularVentaPreparada_regresaADevoluciones() throws Exception {
        seed(bodegaPt, 1);
        OnlineSaleEntity sale = saveSale("PENDIENTE", false);
        saveSaleItem(sale, 1);
        onlineSaleProductionOrderService.prepareDirectSaleFromInventory(sale.getId());
        assertThat(stockAt(bodegaPt)).isEqualByComparingTo("0");

        onlineSaleService.markAsVoid(sale.getId(), "Cliente canceló");

        assertThat(stockAt(devoluciones)).isEqualByComparingTo("1");
        assertThat(stockAt(bodegaPt)).isEqualByComparingTo("0");
    }

    @Test
    void anularVentaConPiezaDeOplYaEnPt_laPasaADevoluciones() throws Exception {
        OnlineSaleEntity sale = saveSale("PRODUCIDO", true);
        ProductionOrderEntity po = saveOplFor(sale);
        receiveAll(po);
        assertThat(stockAt(bodegaPt)).isEqualByComparingTo("1");

        onlineSaleService.markAsVoid(sale.getId(), "Cambió de estilo");
        // Anular dos veces no es posible, pero repetir el traslado no debe mover de más.
        warehouseUnitService.moveReceivedUnitsToReturns(sale.getId(), "X", "repetido");

        assertThat(stockAt(bodegaPt)).isEqualByComparingTo("0");
        assertThat(stockAt(devoluciones)).isEqualByComparingTo("1");
    }

    @Test
    void piezaDeOplRecibidaDespuesDeAnularLaVenta_entraADevoluciones() throws Exception {
        OnlineSaleEntity sale = saveSale("EN_PRODUCCION", true);
        ProductionOrderEntity po = saveOplFor(sale);
        onlineSaleService.markAsVoid(sale.getId(), "Cambió de estilo");

        receiveAll(po);

        assertThat(stockAt(bodegaPt)).isEqualByComparingTo("0");
        assertThat(stockAt(devoluciones)).isEqualByComparingTo("1");
    }

    @Test
    void anularEnvioDeVentaDirecta_yDespacharDeNuevo_descuentaDeDevoluciones() throws Exception {
        seed(bodegaPt, 2);
        OnlineSaleEntity sale = saveSale("PENDIENTE", false);
        saveSaleItem(sale, 1);
        onlineSaleProductionOrderService.prepareDirectSaleFromInventory(sale.getId());
        customerShipmentDispatchService.dispatchDirectOnlineSale(sale.getId(), Map.of());
        assertThat(stockAt(bodegaPt)).isEqualByComparingTo("1");

        onlineSaleService.cancelDispatch(sale.getId(), "Guía equivocada");
        assertThat(stockAt(devoluciones)).isEqualByComparingTo("1");

        customerShipmentDispatchService.dispatchDirectOnlineSale(sale.getId(), Map.of());

        assertThat(stockAt(devoluciones)).isEqualByComparingTo("0");
        assertThat(stockAt(bodegaPt)).isEqualByComparingTo("1");
    }

    @Test
    void anularEnvioDeOpl_yDespacharDeNuevo_descuentaDeDevoluciones() throws Exception {
        seed(bodegaPt, 1);
        OnlineSaleEntity sale = saveSale("PRODUCIDO", true);
        ProductionOrderEntity po = saveOplFor(sale);
        receiveAll(po);
        customerShipmentDispatchService.dispatchCustomerShipment(po.getId(), sale.getId(), Map.of());
        assertThat(stockAt(bodegaPt)).isEqualByComparingTo("1");

        onlineSaleService.cancelDispatch(sale.getId(), "Dirección equivocada");
        assertThat(stockAt(devoluciones)).isEqualByComparingTo("1");

        customerShipmentDispatchService.dispatchCustomerShipment(po.getId(), sale.getId(), Map.of());

        assertThat(stockAt(devoluciones)).isEqualByComparingTo("0");
        assertThat(stockAt(bodegaPt)).isEqualByComparingTo("1");
    }

    @Test
    void eliminarVentaQueYaMovioInventario_seRechaza() throws Exception {
        seed(bodegaPt, 1);
        OnlineSaleEntity sale = saveSale("PENDIENTE", false);
        saveSaleItem(sale, 1);
        onlineSaleProductionOrderService.prepareDirectSaleFromInventory(sale.getId());

        assertThatThrownBy(() -> onlineSaleService.delete(sale.getId()))
                .isInstanceOf(BusinessException.class);
        assertThat(onlineSaleRepository.existsById(sale.getId())).isTrue();
    }

    @Test
    void eliminarVentaSinMovimientos_sigueFuncionando() throws Exception {
        OnlineSaleEntity sale = saveSale("PENDIENTE", false);
        saveSaleItem(sale, 1);

        onlineSaleService.delete(sale.getId());

        assertThat(onlineSaleRepository.existsById(sale.getId())).isFalse();
    }

    // ===== helpers =====

    private OnlineSaleEntity saveSale(String status, boolean inProduction) {
        return onlineSaleRepository.save(OnlineSaleEntity.builder()
                .saleNumber("V-DEV-" + status)
                .customerName("Cliente devolución")
                .status(status)
                .paymentMethod("TARJETA_PAGADO")
                .saleDate(LocalDate.now())
                .inProductionOrder(inProduction)
                .build());
    }

    private OnlineSaleItemEntity saveSaleItem(OnlineSaleEntity sale, int qty) {
        return onlineSaleItemRepository.save(OnlineSaleItemEntity.builder()
                .onlineSaleId(sale.getId())
                .productId(product.getId())
                .productCode(product.getCode())
                .productName(product.getName())
                .colorId(color.getId())
                .colorName(color.getName())
                .quantity(qty)
                .unitPrice(BigDecimal.TEN)
                .build());
    }

    private ProductionOrderEntity saveOplFor(OnlineSaleEntity sale) {
        OnlineSaleItemEntity saleItem = saveSaleItem(sale, 1);
        ProductionOrderEntity po = productionOrderRepository.save(ProductionOrderEntity.builder()
                .code("OPL-DEV-" + sale.getId()).orderType("VENTA_EN_LINEA").status("IN_PROGRESS")
                .startDate(LocalDate.now()).build());
        productionOrderItemRepository.save(ProductionOrderItemEntity.builder()
                .productionOrderId(po.getId()).productId(product.getId()).colorId(color.getId())
                .onlineSaleId(sale.getId()).onlineSaleItemId(saleItem.getId())
                .quantity(1).warehouseReceivedQty(0).build());
        return po;
    }

    private void receiveAll(ProductionOrderEntity po) throws Exception {
        WarehouseWorkspaceResponse workspace = warehouseUnitService.getWorkspace(po.getId());
        warehouseUnitService.updateUnitsReceipt(po.getId(), WarehouseUnitReceiptRequest.builder()
                .units(workspace.getUnits().stream()
                        .map(u -> WarehouseUnitReceiptRequest.UnitUpdate.builder()
                                .unitId(u.getId()).receiptStatus("RECEIVED").build())
                        .collect(Collectors.toList()))
                .build());
    }

    private void seed(LocationEntity location, int qty) {
        inventoryLocationRepository.save(ProductInventoryLocation.builder()
                .productId(product.getId())
                .locationId(location.getId())
                .colorId(color.getId())
                .quantity(BigDecimal.valueOf(qty))
                .build());
    }

    private BigDecimal stockAt(LocationEntity location) {
        return inventoryLocationRepository
                .findByProductIdAndLocationIdAndColorId(product.getId(), location.getId(), color.getId())
                .map(ProductInventoryLocation::getQuantity)
                .orElse(BigDecimal.ZERO);
    }
}
