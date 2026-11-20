package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.ShipmentSearchRequest;
import com.fossiles.fossilescorebackend.application.dto.response.ShipmentSearchOptionsResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ShipmentSearchResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.DistributionOrigin;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.InventoryLocationTypeEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductDistributionEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductInventoryKardex;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductShipmentDetailEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductShipmentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.RoleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.InventoryLocationTypeRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductDistributionRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductInventoryKardexRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductShipmentDetailRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductShipmentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.RoleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Consulta «Envíos»: filtros combinados, procedencia derivada, permisos de encargada, tope de 90 días, origen de
 * descarga y número de consultas constante (sin N+1).
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
class ShipmentSearchServiceTest {

    @Autowired private ShipmentSearchService searchService;
    @Autowired private ProductShipmentRepository shipments;
    @Autowired private ProductShipmentDetailRepository details;
    @Autowired private ProductRepository products;
    @Autowired private ColorRepository colors;
    @Autowired private LocationRepository locations;
    @Autowired private ProductDistributionRepository distributions;
    @Autowired private ProductionOrderRepository orders;
    @Autowired private ProductInventoryKardexRepository kardex;
    @Autowired private InventoryLocationTypeRepository locationTypes;
    @Autowired private UserRepository users;
    @Autowired private RoleRepository roles;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManagerFactory emf;

    @MockBean private SecurityUtil securityUtil;

    private UserEntity admin;
    private ColorEntity tostado;

    @BeforeEach
    void setUp() {
        RoleEntity adminRole = roles.save(RoleEntity.builder().name("ADMIN-" + tag()).build());
        admin = users.save(user("admin", adminRole));
        when(securityUtil.getCurrentUserId()).thenReturn(admin.getId());
        tostado = colors.save(ColorEntity.builder().name("TOSTADO-" + tag()).build());
    }

    @Test
    void findsAnOldShipmentByKioskDateAndProductWithoutItsNumber() throws Exception {
        LocationEntity kiosk = saveKiosk("ESC");
        ProductEntity b56 = saveProduct("B56");
        ProductEntity b7 = saveProduct("B7");
        ProductShipmentEntity target = saveShipment(kiosk, "ENV-00065", null, null, "DELIVERED");
        line(target, b56, "3", "2");
        backdate(target, LocalDateTime.of(2026, 10, 22, 9, 0));
        ProductShipmentEntity otherProduct = saveShipment(kiosk, "ENV-00066", null, null, "DELIVERED");
        line(otherProduct, b7, "1", null);
        backdate(otherProduct, LocalDateTime.of(2026, 10, 22, 10, 0));
        ProductShipmentEntity outOfRange = saveShipment(kiosk, "ENV-00010", null, null, "DELIVERED");
        line(outOfRange, b56, "1", null);
        backdate(outOfRange, LocalDateTime.of(2026, 9, 1, 10, 0));

        ShipmentSearchResponse result = searchService.search(ShipmentSearchRequest.builder()
                .kioskIds(List.of(kiosk.getId()))
                .from(LocalDate.of(2026, 10, 20)).to(LocalDate.of(2026, 10, 25))
                .product(b56.getCode().toLowerCase() + " tostado")
                .build());

        assertThat(result.getItems()).extracting(i -> i.getShipment().getId()).containsExactly(target.getId());
        ShipmentSearchResponse.Item item = result.getItems().get(0);
        assertThat(item.getProvenance()).isEqualTo(ShipmentSearchService.P_DIRECTO);
        assertThat(item.getReceiptDifference()).isTrue();
        assertThat(item.getTotalUnits()).isEqualByComparingTo("3");
        assertThat(item.getTotalReceived()).isEqualByComparingTo("2");
        assertThat(result.isDefaultRangeApplied()).isFalse();

        ShipmentSearchResponse withDiff = searchService.search(ShipmentSearchRequest.builder()
                .kioskIds(List.of(kiosk.getId()))
                .from(LocalDate.of(2026, 10, 20)).to(LocalDate.of(2026, 10, 25))
                .receiptDifference(true)
                .build());
        assertThat(withDiff.getItems()).extracting(i -> i.getShipment().getId()).containsExactly(target.getId());
    }

