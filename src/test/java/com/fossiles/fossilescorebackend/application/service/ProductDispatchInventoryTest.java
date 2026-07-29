package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.ProductShipmentRequest;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.*;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductInventorySizesJson;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Salidas de Bodega PT / Devoluciones: que el documento y el inventario cuenten lo mismo.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ProductDispatchInventoryTest {

    @Autowired
    private ProductDistributionService productDistributionService;

    @Autowired
    private ProductInventoryService productInventoryService;

    @Autowired
    private ProductShipmentRepository shipmentRepository;

    @Autowired
    private ProductShipmentDetailRepository shipmentDetailRepository;

    @Autowired
    private ProductInventoryLocationRepository inventoryLocationRepository;

    @Autowired
    private ProductInventoryKardexRepository kardexRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ColorRepository colorRepository;

    @Autowired
    private InventoryLocationTypeRepository inventoryLocationTypeRepository;

    @Autowired
    private LocationRepository locationRepository;

    @Autowired
    private EntityManager entityManager;

    @MockBean
    private SecurityUtil securityUtil;

    private LocationEntity bodegaPt;
    private LocationEntity kiosko;
    private ColorEntity color;

    @BeforeEach
    void setUp() {
        when(securityUtil.getCurrentUserId()).thenReturn(7L);

        inventoryLocationTypeRepository.save(InventoryLocationTypeEntity.builder()
                .code("BODEGA_PT")
                .name("Bodega PT")
                .isActive(true)
                .build());
        bodegaPt = locationRepository.save(LocationEntity.builder()
                .code("BODEGA_PT")
                .name("Bodega Producto Terminado")
                .categoria("BODEGA_PT")
                .build());
        kiosko = locationRepository.save(LocationEntity.builder()
                .code("KIOSKO-01")
                .name("Kiosko Cayala")
                .categoria("KIOSKO")
                .build());
        color = colorRepository.save(ColorEntity.builder().name("NEGRO").build());
    }

    @Test
    void envioAKiosko_descargaBodegaPt() throws Exception {
        ProductEntity product = saveProduct("PT-DISP-01", "Producto despacho");
        seedStock(product, BigDecimal.valueOf(10), null);

        ProductShipmentEntity shipment = saveShipment("SHP-KIOSK-1", kiosko.getId());
        saveDetail(shipment, product, null, BigDecimal.valueOf(3));

        productDistributionService.sendShipment(shipment.getId());

        assertThat(stockAt(product, bodegaPt)).isEqualByComparingTo("7");

        List<ProductInventoryKardex> outflows = kardexRepository
                .findByReferenceTypeAndReferenceId("SHIPMENT", shipment.getId()).stream()
                .filter(k -> k.getQuantity().signum() < 0)
                .toList();
        assertThat(outflows).hasSize(1);
        assertThat(outflows.get(0).getLocationId()).isEqualTo(bodegaPt.getId());
        assertThat(outflows.get(0).getQuantity()).isEqualByComparingTo("-3");
    }

    @Test
    void dosTallasDelMismoProductoYColor_descarganAmbas() throws Exception {
        ProductEntity cincho = saveProduct("FOSS-CINCHO-01", "Cincho FOSS");
        seedStock(cincho, BigDecimal.valueOf(9), Map.of(
                "30", BigDecimal.valueOf(5),
                "32", BigDecimal.valueOf(4)));

        ProductShipmentEntity shipment = saveShipment("SHP-TALLAS-1", kiosko.getId());
        saveDetail(shipment, cincho, "30", BigDecimal.valueOf(2));
        saveDetail(shipment, cincho, "32", BigDecimal.valueOf(3));

        productDistributionService.sendShipment(shipment.getId());

        ProductInventoryLocation stock = inventoryLocationRepository
                .findByProductIdAndLocationIdAndColorId(cincho.getId(), bodegaPt.getId(), color.getId())
                .orElseThrow();
        assertThat(stock.getQuantity()).isEqualByComparingTo("4");

        Map<String, BigDecimal> sizes = ProductInventorySizesJson.parse(stock.getSizesData());
        assertThat(sizes.get("30")).isEqualByComparingTo("3");
        assertThat(sizes.get("32")).isEqualByComparingTo("1");

        // Una fila de kardex por talla: sin ellas la segunda salida se daba por aplicada.
        List<ProductInventoryKardex> outflows = kardexRepository
                .findByReferenceTypeAndReferenceId("SHIPMENT", shipment.getId()).stream()
                .filter(k -> k.getQuantity().signum() < 0)
                .toList();
        assertThat(outflows).hasSize(2);
        assertThat(outflows).extracting(ProductInventoryKardex::getSizeLabel)
                .containsExactlyInAnyOrder("30", "32");
    }

    @Test
    void reversionDeSalida_esIdempotente() throws Exception {
        ProductEntity product = saveProduct("PT-DISP-02", "Producto reversion");
        seedStock(product, BigDecimal.valueOf(10), null);

        ProductShipmentEntity shipment = saveShipment("SHP-REV-1", kiosko.getId());
        saveDetail(shipment, product, null, BigDecimal.valueOf(4));
        productDistributionService.sendShipment(shipment.getId());
        assertThat(stockAt(product, bodegaPt)).isEqualByComparingTo("6");

        productInventoryService.reverseDispatchOutflows("SHIPMENT", shipment.getId(),
                ProductInventoryService.MOVEMENT_SHIPMENT, shipment.getShipmentNumber(), "Reversión test");
        assertThat(stockAt(product, bodegaPt)).isEqualByComparingTo("10");

        // Repetir la reversión no puede volver a acreditar el mismo stock.
        productInventoryService.reverseDispatchOutflows("SHIPMENT", shipment.getId(),
                ProductInventoryService.MOVEMENT_SHIPMENT, shipment.getShipmentNumber(), "Reversión test");
        assertThat(stockAt(product, bodegaPt)).isEqualByComparingTo("10");
    }

    @Test
    void editarEnvioEnTransito_dejaElStockSegunLaNuevaCantidad() throws Exception {
        ProductEntity product = saveProduct("PT-DISP-03", "Producto edicion");
        seedStock(product, BigDecimal.valueOf(10), null);

        ProductShipmentEntity shipment = saveShipment("SHP-EDIT-1", kiosko.getId());
        saveDetail(shipment, product, null, BigDecimal.valueOf(3));
        productDistributionService.sendShipment(shipment.getId());
        assertThat(stockAt(product, bodegaPt)).isEqualByComparingTo("7");

        entityManager.flush();
        entityManager.clear();

        productDistributionService.updateShipmentProducts(shipment.getId(), List.of(
                ProductShipmentRequest.ProductShipmentDetailRequest.builder()
                        .productId(product.getId())
                        .colorId(color.getId())
                        .quantity(BigDecimal.valueOf(5))
                        .build()));

        assertThat(stockAt(product, bodegaPt)).isEqualByComparingTo("5");
    }

    @Test
    void kardexNeteaLaSalidaConSuReversion() throws Exception {
        ProductEntity product = saveProduct("PT-DISP-04", "Producto neteo");
        seedStock(product, BigDecimal.valueOf(8), null);

        ProductShipmentEntity shipment = saveShipment("SHP-NET-1", kiosko.getId());
        ProductShipmentDetailEntity detail = saveDetail(shipment, product, null, BigDecimal.valueOf(2));
        productDistributionService.sendShipment(shipment.getId());

        assertThat(productInventoryService.getNetConsumedForLine("SHIPMENT", shipment.getId(),
                ProductInventoryService.MOVEMENT_SHIPMENT, product.getId(), null, color.getId(), detail.getId()))
                .isEqualByComparingTo("2");

        productInventoryService.reverseDispatchOutflows("SHIPMENT", shipment.getId(),
                ProductInventoryService.MOVEMENT_SHIPMENT, shipment.getShipmentNumber(), "Reversión test");

        assertThat(productInventoryService.getNetConsumedForLine("SHIPMENT", shipment.getId(),
                ProductInventoryService.MOVEMENT_SHIPMENT, product.getId(), null, color.getId(), detail.getId()))
                .isEqualByComparingTo("0");
    }

    // ===== helpers =====

    private ProductEntity saveProduct(String code, String name) {
        return productRepository.save(ProductEntity.builder()
                .code(code)
                .name(name)
                .requiresMaterials(false)
                .build());
    }

    private void seedStock(ProductEntity product, BigDecimal quantity, Map<String, BigDecimal> sizes) {
        inventoryLocationRepository.save(ProductInventoryLocation.builder()
                .productId(product.getId())
                .locationId(bodegaPt.getId())
                .colorId(color.getId())
                .quantity(quantity)
                .sizesData(sizes == null ? null : ProductInventorySizesJson.serialize(sizes))
                .build());
    }

    private ProductShipmentEntity saveShipment(String number, Long locationId) {
        return shipmentRepository.save(ProductShipmentEntity.builder()
                .shipmentNumber(number)
                .status("CONFIRMED")
                .locationId(locationId)
                .build());
    }

    private ProductShipmentDetailEntity saveDetail(
            ProductShipmentEntity shipment, ProductEntity product, String size, BigDecimal quantity) {
        return shipmentDetailRepository.save(ProductShipmentDetailEntity.builder()
                .shipmentId(shipment.getId())
                .productId(product.getId())
                .colorId(color.getId())
                .sizeLabel(size)
                .quantity(quantity)
                .build());
    }

    private BigDecimal stockAt(ProductEntity product, LocationEntity location) {
        return inventoryLocationRepository
                .findByProductIdAndLocationIdAndColorId(product.getId(), location.getId(), color.getId())
                .map(ProductInventoryLocation::getQuantity)
                .orElse(BigDecimal.ZERO);
    }
}
