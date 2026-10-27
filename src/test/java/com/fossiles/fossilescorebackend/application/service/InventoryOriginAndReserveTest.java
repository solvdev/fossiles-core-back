package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.WarehouseUnitReceiptRequest;
import com.fossiles.fossilescorebackend.application.dto.response.WarehouseWorkspaceResponse;
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
import static org.mockito.Mockito.when;

/**
 * De qué ubicación entra y sale cada pieza: las OPV/OPC de Luis Felipe viven en la reserva hasta cerrar
 * la recepción; lo hecho a pedido sale de Bodega PT; el stock libre y los cambios, de Devoluciones.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class InventoryOriginAndReserveTest {

    @Autowired private ProductionOrderWarehouseUnitService warehouseUnitService;
    @Autowired private ProductDistributionService productDistributionService;
    @Autowired private CustomerShipmentDispatchService customerShipmentDispatchService;
    @Autowired private OnlineSaleProductionOrderService onlineSaleProductionOrderService;
    @Autowired private ProductInventoryService productInventoryService;
    @Autowired private ProductionOrderRepository productionOrderRepository;
    @Autowired private ProductionOrderItemRepository productionOrderItemRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ColorRepository colorRepository;
    @Autowired private InventoryLocationTypeRepository inventoryLocationTypeRepository;
    @Autowired private LocationRepository locationRepository;
    @Autowired private ProductInventoryLocationRepository inventoryLocationRepository;
    @Autowired private ProductInventoryKardexRepository kardexRepository;
    @Autowired private ProductShipmentRepository shipmentRepository;
    @Autowired private ProductShipmentDetailRepository shipmentDetailRepository;
    @Autowired private OnlineSaleRepository onlineSaleRepository;
    @Autowired private OnlineSaleItemRepository onlineSaleItemRepository;

    @MockBean private SecurityUtil securityUtil;

    private LocationEntity bodegaPt;
    private LocationEntity devoluciones;
    private LocationEntity kiosko;
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
        kiosko = locationRepository.save(LocationEntity.builder()
                .code("KIOSKO-01").name("Kiosko Cayala").categoria("KIOSKO").build());
        color = colorRepository.save(ColorEntity.builder().name("NEGRO").build());
        product = productRepository.save(ProductEntity.builder()
                .code("INV-ORG-01").name("Producto origen").requiresMaterials(false).build());
    }

    // ===== Reserva de Luis Felipe =====

    @Test
    void opvLuisFelipe_seRecibeEnReserva_yElCierreLaLibera() throws Exception {
        ProductionOrderEntity po = saveOrder("OPV-RES-01", "NORMAL", "LUIS FELIPE");
        saveItem(po, 3);
        receiveAll(po);

        LocationEntity reserve = productInventoryService.getOrCreateReserveLocation();
        assertThat(stockAt(reserve)).isEqualByComparingTo("3");
        assertThat(stockAt(bodegaPt)).isEqualByComparingTo("0");
        assertThat(stockAt(devoluciones)).isEqualByComparingTo("0");

        Map<String, Object> closed = warehouseUnitService.closeWarehouseReceipt(po.getId());

        assertThat(closed.get("releasedFromReserve")).isEqualTo(3);
        assertThat(stockAt(reserve)).isEqualByComparingTo("0");
        assertThat(stockAt(bodegaPt)).isEqualByComparingTo("0");
        List<ProductInventoryKardex> releases = kardexRepository.findAll().stream()
                .filter(k -> ProductInventoryService.MOVEMENT_RESERVE_RELEASE.equals(k.getMovementType()))
                .toList();
        assertThat(releases).hasSize(3);
        assertThat(releases).allMatch(k -> k.getLocationId().equals(reserve.getId())
                && k.getQuantity().compareTo(BigDecimal.ONE.negate()) == 0);
    }

    @Test
    void cerrarDosVeces_noDescuentaDobleLaReserva() throws Exception {
        ProductionOrderEntity po = saveOrder("OPV-RES-02", "NORMAL", "LUIS FELIPE");
        saveItem(po, 2);
        receiveAll(po);

        warehouseUnitService.closeWarehouseReceipt(po.getId());
        Map<String, Object> second = warehouseUnitService.closeWarehouseReceipt(po.getId());

        assertThat(second.get("releasedFromReserve")).isEqualTo(0);
        assertThat(stockAt(productInventoryService.getOrCreateReserveLocation())).isEqualByComparingTo("0");
        assertThat(stockAt(bodegaPt)).isEqualByComparingTo("0");
    }

    @Test
    void opcLuisFelipe_tambienVaAReserva() throws Exception {
        ProductionOrderEntity po = saveOrder("OPC-RES-01", "CINCHOS_FOSSILES", "LUIS FELIPE");
        saveItem(po, 2);
        receiveAll(po);

        assertThat(stockAt(productInventoryService.getOrCreateReserveLocation())).isEqualByComparingTo("2");
        assertThat(stockAt(bodegaPt)).isEqualByComparingTo("0");
    }

    @Test
    void opvDeOtroVendedor_sigueEntrandoABodegaPt() throws Exception {
        ProductionOrderEntity po = saveOrder("OPV-RES-03", "NORMAL", "MADELYN");
        saveItem(po, 2);
        receiveAll(po);

        assertThat(stockAt(bodegaPt)).isEqualByComparingTo("2");
        assertThat(stockAt(productInventoryService.getOrCreateReserveLocation())).isEqualByComparingTo("0");
    }

    @Test
    void piezaRecibidaEnPtAntesDeLaReserva_sale_dePtAlCerrar() throws Exception {
        ProductionOrderEntity po = saveOrder("OPV-RES-04", "NORMAL", "LUIS FELIPE");
        saveItem(po, 1);
        // Recepción previa al cambio: la pieza quedó en PT.
        seed(bodegaPt, 1);
        WarehouseWorkspaceResponse workspace = warehouseUnitService.getWorkspace(po.getId());
        ProductionOrderWarehouseUnitEntity unit = unitOf(workspace);
        unit.setReceiptStatus(ProductionOrderWarehouseUnitService.STATUS_RECEIVED);
        productionOrderRepository.flush();

        Map<String, Object> closed = warehouseUnitService.closeWarehouseReceipt(po.getId());

        assertThat(closed.get("releasedFromReserve")).isEqualTo(1);
        assertThat(stockAt(bodegaPt)).isEqualByComparingTo("0");
    }

    @Test
    void envioDeOpcLuisFelipe_noVuelveAMoverInventario() throws Exception {
        seed(bodegaPt, 5);
        ProductionOrderEntity po = saveOrder("OPC-RES-02", "CINCHOS_FOSSILES", "LUIS FELIPE");
        ProductShipmentEntity shipment = shipmentRepository.save(ProductShipmentEntity.builder()
                .shipmentNumber("ENVP-TEST-ENV-00001")
                .status("CONFIRMED")
                .productionOrderId(po.getId())
                .locationId(null)
                .notes("DESTINO: Cliente LF")
                .build());
        saveDetail(shipment, 2);

        productDistributionService.sendShipment(shipment.getId());

        assertThat(shipmentRepository.findById(shipment.getId()).orElseThrow().getStatus()).isEqualTo("SENT");
        assertThat(stockAt(bodegaPt)).isEqualByComparingTo("5");
        assertThat(kardexRepository.findByReferenceTypeAndReferenceId("SHIPMENT", shipment.getId())).isEmpty();
    }

    // ===== Origen de la salida =====

    @Test
    void envioDeOpCliente_kiosko_salePrimeroDePt() throws Exception {
        seed(devoluciones, 3);
        seed(bodegaPt, 3);
        ProductionOrderEntity po = saveOrder("OPCK-ORG-01", "CLIENTE_KIOSKO", null);
        ProductShipmentEntity shipment = shipmentRepository.save(ProductShipmentEntity.builder()
                .shipmentNumber("SHP-OPCK-1")
                .status("CONFIRMED")
                .productionOrderId(po.getId())
                .locationId(kiosko.getId())
                .build());
        saveDetail(shipment, 2);

        productDistributionService.sendShipment(shipment.getId());

        assertThat(stockAt(bodegaPt)).isEqualByComparingTo("1");
        assertThat(stockAt(devoluciones)).isEqualByComparingTo("3");
    }

    @Test
    void envioDeOpCliente_siPtNoAlcanza_completaConDevoluciones() throws Exception {
        seed(devoluciones, 3);
        seed(bodegaPt, 1);
        ProductionOrderEntity po = saveOrder("OPCK-ORG-02", "CLIENTE_KIOSKO", null);
        ProductShipmentEntity shipment = shipmentRepository.save(ProductShipmentEntity.builder()
                .shipmentNumber("SHP-OPCK-2")
                .status("CONFIRMED")
                .productionOrderId(po.getId())
                .locationId(kiosko.getId())
                .build());
        saveDetail(shipment, 3);

        productDistributionService.sendShipment(shipment.getId());

        assertThat(stockAt(bodegaPt)).isEqualByComparingTo("0");
        assertThat(stockAt(devoluciones)).isEqualByComparingTo("1");
    }

    @Test
    void envioSinOrdenDeProduccion_stockLibre_salePrimeroDeDevoluciones() throws Exception {
        seed(devoluciones, 3);
        seed(bodegaPt, 3);
        ProductShipmentEntity shipment = shipmentRepository.save(ProductShipmentEntity.builder()
                .shipmentNumber("SHP-LIBRE-1")
                .status("CONFIRMED")
                .locationId(kiosko.getId())
                .build());
        saveDetail(shipment, 2);

        productDistributionService.sendShipment(shipment.getId());

        assertThat(stockAt(devoluciones)).isEqualByComparingTo("1");
        assertThat(stockAt(bodegaPt)).isEqualByComparingTo("3");
    }

    @Test
    void despachoDeOpl_saleDePt_auncuandoDevolucionesTengaLoMismo() throws Exception {
        seed(devoluciones, 1);
        OnlineSaleEntity sale = saveOnlineSale("V-ORG-OPL", "PRODUCIDO", true, null);
        ProductionOrderEntity po = productionOrderRepository.save(ProductionOrderEntity.builder()
                .code("OPL-ORG-01").orderType("VENTA_EN_LINEA").status("IN_PROGRESS").startDate(LocalDate.now()).build());
        productionOrderItemRepository.save(ProductionOrderItemEntity.builder()
                .productionOrderId(po.getId()).productId(product.getId()).colorId(color.getId())
                .onlineSaleId(sale.getId()).quantity(1).warehouseReceivedQty(0).build());
        receiveAll(po);
        assertThat(stockAt(bodegaPt)).isEqualByComparingTo("1");

        customerShipmentDispatchService.dispatchCustomerShipment(po.getId(), sale.getId(), Map.of());

        assertThat(stockAt(bodegaPt)).isEqualByComparingTo("0");
        assertThat(stockAt(devoluciones)).isEqualByComparingTo("1");
    }

    @Test
    void ventaNormalDesdeStock_salePrimeroDePt() throws Exception {
        seed(devoluciones, 2);
        seed(bodegaPt, 2);
        OnlineSaleEntity sale = saveOnlineSale("V-ORG-NORMAL", "PENDIENTE", false, null);
        saveSaleItem(sale, 1);

        onlineSaleProductionOrderService.prepareDirectSaleFromInventory(sale.getId());

        assertThat(stockAt(bodegaPt)).isEqualByComparingTo("1");
        assertThat(stockAt(devoluciones)).isEqualByComparingTo("2");
    }

    @Test
    void cambioDeVentaEnLinea_salePrimeroDeDevoluciones() throws Exception {
        seed(devoluciones, 2);
        seed(bodegaPt, 2);
        OnlineSaleEntity sale = saveOnlineSale("V-ORG-CAMBIO", "PENDIENTE", false,
                "CAMBIO_Q0(ENVIO_REL=ENVL-00001)");
        saveSaleItem(sale, 1);
        assertThat(OnlineSaleService.isExchangeSale(sale)).isTrue();

        onlineSaleProductionOrderService.prepareDirectSaleFromInventory(sale.getId());

        assertThat(stockAt(devoluciones)).isEqualByComparingTo("1");
        assertThat(stockAt(bodegaPt)).isEqualByComparingTo("2");
    }

    // ===== helpers =====

    private ProductionOrderEntity saveOrder(String code, String type, String seller) {
        return productionOrderRepository.save(ProductionOrderEntity.builder()
                .code(code)
                .orderType(type)
                .sellerName(seller)
                .status("IN_PROGRESS")
                .startDate(LocalDate.now())
                .build());
    }

    private ProductionOrderItemEntity saveItem(ProductionOrderEntity po, int qty) {
        return productionOrderItemRepository.save(ProductionOrderItemEntity.builder()
                .productionOrderId(po.getId())
                .productId(product.getId())
                .colorId(color.getId())
                .quantity(qty)
                .warehouseReceivedQty(0)
                .build());
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

    @Autowired private ProductionOrderWarehouseUnitRepository unitRepository;

    private ProductionOrderWarehouseUnitEntity unitOf(WarehouseWorkspaceResponse workspace) {
        return unitRepository.findById(workspace.getUnits().get(0).getId()).orElseThrow();
    }

    private void seed(LocationEntity location, int qty) {
        inventoryLocationRepository.save(ProductInventoryLocation.builder()
                .productId(product.getId())
                .locationId(location.getId())
                .colorId(color.getId())
                .quantity(BigDecimal.valueOf(qty))
                .build());
    }

    private ProductShipmentDetailEntity saveDetail(ProductShipmentEntity shipment, int qty) {
        return shipmentDetailRepository.save(ProductShipmentDetailEntity.builder()
                .shipmentId(shipment.getId())
                .productId(product.getId())
                .colorId(color.getId())
                .quantity(BigDecimal.valueOf(qty))
                .build());
    }

    private OnlineSaleEntity saveOnlineSale(String number, String status, boolean inProduction, String observations) {
        return onlineSaleRepository.save(OnlineSaleEntity.builder()
                .saleNumber(number)
                .customerName("Cliente " + number)
                .status(status)
                .paymentMethod("TARJETA_PAGADO")
                .saleDate(LocalDate.now())
                .inProductionOrder(inProduction)
                .observations(observations)
                .build());
    }

    private void saveSaleItem(OnlineSaleEntity sale, int qty) {
        onlineSaleItemRepository.save(OnlineSaleItemEntity.builder()
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

    private BigDecimal stockAt(LocationEntity location) {
        return inventoryLocationRepository
                .findByProductIdAndLocationIdAndColorId(product.getId(), location.getId(), color.getId())
                .map(ProductInventoryLocation::getQuantity)
                .orElse(BigDecimal.ZERO);
    }
}