    @Test
    void derivesProvenanceAndFiltersByIt() throws Exception {
        LocationEntity kiosk = saveKiosk("PRV");
        ProductEntity p = saveProduct("PRV");
        ProductDistributionEntity manual = saveDistribution(DistributionOrigin.MANUAL, "Distribución semanal");
        ProductDistributionEntity repo = saveDistribution(DistributionOrigin.REPOSICION, "Reposición automática");
        ProductDistributionEntity legacyDirect = saveDistribution(DistributionOrigin.MANUAL,
                ProductDistributionService.DIRECT_KIOSK_DISTRIBUTION_DESCRIPTION + " · Kiosko");
        ProductionOrderEntity opk = orders.save(ProductionOrderEntity.builder()
                .code("OPK-" + tag()).orderType("NORMAL").status("IN_PROGRESS").startDate(LocalDate.now()).build());

        ProductShipmentEntity direct = withLine(saveShipment(kiosk, "ENV-1", null, null, "CONFIRMED"), p);
        ProductShipmentEntity oldDirect = withLine(saveShipment(kiosk, "ENV-2", legacyDirect.getId(), null, "SENT"), p);
        ProductShipmentEntity inDist = withLine(saveShipment(kiosk, "ENV-3", manual.getId(), null, "SENT"), p);
        ProductShipmentEntity inRepo = withLine(saveShipment(kiosk, "ENV-4", repo.getId(), null, "CONFIRMED"), p);
        ProductShipmentEntity pending = saveShipment(kiosk, "ENV-5", repo.getId(), null, "DRAFT");
        pending.setNotes("Faltante\n" + ProductDistributionService.PENDING_PRODUCTION_TAG);
        shipments.save(pending);
        ProductShipmentEntity byOp = withLine(saveShipment(kiosk, "ENV-6", manual.getId(), opk.getId(), "DELIVERED"), p);
        ProductShipmentEntity historical = saveShipment(kiosk, "ENV-7", null, null, "SENT");
        historical.setNotes(CustomerAccountService.HISTORICAL_SHIPMENT_NOTE);
        shipments.save(historical);

        ShipmentSearchResponse all = searchService.search(ShipmentSearchRequest.builder()
                .kioskIds(List.of(kiosk.getId())).build());
        assertThat(all.getTotal()).isEqualTo(7);
        assertThat(provenanceOf(all, direct)).isEqualTo(ShipmentSearchService.P_DIRECTO);
        assertThat(provenanceOf(all, oldDirect)).isEqualTo(ShipmentSearchService.P_DIRECTO);
        assertThat(provenanceOf(all, inDist)).isEqualTo(ShipmentSearchService.P_DISTRIBUCION);
        assertThat(provenanceOf(all, inRepo)).isEqualTo(ShipmentSearchService.P_REPOSICION);
        assertThat(provenanceOf(all, pending)).isEqualTo(ShipmentSearchService.P_PENDIENTE);
        assertThat(provenanceOf(all, byOp)).isEqualTo(ShipmentSearchService.P_OP);
        assertThat(provenanceOf(all, historical)).isEqualTo(ShipmentSearchService.P_HISTORICO);
        assertThat(all.getItems().stream().filter(i -> i.getShipment().getId().equals(byOp.getId())).findFirst()
                .orElseThrow().getProductionOrderType()).isEqualTo("OPK");
        assertThat(all.getCountsByProvenance().get(ShipmentSearchService.P_DIRECTO)).isEqualTo(2L);

        ShipmentSearchResponse directSent = searchService.search(ShipmentSearchRequest.builder()
                .kioskIds(List.of(kiosk.getId()))
                .provenances(List.of("directo"))
                .statuses(List.of("SENT"))
                .build());
        assertThat(directSent.getItems()).extracting(i -> i.getShipment().getId()).containsExactly(oldDirect.getId());
        // Los chips de procedencia cuentan con el filtro de estado; los de estado, con el de procedencia.
        assertThat(directSent.getCountsByProvenance().get(ShipmentSearchService.P_DISTRIBUCION)).isEqualTo(1L);
        assertThat(directSent.getCountsByStatus().get("CONFIRMED")).isEqualTo(1L);

        ShipmentSearchResponse byDist = searchService.search(ShipmentSearchRequest.builder()
                .kioskIds(List.of(kiosk.getId()))
                .distributionNumber(manual.getDistributionNumber())
                .build());
        assertThat(byDist.getItems()).extracting(i -> i.getShipment().getId())
                .containsExactlyInAnyOrder(inDist.getId(), byOp.getId());

        ShipmentSearchResponse pendingOnly = searchService.search(ShipmentSearchRequest.builder()
                .kioskIds(List.of(kiosk.getId())).pendingProduction(true).build());
        assertThat(pendingOnly.getItems()).extracting(i -> i.getShipment().getId()).containsExactly(pending.getId());
    }

