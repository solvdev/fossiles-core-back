package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.ProductShipmentResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.*;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Listado "Recibir distribución" del POS: una sola consulta de pendientes por kiosko (SENT + entregados con
 * líneas por recibir) y armado de los DTO en lote, sin consultas por envío/línea.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Transactional
class ShipmentPendingReceiptListingTest {

    @Autowired private ProductDistributionService service;
    @Autowired private ProductShipmentRepository shipmentRepository;
    @Autowired private ProductShipmentDetailRepository detailRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ColorRepository colorRepository;
    @Autowired private ProductCategoryRepository categoryRepository;
    @Autowired private LocationRepository locationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private EntityManagerFactory entityManagerFactory;

    @MockBean private SecurityUtil securityUtil;

    private LocationEntity kioskA;
    private LocationEntity kioskB;
    private ProductEntity product1;
    private ProductEntity product2;
    private ColorEntity color;

    @BeforeEach
    void setUp() {
        RoleEntity role = roleRepository.save(RoleEntity.builder().name("ENCARGADA").build());
        UserEntity encargada = userRepository.save(UserEntity.builder()
                .username("encargada.recibir")
                .email("encargada.recibir@fossiles.test")
                .firstName("Ana")
                .lastName("Gómez")
                .password("x")
                .status("ACTIVE")
                .roles(new HashSet<>(Set.of(role)))
                .build());
        kioskA = locationRepository.save(LocationEntity.builder()
                .code("KIOSK_A").name("Kiosko A").categoria("KIOSKO").encargadoId(encargada.getId()).build());
        kioskB = locationRepository.save(LocationEntity.builder()
                .code("KIOSK_B").name("Kiosko B").categoria("KIOSKO").build());
        when(securityUtil.getCurrentUserId()).thenReturn(encargada.getId());

        ProductCategoryEntity category = categoryRepository.save(ProductCategoryEntity.builder().code("CINCHOS").name("Cinchos").build());
        product1 = productRepository.save(ProductEntity.builder()
                .code("P1").name("Producto 1").categoryId(category.getId()).requiresMaterials(false).build());
        product2 = productRepository.save(ProductEntity.builder()
                .code("P2").name("Producto 2").categoryId(category.getId()).requiresMaterials(false).build());
        color = colorRepository.save(ColorEntity.builder().name("NEGRO").build());
    }

    @Test
    void pendingReceipt_returnsSentAndDeliveredWithPendingLines_onlyForThatKiosk() throws Exception {
        ProductShipmentEntity sent1 = shipment("A-ENV-1", "SENT", kioskA, null, null);
        ProductShipmentEntity sent2 = shipment("A-ENV-2", "sent", kioskA, null, null);
        ProductShipmentEntity deliveredPending = shipment("A-ENV-3", "DELIVERED", kioskA, new BigDecimal("3"), new BigDecimal("5"));
        shipment("A-ENV-4", "DELIVERED", kioskA, new BigDecimal("5"), new BigDecimal("5"));
        shipment("A-ENV-5", "DELIVERED", kioskA, null, new BigDecimal("5"));
        shipment("A-ENV-6", "DRAFT", kioskA, null, null);
        shipment("B-ENV-1", "SENT", kioskB, null, null);
        flushAndClear();

        List<ProductShipmentResponse> result = service.getShipmentsPendingReceipt(kioskA.getId());

        assertThat(result).extracting(ProductShipmentResponse::getId)
                .containsExactly(sent1.getId(), sent2.getId(), deliveredPending.getId());
        ProductShipmentResponse first = result.get(0);
        assertThat(first.getLocationName()).isEqualTo("Kiosko A");
        assertThat(first.getLocationCode()).isEqualTo("KIOSK_A");
        assertThat(first.getCreatedByName()).isEqualTo("Ana Gómez");
        assertThat(first.getProducts()).hasSize(2);
        assertThat(first.getProducts().get(0).getProductCode()).isEqualTo("P1");
        assertThat(first.getProducts().get(0).getCategoryName()).isEqualTo("Cinchos");
        assertThat(first.getProducts().get(0).getColorName()).isEqualTo("NEGRO");
    }

    @Test
    void pendingReceipt_rejectsKioskOutsideUserScope() {
        assertThatThrownBy(() -> service.getShipmentsPendingReceipt(kioskB.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("No tienes acceso");
    }

    @Test
    void pendingReceipt_queryCountDoesNotGrowWithShipments() throws Exception {
        for (int i = 0; i < 20; i++) {
            shipment("A-BULK-" + i, "SENT", kioskA, null, null);
        }
        shipment("A-BULK-DELIVERED", "DELIVERED", kioskA, new BigDecimal("1"), new BigDecimal("5"));
        flushAndClear();

        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        List<ProductShipmentResponse> result = service.getShipmentsPendingReceipt(kioskA.getId());
        long statements = stats.getPrepareStatementCount();

        assertThat(result).hasSize(21);
        System.out.println("pending-receipt: 21 envíos x 2 líneas -> " + statements + " sentencias SQL");
        // usuario + ubicaciones del alcance + 2 listados + detalles + productos + categorías + colores
        // + ubicaciones + usuarios: constante, no depende de cuántos envíos haya.
        assertThat(statements).isLessThanOrEqualTo(15);
    }

    private ProductShipmentEntity shipment(String number, String status, LocationEntity location,
                                           BigDecimal received, BigDecimal quantity) {
        ProductShipmentEntity shipment = shipmentRepository.save(ProductShipmentEntity.builder()
                .shipmentNumber(number)
                .status(status)
                .locationId(location.getId())
                .createdBy(userRepository.findAll().get(0).getId())
                .sentAt(LocalDateTime.now())
                .build());
        BigDecimal qty = quantity != null ? quantity : new BigDecimal("5");
        detailRepository.save(ProductShipmentDetailEntity.builder()
                .shipmentId(shipment.getId()).productId(product1.getId()).colorId(color.getId())
                .sizeLabel("").hardwareCondition("").quantity(qty).quantityReceived(received).build());
        detailRepository.save(ProductShipmentDetailEntity.builder()
                .shipmentId(shipment.getId()).productId(product2.getId()).colorId(color.getId())
                .sizeLabel("").hardwareCondition("").quantity(qty).quantityReceived(
                        received != null ? qty : null).build());
        return shipment;
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
