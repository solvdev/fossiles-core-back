package com.fossiles.fossilescorebackend.application.service;

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
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Los cinchos hacia Entrecueros no pasan por Bodega PT/Devoluciones: se pueden enviar sin stock,
 * no descuentan bodega y no quedan en tránsito. El resto de productos sigue validando stock.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ShipmentCinchoEntrecuerosNoWarehouseTest {

    @Autowired private ProductDistributionService productDistributionService;
    @Autowired private ProductInventoryService productInventoryService;
    @Autowired private ProductShipmentRepository shipmentRepository;
    @Autowired private ProductShipmentDetailRepository shipmentDetailRepository;
    @Autowired private ProductInventoryLocationRepository inventoryLocationRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ColorRepository colorRepository;
    @Autowired private InventoryLocationTypeRepository inventoryLocationTypeRepository;
    @Autowired private LocationRepository locationRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;

    @MockBean private SecurityUtil securityUtil;

    private LocationEntity bodegaPt;
    private LocationEntity entrecueros;
    private LocationEntity kioskoEstandar;
    private ColorEntity color;

    @BeforeEach
    void setUp() {
        RoleEntity adminRole = roleRepository.save(RoleEntity.builder().name("ADMIN").build());
        UserEntity admin = userRepository.save(UserEntity.builder()
                .username("admin.entrecueros")
                .email("admin.entrecueros@fossiles.test")
                .password("x")
                .status("ACTIVE")
                .roles(new HashSet<>(Set.of(adminRole)))
                .build());
        when(securityUtil.getCurrentUserId()).thenReturn(admin.getId());

        inventoryLocationTypeRepository.save(InventoryLocationTypeEntity.builder()
                .code("BODEGA_PT").name("Bodega PT").isActive(true).build());
        bodegaPt = locationRepository.save(LocationEntity.builder()
                .code("BODEGA_PT").name("Bodega Producto Terminado").categoria("BODEGA_PT").build());
        entrecueros = locationRepository.save(LocationEntity.builder()
                .code("KIOSKO-EC").name("Entrecueros").categoria("KIOSKO")
                .posMode(KioskPosMode.ENTRECUEROS).build());
        kioskoEstandar = locationRepository.save(LocationEntity.builder()
                .code("KIOSKO-STD").name("Kiosko estandar").categoria("KIOSKO").build());
        color = colorRepository.save(ColorEntity.builder().name("NEGRO").build());
    }

    @Test
    void cinchoAEntrecueros_seEnviaSinStock_yNoTocaBodegaNiTransito() throws Exception {
        ProductEntity cincho = saveProduct("FOSS-EC-01", "Cincho casual");
        ProductShipmentEntity shipment = saveShipment("SHP-EC-1", entrecueros);
        saveDetail(shipment, cincho, "34", BigDecimal.valueOf(5));

        productDistributionService.sendShipment(shipment.getId());

        assertThat(shipmentRepository.findById(shipment.getId()).orElseThrow().getStatus()).isEqualTo("SENT");
        assertThat(stockAt(cincho, bodegaPt)).isEqualByComparingTo("0");
        assertThat(stockAt(cincho, productInventoryService.getOrCreateTransitLocation())).isEqualByComparingTo("0");
    }

    @Test
    void cinchoAEntrecueros_conStockEnBodega_noLoDescuenta() throws Exception {
        ProductEntity cincho = saveProduct("FOSS-EC-02", "Cincho vestir");
        seedStock(cincho, bodegaPt, BigDecimal.TEN);
        ProductShipmentEntity shipment = saveShipment("SHP-EC-2", entrecueros);
        saveDetail(shipment, cincho, "36", BigDecimal.valueOf(4));

        productDistributionService.sendShipment(shipment.getId());

        assertThat(stockAt(cincho, bodegaPt)).isEqualByComparingTo("10");
    }

    @Test
    void cinchoAKioskoEstandar_sigueValidandoStock() {
        ProductEntity cincho = saveProduct("FOSS-EC-03", "Cincho casual");
        ProductShipmentEntity shipment = saveShipment("SHP-EC-3", kioskoEstandar);
        saveDetail(shipment, cincho, "34", BigDecimal.valueOf(5));

        assertThatThrownBy(() -> productDistributionService.sendShipment(shipment.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Stock insuficiente");
    }

    @Test
    void productoNoCinchoAEntrecueros_sigueValidandoStock() {
        ProductEntity billetera = saveProduct("BILL-EC-01", "Billetera clasica");
        ProductShipmentEntity shipment = saveShipment("SHP-EC-4", entrecueros);
        saveDetail(shipment, billetera, "", BigDecimal.valueOf(2));

        assertThatThrownBy(() -> productDistributionService.sendShipment(shipment.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Stock insuficiente");
    }

    @Test
    void envioMixto_soloDescuentaLoQueNoEsCincho() throws Exception {
        ProductEntity cincho = saveProduct("FOSS-EC-05", "Cincho casual");
        ProductEntity billetera = saveProduct("BILL-EC-05", "Billetera clasica");
        seedStock(billetera, bodegaPt, BigDecimal.TEN);
        ProductShipmentEntity shipment = saveShipment("SHP-EC-5", entrecueros);
        saveDetail(shipment, cincho, "34", BigDecimal.valueOf(5));
        saveDetail(shipment, billetera, "", BigDecimal.valueOf(3));

        productDistributionService.sendShipment(shipment.getId());

        assertThat(stockAt(cincho, bodegaPt)).isEqualByComparingTo("0");
        assertThat(stockAt(billetera, bodegaPt)).isEqualByComparingTo("7");
    }

    private ProductEntity saveProduct(String code, String name) {
        return productRepository.save(ProductEntity.builder()
                .code(code).name(name).requiresMaterials(false).build());
    }

    private void seedStock(ProductEntity product, LocationEntity location, BigDecimal quantity) {
        inventoryLocationRepository.save(ProductInventoryLocation.builder()
                .productId(product.getId())
                .locationId(location.getId())
                .colorId(color.getId())
                .quantity(quantity)
                .build());
    }

    private ProductShipmentEntity saveShipment(String number, LocationEntity destination) {
        return shipmentRepository.save(ProductShipmentEntity.builder()
                .shipmentNumber(number)
                .status("CONFIRMED")
                .locationId(destination.getId())
                .build());
    }

    private void saveDetail(ProductShipmentEntity shipment, ProductEntity product, String size, BigDecimal qty) {
        shipmentDetailRepository.save(ProductShipmentDetailEntity.builder()
                .shipmentId(shipment.getId())
                .productId(product.getId())
                .colorId(color.getId())
                .sizeLabel(size)
                .quantity(qty)
                .build());
    }

    private BigDecimal stockAt(ProductEntity product, LocationEntity location) {
        return inventoryLocationRepository
                .findByProductIdAndLocationIdAndColorId(product.getId(), location.getId(), color.getId())
                .map(ProductInventoryLocation::getQuantity)
                .orElse(BigDecimal.ZERO);
    }
}