    @Test
    void encargadaOnlySeesHerKiosks() throws Exception {
        LocationEntity hers = saveKiosk("HER");
        LocationEntity other = saveKiosk("OTH");
        ProductEntity p = saveProduct("ENC");
        ProductShipmentEntity mine = withLine(saveShipment(hers, "ENV-1", null, null, "SENT"), p);
        withLine(saveShipment(other, "ENV-1", null, null, "SENT"), p);

        RoleEntity encargadaRole = roles.save(RoleEntity.builder().name("ENCARGADA-" + tag()).build());
        UserEntity encargada = users.save(user("encargada", encargadaRole));
        hers.setEncargadoId(encargada.getId());
        locations.save(hers);
        when(securityUtil.getCurrentUserId()).thenReturn(encargada.getId());

        ShipmentSearchResponse result = searchService.search(new ShipmentSearchRequest());
        assertThat(result.getItems()).extracting(i -> i.getShipment().getId()).containsExactly(mine.getId());

        ShipmentSearchResponse askedForOther = searchService.search(ShipmentSearchRequest.builder()
                .kioskIds(List.of(other.getId())).build());
        assertThat(askedForOther.getItems()).isEmpty();

        ShipmentSearchOptionsResponse options = searchService.options();
        assertThat(options.isAllKiosks()).isFalse();
        assertThat(options.getKiosks()).extracting(ShipmentSearchOptionsResponse.Kiosk::getId).containsExactly(hers.getId());

        UserEntity nobody = users.save(user("sinkiosco", encargadaRole));
        when(securityUtil.getCurrentUserId()).thenReturn(nobody.getId());
        assertThatThrownBy(() -> searchService.search(new ShipmentSearchRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("no tiene kioscos asignados");
    }

    @Test
    void withoutRangeOnlyTheLast90DaysAreSearched() throws Exception {
        LocationEntity kiosk = saveKiosk("TOP");
        ProductEntity p = saveProduct("TOP");
        ProductShipmentEntity recent = withLine(saveShipment(kiosk, "ENV-1", null, null, "DELIVERED"), p);
        ProductShipmentEntity old = withLine(saveShipment(kiosk, "ENV-2", null, null, "DELIVERED"), p);
        backdate(old, LocalDateTime.now().minusDays(120));

        ShipmentSearchResponse result = searchService.search(ShipmentSearchRequest.builder()
                .kioskIds(List.of(kiosk.getId())).build());

        assertThat(result.isDefaultRangeApplied()).isTrue();
        assertThat(result.getFrom()).isEqualTo(LocalDate.now().minusDays(ShipmentSearchService.DEFAULT_DAYS));
        assertThat(result.getItems()).extracting(i -> i.getShipment().getId()).containsExactly(recent.getId());

        ShipmentSearchResponse explicit = searchService.search(ShipmentSearchRequest.builder()
                .kioskIds(List.of(kiosk.getId()))
                .from(LocalDate.now().minusDays(200)).to(LocalDate.now())
                .build());
        assertThat(explicit.getTotal()).isEqualTo(2);
    }

    @Test
    void reportsWhereEachShipmentLeftFromAndFiltersByIt() throws Exception {
        LocationEntity pt = warehouse("BODEGA_PT", "Bodega Producto Terminado");
        LocationEntity dev = warehouse("BODEGA_DEV", "Bodega Devoluciones");
        LocationEntity kiosk = saveKiosk("ORG");
        ProductEntity p = saveProduct("ORG");
        ProductShipmentEntity fromPt = withLine(saveShipment(kiosk, "ENV-1", null, null, "SENT"), p);
        ProductShipmentEntity mixed = withLine(saveShipment(kiosk, "ENV-2", null, null, "SENT"), p);
        out(fromPt, p, pt, "-2");
        out(mixed, p, dev, "-1");
        out(mixed, p, pt, "-1");

        ShipmentSearchResponse all = searchService.search(ShipmentSearchRequest.builder()
                .kioskIds(List.of(kiosk.getId())).build());
        assertThat(all.getItems().stream().filter(i -> i.getShipment().getId().equals(mixed.getId())).findFirst()
                .orElseThrow().getDispatchOrigin()).isEqualTo(ShipmentSearchService.ORIGIN_MIXED);
        assertThat(all.getItems().stream().filter(i -> i.getShipment().getId().equals(fromPt.getId())).findFirst()
                .orElseThrow().getDispatchOrigin()).isEqualTo(ShipmentSearchService.ORIGIN_PT);

        ShipmentSearchResponse onlyMixed = searchService.search(ShipmentSearchRequest.builder()
                .kioskIds(List.of(kiosk.getId())).dispatchOrigin("mixto").build());
        assertThat(onlyMixed.getItems()).extracting(i -> i.getShipment().getId()).containsExactly(mixed.getId());
    }

    @Test
    void paginatesAndKeepsTheTotal() throws Exception {
        LocationEntity kiosk = saveKiosk("PAG");
        ProductEntity p = saveProduct("PAG");
        for (int i = 0; i < 5; i++) {
            withLine(saveShipment(kiosk, "ENV-" + i, null, null, "SENT"), p);
        }
        ShipmentSearchResponse second = searchService.search(ShipmentSearchRequest.builder()
                .kioskIds(List.of(kiosk.getId())).page(1).size(2).order("number").build());
        assertThat(second.getTotal()).isEqualTo(5);
        assertThat(second.getItems()).hasSize(2);
        assertThat(second.getItems().get(0).getShipment().getShipmentNumber()).endsWith("ENV-2");
        assertThat(second.getTotalUnits()).isEqualByComparingTo("5");
    }

    @Test
    void queryCountDoesNotGrowWithTheNumberOfShipments() throws Exception {
        LocationEntity small = saveKiosk("NQ1");
        LocationEntity big = saveKiosk("NQ2");
        ProductDistributionEntity dist = saveDistribution(DistributionOrigin.MANUAL, "Distribución N+1");
        for (int i = 0; i < 2; i++) {
            withLine(saveShipment(small, "ENV-" + i, dist.getId(), null, "DELIVERED"), saveProduct("S" + i));
        }
        for (int i = 0; i < 12; i++) {
            withLine(saveShipment(big, "ENV-" + i, dist.getId(), null, "DELIVERED"), saveProduct("B" + i));
        }

        long smallCount = statementsFor(small);
        long bigCount = statementsFor(big);

        assertThat(smallCount).isPositive();
        assertThat(bigCount).isEqualTo(smallCount);
    }

    private long statementsFor(LocationEntity kiosk) throws Exception {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();
        ShipmentSearchResponse result = searchService.search(ShipmentSearchRequest.builder()
                .kioskIds(List.of(kiosk.getId())).product("producto").build());
        assertThat(result.getItems()).isNotEmpty();
        return stats.getPrepareStatementCount();
    }

    // ------------------------------------------------------------------ datos

    private String provenanceOf(ShipmentSearchResponse result, ProductShipmentEntity shipment) {
        return result.getItems().stream()
                .filter(i -> i.getShipment().getId().equals(shipment.getId()))
                .findFirst().orElseThrow().getProvenance();
    }

    private UserEntity user(String prefix, RoleEntity role) {
        String t = tag();
        return UserEntity.builder()
                .username(prefix + "." + t)
                .email(prefix + "." + t + "@fossiles.test")
                .password("x")
                .status("ACTIVE")
                .firstName(prefix)
                .roles(new HashSet<>(Set.of(role)))
                .build();
    }

    private LocationEntity saveKiosk(String prefix) {
        return locations.save(LocationEntity.builder()
                .code((prefix + tag()).substring(0, 9))
                .name("Kiosko " + prefix)
                .categoria("KIOSKO")
                .departamento("Escuintla")
                .build());
    }

    /** Reutiliza la bodega si otra prueba del mismo contexto ya la creó (las búsquedas toman la primera). */
    private LocationEntity warehouse(String code, String name) {
        if (locationTypes.findAll().stream().noneMatch(t -> code.equals(t.getCode()))) {
            locationTypes.save(InventoryLocationTypeEntity.builder().code(code).name(name).isActive(true).build());
        }
        return locations.findAll().stream().filter(l -> code.equals(l.getCode())).findFirst()
                .orElseGet(() -> locations.save(LocationEntity.builder().code(code).name(name).categoria(code).build()));
    }

    private ProductEntity saveProduct(String code) {
        return products.save(ProductEntity.builder()
                .code(code + "-" + tag())
                .name("Producto " + code)
                .requiresMaterials(false)
                .salePrice(BigDecimal.valueOf(100))
                .build());
    }

    private ProductDistributionEntity saveDistribution(String origin, String description) {
        return distributions.save(ProductDistributionEntity.builder()
                .distributionNumber("DIST-QA-" + tag())
                .distributionDate(LocalDate.now())
                .status("IN_PROGRESS")
                .origin(origin)
                .description(description)
                .build());
    }

    private ProductShipmentEntity saveShipment(LocationEntity kiosk, String suffix, Long distributionId,
                                               Long productionOrderId, String status) {
        return shipments.save(ProductShipmentEntity.builder()
                .shipmentNumber(kiosk.getCode() + "-" + suffix)
                .locationId(kiosk.getId())
                .distributionId(distributionId)
                .productionOrderId(productionOrderId)
                .withoutDistribution(distributionId == null && productionOrderId == null)
                .status(status)
                .createdBy(admin.getId())
                .build());
    }

    private ProductShipmentEntity withLine(ProductShipmentEntity shipment, ProductEntity product) {
        line(shipment, product, "1", null);
        return shipment;
    }

    private void line(ProductShipmentEntity shipment, ProductEntity product, String qty, String received) {
        details.save(ProductShipmentDetailEntity.builder()
                .shipmentId(shipment.getId())
                .productId(product.getId())
                .colorId(tostado.getId())
                .sizeLabel("")
                .quantity(new BigDecimal(qty))
                .quantityReceived(received == null ? null : new BigDecimal(received))
                .build());
    }

    private void out(ProductShipmentEntity shipment, ProductEntity product, LocationEntity location, String qty) {
        kardex.save(ProductInventoryKardex.builder()
                .productId(product.getId())
                .locationId(location.getId())
                .colorId(tostado.getId())
                .sizeLabel("")
                .movementType(ProductInventoryService.MOVEMENT_SHIPMENT)
                .quantity(new BigDecimal(qty))
                .referenceType("SHIPMENT")
                .referenceId(shipment.getId())
                .referenceNumber(shipment.getShipmentNumber())
                .movementDate(LocalDateTime.now())
                .build());
    }

    /** created_at lo fija @PrePersist: se cambia directo en la tabla. */
    private void backdate(ProductShipmentEntity shipment, LocalDateTime createdAt) {
        jdbc.update("UPDATE product_shipment SET created_at = ? WHERE id = ?", createdAt, shipment.getId());
    }

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
