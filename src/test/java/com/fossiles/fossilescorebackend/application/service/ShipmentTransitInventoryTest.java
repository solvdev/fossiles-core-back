package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.ConfirmReceiptRequest;
import com.fossiles.fossilescorebackend.application.dto.request.InventoryTransferRequest;
import com.fossiles.fossilescorebackend.application.dto.request.PendingShipmentReceiptRequest;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.*;
import com.fossiles.fossilescorebackend.infrastructure.util.KioskPosMode;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Envíos a kiosko con ubicación "En tránsito", recepciones parciales y devoluciones de venta en línea.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ShipmentTransitInventoryTest {

    @Autowired private ProductDistributionService productDistributionService;
    @Autowired private ProductInventoryService productInventoryService;
    @Autowired private OnlineSaleService onlineSaleService;
    @Autowired private KioscoInventoryService kioscoInventoryService;
    @Autowired private InventoryService inventoryService;
    @Autowired private ProductShipmentRepository shipmentRepository;
    @Autowired private ProductShipmentDetailRepository shipmentDetailRepository;
    @Autowired private ProductInventoryLocationRepository inventoryLocationRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ColorRepository colorRepository;
    @Autowired private InventoryLocationTypeRepository inventoryLocationTypeRepository;
    @Autowired private LocationRepository locationRepository;
    @Autowired private KioscoStockRepository kioscoStockRepository;
    @Autowired private OnlineSaleRepository onlineSaleRepository;
    @Autowired private OnlineSaleItemRepository onlineSaleItemRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;

    @MockBean private SecurityUtil securityUtil;

    private LocationEntity bodegaPt;
    private LocationEntity kiosko;
    private ColorEntity color;

    @BeforeEach
    void setUp() {
        RoleEntity adminRole = roleRepository.save(RoleEntity.builder().name("ADMIN").build());
        UserEntity admin = userRepository.save(UserEntity.builder()
                .username("admin.transit")
                .email("admin.transit@fossiles.test")
                .password("x")
                .status("ACTIVE")
                .roles(new HashSet<>(Set.of(adminRole)))
                .build());
        when(securityUtil.getCurrentUserId()).thenReturn(admin.getId());

        inventoryLocationTypeRepository.save(InventoryLocationTypeEntity.builder()
                .code("BODEGA_PT").name("Bodega PT").isActive(true).build());
        bodegaPt = locationRepository.save(LocationEntity.builder()
                .code("BODEGA_PT").name("Bodega Producto Terminado").categoria("BODEGA_PT").build());
        kiosko = locationRepository.save(LocationEntity.builder()
                .code("KIOSKO-TR").name("Kiosko Transito").categoria("KIOSKO").build());
        if (kiosko.getId() == KioskPosMode.ENTRECUEROS_LOCATION_ID) {
            // El id 42 es Entre Cueros (exige marca por línea): se usa otro kiosko estándar.
            kiosko = locationRepository.save(LocationEntity.builder()
                    .code("KIOSKO-TR2").name("Kiosko Transito 2").categoria("KIOSKO").build());
        }
        color = colorRepository.save(ColorEntity.builder().name("CAFE").build());
    }

    @Test
    void envio_dejaLoDespachadoEnTransito() throws Exception {
        ProductEntity product = saveProduct("PT-TR-01");
        seedStock(product, bodegaPt, BigDecimal.TEN);
        ProductShipmentEntity shipment = saveShipment("SHP-TR-1");
        saveDetail(shipment, product, BigDecimal.valueOf(3));

        productDistributionService.sendShipment(shipment.getId());

        assertThat(stockAt(product, bodegaPt)).isEqualByComparingTo("7");
        assertThat(stockAt(product, transit())).isEqualByComparingTo("3");
    }

    @Test
    void revertirEnvio_vaciaTransitoYDevuelveAPt() throws Exception {
        ProductEntity product = saveProduct("PT-TR-02");
        seedStock(product, bodegaPt, BigDecimal.TEN);
        ProductShipmentEntity shipment = saveShipment("SHP-TR-2");
        saveDetail(shipment, product, BigDecimal.valueOf(4));
        productDistributionService.sendShipment(shipment.getId());

        productDistributionService.revertSentShipment(shipment.getId());

        assertThat(stockAt(product, bodegaPt)).isEqualByComparingTo("10");
        assertThat(stockAt(product, transit())).isEqualByComparingTo("0");
    }

    @Test
    void recepcionParcial_dejaPendienteEnTransito_yLuegoSeRecibe() throws Exception {
        ProductEntity product = saveProduct("PT-TR-03");
        seedStock(product, bodegaPt, BigDecimal.TEN);
        ProductShipmentEntity shipment = saveShipment("SHP-TR-3");
        ProductShipmentDetailEntity detail = saveDetail(shipment, product, BigDecimal.valueOf(5));
        productDistributionService.sendShipment(shipment.getId());

        productDistributionService.confirmReceipt(shipment.getId(), ConfirmReceiptRequest.builder()
                .items(List.of(ConfirmReceiptRequest.Item.builder()
                        .detailId(detail.getId()).quantityReceived(BigDecimal.valueOf(3)).build()))
                .build());

        assertThat(stockAt(product, transit())).isEqualByComparingTo("2");
        assertThat(kioskStock(product)).isEqualTo(3);

        productDistributionService.resolvePendingReceipt(shipment.getId(), pending(detail, 2, "RECEIVE"));

        assertThat(stockAt(product, transit())).isEqualByComparingTo("0");
        assertThat(kioskStock(product)).isEqualTo(5);
        ProductShipmentDetailEntity after = shipmentDetailRepository.findById(detail.getId()).orElseThrow();
        assertThat(after.getQuantityReceived()).isEqualByComparingTo("5");
        assertThat(after.getQuantityDifference()).isEqualByComparingTo("0");
    }

    @Test
    void pendiente_noPermiteRecibirMasDeLoQueFalta() throws Exception {
        ProductEntity product = saveProduct("PT-TR-04");
        seedStock(product, bodegaPt, BigDecimal.TEN);
        ProductShipmentEntity shipment = saveShipment("SHP-TR-4");
        ProductShipmentDetailEntity detail = saveDetail(shipment, product, BigDecimal.valueOf(5));
        productDistributionService.sendShipment(shipment.getId());
        productDistributionService.confirmReceipt(shipment.getId(), ConfirmReceiptRequest.builder()
                .items(List.of(ConfirmReceiptRequest.Item.builder()
                        .detailId(detail.getId()).quantityReceived(BigDecimal.valueOf(4)).build()))
                .build());

        assertThatThrownBy(() -> productDistributionService.resolvePendingReceipt(
                shipment.getId(), pending(detail, 2, "RECEIVE")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("pendientes");
    }

    @Test
    void pendiente_regresaABodegaPt() throws Exception {
        ProductEntity product = saveProduct("PT-TR-05");
        seedStock(product, bodegaPt, BigDecimal.TEN);
        ProductShipmentEntity shipment = saveShipment("SHP-TR-5");
        ProductShipmentDetailEntity detail = saveDetail(shipment, product, BigDecimal.valueOf(5));
        productDistributionService.sendShipment(shipment.getId());
        productDistributionService.confirmReceipt(shipment.getId(), ConfirmReceiptRequest.builder()
                .items(List.of(ConfirmReceiptRequest.Item.builder()
                        .detailId(detail.getId()).quantityReceived(BigDecimal.valueOf(3)).build()))
                .build());

        productDistributionService.resolvePendingReceipt(
                shipment.getId(), pending(detail, 2, "RETURN_TO_WAREHOUSE"));

        assertThat(stockAt(product, transit())).isEqualByComparingTo("0");
        assertThat(stockAt(product, bodegaPt)).isEqualByComparingTo("7");
        ProductShipmentDetailEntity after = shipmentDetailRepository.findById(detail.getId()).orElseThrow();
        assertThat(after.getQuantity()).isEqualByComparingTo("3");
        assertThat(after.getQuantityDifference()).isEqualByComparingTo("0");
    }

    @Test
    void devolucionEnLinea_dosLineasMismoProductoYColor_regresanAmbas() throws Exception {
        LocationEntity devoluciones = saveReturnsWarehouse();
        ProductEntity product = saveProduct("PT-TR-06");
        OnlineSaleEntity sale = saveSentSale("V-TR-RET-1");
        saveSaleItem(sale, product, 1);
        saveSaleItem(sale, product, 2);

        onlineSaleService.markAsReturn(sale.getId(), "No le quedó", "BUENO");

        assertThat(stockAt(product, devoluciones)).isEqualByComparingTo("3");
    }

    @Test
    void devolucionEnLinea_danada_noEntraADevolucionesDespachables() throws Exception {
        LocationEntity devoluciones = saveReturnsWarehouse();
        ProductEntity product = saveProduct("PT-TR-07");
        OnlineSaleEntity sale = saveSentSale("V-TR-RET-2");
        saveSaleItem(sale, product, 1);

        onlineSaleService.markAsReturn(sale.getId(), "Llegó roto", "DAÑADO");

        assertThat(stockAt(product, devoluciones)).isEqualByComparingTo("0");
        LocationEntity noApta = locationRepository
                .findByCode(ProductInventoryService.NON_SELLABLE_RETURNS_LOCATION_CODE).orElseThrow();
        assertThat(stockAt(product, noApta)).isEqualByComparingTo("1");
        assertThat(productInventoryService.getDispatchSourceWarehouses())
                .extracting(LocationEntity::getId)
                .doesNotContain(noApta.getId());
    }

    @Test
    void devolucionADeposito_apta_entraADevoluciones_yDanada_aNoAptas() throws Exception {
        LocationEntity devoluciones = saveReturnsWarehouse();
        ProductEntity product = saveProduct("PT-TR-08");
        seedStock(product, bodegaPt, BigDecimal.TEN);
        ProductShipmentEntity shipment = saveShipment("SHP-TR-8");
        saveDetail(shipment, product, BigDecimal.valueOf(5));
        productDistributionService.sendShipment(shipment.getId());
        productDistributionService.confirmReceipt(shipment.getId(), null);
        assertThat(kioskStock(product)).isEqualTo(5);

        kioscoInventoryService.registrarDevolucionDeposito(kiosko.getId(), product.getId(), color.getId(), 2,
                null, null, null, "BD-1", "Reintegro", null, null, true);
        kioscoInventoryService.registrarDevolucionDeposito(kiosko.getId(), product.getId(), color.getId(), 1,
                null, null, null, "BD-2", "Costura rota", null, null, false);

        assertThat(kioskStock(product)).isEqualTo(2);
        assertThat(stockAt(product, devoluciones)).isEqualByComparingTo("2");
        LocationEntity noApta = locationRepository
                .findByCode(ProductInventoryService.NON_SELLABLE_RETURNS_LOCATION_CODE).orElseThrow();
        assertThat(stockAt(product, noApta)).isEqualByComparingTo("1");
    }

    @Test
    void envioDeCinchoSinTalla_seBloquea() {
        ProductEntity cincho = productRepository.save(ProductEntity.builder()
                .code("CIN-TR-01").name("Cincho casual").requiresMaterials(false).build());
        seedStock(cincho, bodegaPt, BigDecimal.TEN);
        ProductShipmentEntity shipment = saveShipment("SHP-TR-CIN");
        saveDetail(shipment, cincho, BigDecimal.ONE);

        assertThatThrownBy(() -> productDistributionService.sendShipment(shipment.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("talla");
        assertThat(stockAt(cincho, bodegaPt)).isEqualByComparingTo("10");
    }

    @Test
    void trasladoDeCincho_exigeTalla_yConTallaMueveStock() throws Exception {
        ProductEntity cincho = productRepository.save(ProductEntity.builder()
                .code("CIN-TR-02").name("Cincho vestir").requiresMaterials(false).build());
        seedStock(cincho, bodegaPt, BigDecimal.TEN);
        LocationEntity devoluciones = saveReturnsWarehouse();

        InventoryTransferRequest sinTalla = InventoryTransferRequest.builder()
                .fromLocationId(bodegaPt.getId()).toLocationId(devoluciones.getId())
                .productId(cincho.getId()).colorId(color.getId())
                .quantity(BigDecimal.valueOf(2)).physicalSlipNumber("TRF-T1").build();
        assertThatThrownBy(() -> inventoryService.createTransfer(sinTalla))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("talla");

        inventoryService.createTransfer(InventoryTransferRequest.builder()
                .fromLocationId(bodegaPt.getId()).toLocationId(devoluciones.getId())
                .productId(cincho.getId()).colorId(color.getId()).sizeKey("34")
                .quantity(BigDecimal.valueOf(2)).physicalSlipNumber("TRF-T2").build());

        assertThat(stockAt(cincho, bodegaPt)).isEqualByComparingTo("8");
        assertThat(stockAt(cincho, devoluciones)).isEqualByComparingTo("2");
    }

    // ===== helpers =====

    private LocationEntity transit() {
        return productInventoryService.getOrCreateTransitLocation();
    }

    private PendingShipmentReceiptRequest pending(ProductShipmentDetailEntity detail, int qty, String action) {
        return PendingShipmentReceiptRequest.builder()
                .items(List.of(PendingShipmentReceiptRequest.Item.builder()
                        .detailId(detail.getId())
                        .quantity(BigDecimal.valueOf(qty))
                        .action(action)
                        .build()))
                .build();
    }

    private LocationEntity saveReturnsWarehouse() {
        inventoryLocationTypeRepository.save(InventoryLocationTypeEntity.builder()
                .code("BODEGA_DEV").name("Bodega Devoluciones").isActive(true).build());
        return locationRepository.save(LocationEntity.builder()
                .code("BODEGA_DEV").name("Bodega Devoluciones").categoria("BODEGA_DEV").build());
    }

    private OnlineSaleEntity saveSentSale(String number) {
        return onlineSaleRepository.save(OnlineSaleEntity.builder()
                .saleNumber(number)
                .customerName("Cliente devolución")
                .status("ENVIADO")
                .paymentMethod("TARJETA_PAGADO")
                .saleDate(LocalDate.now())
                .inProductionOrder(false)
                .build());
    }

    private void saveSaleItem(OnlineSaleEntity sale, ProductEntity product, int qty) {
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

    private ProductEntity saveProduct(String code) {
        return productRepository.save(ProductEntity.builder()
                .code(code)
                .name("Producto " + code)
                .requiresMaterials(false)
                .build());
    }

    private void seedStock(ProductEntity product, LocationEntity location, BigDecimal quantity) {
        inventoryLocationRepository.save(ProductInventoryLocation.builder()
                .productId(product.getId())
                .locationId(location.getId())
                .colorId(color.getId())
                .quantity(quantity)
                .build());
    }

    private ProductShipmentEntity saveShipment(String number) {
        return shipmentRepository.save(ProductShipmentEntity.builder()
                .shipmentNumber(number)
                .status("CONFIRMED")
                .locationId(kiosko.getId())
                .build());
    }

    private ProductShipmentDetailEntity saveDetail(ProductShipmentEntity shipment, ProductEntity product, BigDecimal qty) {
        return shipmentDetailRepository.save(ProductShipmentDetailEntity.builder()
                .shipmentId(shipment.getId())
                .productId(product.getId())
                .colorId(color.getId())
                .sizeLabel("")
                .quantity(qty)
                .build());
    }

    private BigDecimal stockAt(ProductEntity product, LocationEntity location) {
        return inventoryLocationRepository
                .findByProductIdAndLocationIdAndColorId(product.getId(), location.getId(), color.getId())
                .map(ProductInventoryLocation::getQuantity)
                .orElse(BigDecimal.ZERO);
    }

    private int kioskStock(ProductEntity product) {
        return kioscoStockRepository.findByLocationIdAndProductIdAndColorId(kiosko.getId(), product.getId(), color.getId())
                .map(s -> s.getCurrentStock() != null ? s.getCurrentStock() : 0)
                .orElse(0);
    }
}
