package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.KioskReplenishmentDistributionRequest;
import com.fossiles.fossilescorebackend.application.dto.request.ProductShipmentRequest;
import com.fossiles.fossilescorebackend.application.dto.request.StandaloneKioskShipmentRequest;
import com.fossiles.fossilescorebackend.application.dto.response.ProductDistributionResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ProductShipmentResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.DistributionOrigin;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductDistributionEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductShipmentDetailEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductShipmentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductDistributionRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductShipmentDetailRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductShipmentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Todo envío de reposición a kiosco vive en una distribución: la reposición sugerida, los envíos de OPK y el envío
 * directo. OPCK, Luis Felipe y OPI siguen sin distribución y con su misma numeración.
 */
@SpringBootTest
@ActiveProfiles("test")
class DistributionReplenishmentFlowTest {

    @Autowired private ProductDistributionService distributionService;
    @Autowired private ProductionOrderRepository orders;
    @Autowired private ProductionOrderItemRepository items;
    @Autowired private ProductRepository products;
    @Autowired private ColorRepository colors;
    @Autowired private LocationRepository locations;
    @Autowired private ProductShipmentRepository shipments;
    @Autowired private ProductShipmentDetailRepository details;
    @Autowired private ProductDistributionRepository distributions;

    @MockBean private SecurityUtil securityUtil;

    private ColorEntity color;

    @BeforeEach
    void setUp() {
        when(securityUtil.getCurrentUserId()).thenReturn(22L);
        color = colors.save(ColorEntity.builder().name("QA-REPO-" + tag()).build());
    }

    @Test
    void replenishmentCreatesOneDistributionWithConfirmedAndPendingShipments() throws Exception {
        LocationEntity k1 = saveKiosk("R1");
        LocationEntity k2 = saveKiosk("R2");
        ProductEntity product = saveProduct("REP");

        ProductDistributionResponse dist = distributionService.createReplenishmentDistribution(
                KioskReplenishmentDistributionRequest.builder()
                        .windowDays(60)
                        .kiosks(List.of(
                                kioskLine(k1, product, "3", "2", "1"),
                                kioskLine(k2, product, "1", "1", "0")))
                        .build());

        assertThat(dist.getOrigin()).isEqualTo(DistributionOrigin.REPOSICION);
        assertThat(dist.getReplenishmentWindowDays()).isEqualTo(60);
        assertThat(dist.getDescription()).isEqualTo("Reposición automática — venta de 60 días");
        assertThat(dist.getShipments()).hasSize(3);

        List<ProductShipmentResponse> k1Shipments = dist.getShipments().stream()
                .filter(s -> k1.getId().equals(s.getLocationId())).toList();
        assertThat(k1Shipments).hasSize(2);
        ProductShipmentResponse confirmed = k1Shipments.stream()
                .filter(s -> "CONFIRMED".equals(s.getStatus())).findFirst().orElseThrow();
        ProductShipmentResponse pending = k1Shipments.stream()
                .filter(s -> "DRAFT".equals(s.getStatus())).findFirst().orElseThrow();
        assertThat(confirmed.getPendingProduction()).isFalse();
        assertThat(pending.getPendingProduction()).isTrue();
        assertThat(confirmed.getDistributionOrigin()).isEqualTo(DistributionOrigin.REPOSICION);
        // Numeración de kiosco intacta: correlativo propio del kiosco.
        assertThat(confirmed.getShipmentNumber()).isEqualTo(k1.getCode() + "-ENV-00001");
        assertThat(pending.getShipmentNumber()).isEqualTo(k1.getCode() + "-ENV-00002");

        List<ProductShipmentDetailEntity> confirmedLines = details.findByShipmentId(confirmed.getId());
        assertThat(confirmedLines).hasSize(1);
        assertThat(confirmedLines.get(0).getQuantity()).isEqualByComparingTo("3");
        assertThat(confirmedLines.get(0).getSuggestedQuantity()).isEqualByComparingTo("2");
        assertThat(details.findByShipmentId(pending.getId()).get(0).getQuantity()).isEqualByComparingTo("1");

        // Confirmar el pendiente quita la marca.
        ProductShipmentResponse nowConfirmed = distributionService.confirmShipmentDraft(pending.getId());
        assertThat(nowConfirmed.getPendingProduction()).isFalse();
        assertThat(nowConfirmed.getNotes()).doesNotContain(ProductDistributionService.PENDING_PRODUCTION_TAG);
    }

    @Test
    void replenishmentIsAllOrNothing() throws Exception {
        LocationEntity k1 = saveKiosk("A1");
        LocationEntity notKiosk = locations.save(LocationEntity.builder()
                .code(("B" + tag()).substring(0, 9)).name("Bodega QA").categoria("BODEGA").build());
        ProductEntity product = saveProduct("ATO");
        long before = distributions.count();

        assertThatThrownBy(() -> distributionService.createReplenishmentDistribution(
                KioskReplenishmentDistributionRequest.builder()
                        .kiosks(List.of(kioskLine(k1, product, "2", "2", "0"), kioskLine(notKiosk, product, "1", "1", "0")))
                        .build()))
                .isInstanceOf(BusinessException.class);

        assertThat(distributions.count()).isEqualTo(before);
        assertThat(shipments.findByLocationIdOrderByIdAsc(k1.getId())).isEmpty();
    }

    @Test
    void replenishmentDistributionCannotBeCompletedNorGenerateOp() throws Exception {
        LocationEntity k1 = saveKiosk("C1");
        ProductEntity product = saveProduct("CMP");
        ProductDistributionResponse dist = distributionService.createReplenishmentDistribution(
                KioskReplenishmentDistributionRequest.builder()
                        .kiosks(List.of(kioskLine(k1, product, "1", "1", "0"))).build());

        assertThatThrownBy(() -> distributionService.completeDistribution(dist.getId(), true))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("OPK sugerida");
    }

    @Test
    void opkShipmentsLiveInOneDistributionLinkedToTheOrder() throws Exception {
        LocationEntity k1 = saveKiosk("K1");
        LocationEntity k2 = saveKiosk("K2");
        ProductEntity product = saveProduct("OPK");
        ProductionOrderEntity opk = saveOrder("OPK", "NORMAL", "Bodega", "KIOSCOS");
        saveItem(opk, product, 4);

        ProductShipmentResponse first = distributionService.createOrUpdateShipmentForProductionOrder(
                opk.getId(), kioskRequest(k1, product));
        ProductShipmentResponse second = distributionService.createOrUpdateShipmentForProductionOrder(
                opk.getId(), kioskRequest(k2, product));

        ProductionOrderEntity reloaded = orders.findById(opk.getId()).orElseThrow();
        assertThat(reloaded.getDistributionId()).isNotNull();
        assertThat(first.getDistributionId()).isEqualTo(reloaded.getDistributionId());
        assertThat(second.getDistributionId()).isEqualTo(reloaded.getDistributionId());
        assertThat(first.getProductionOrderId()).isEqualTo(opk.getId());
        assertThat(first.getDistributionOrigin()).isEqualTo(DistributionOrigin.MANUAL);
        assertThat(first.getShipmentNumber()).isEqualTo(k1.getCode() + "-ENV-00001");
        assertThat(second.getShipmentNumber()).isEqualTo(k2.getCode() + "-ENV-00001");

        ProductDistributionEntity dist = distributions.findById(reloaded.getDistributionId()).orElseThrow();
        assertThat(dist.getDescription()).isEqualTo("Envíos de " + opk.getCode());

        // Editar el envío de la OPK desde la OP sigue funcionando aunque viva en una distribución.
        ProductShipmentRequest edit = kioskRequest(k1, product);
        edit.setShipmentId(first.getId());
        ProductShipmentResponse edited = distributionService.createOrUpdateShipmentForProductionOrder(opk.getId(), edit);
        assertThat(edited.getId()).isEqualTo(first.getId());
        // La vista de la OP muestra solo sus envíos.
        assertThat(distributionService.getShipmentsLinkedToProductionOrder(opk.getId())).hasSize(2);
    }

    @Test
    void opkFromReplenishmentFulfillsThePendingShipmentKeepingItsNumber() throws Exception {
        LocationEntity k1 = saveKiosk("P1");
        ProductEntity product = saveProduct("PND");
        ProductDistributionResponse dist = distributionService.createReplenishmentDistribution(
                KioskReplenishmentDistributionRequest.builder()
                        .kiosks(List.of(kioskLine(k1, product, "0", "2", "2"))).build());
        ProductShipmentResponse pending = dist.getShipments().get(0);
        assertThat(pending.getPendingProduction()).isTrue();

        ProductionOrderEntity opk = saveOrder("OPK", "NORMAL", "Bodega", "KIOSCOS");
        opk.setDistributionId(dist.getId());
        opk = orders.save(opk);
        saveItem(opk, product, 2);
        ProductionOrderEntity opc = saveOrder("OPC", "CINCHOS_FOSSILES", "Bodega", "KIOSCOS");
        opc.setDistributionId(dist.getId());
        orders.save(opc);

        ProductShipmentResponse fromOp = distributionService.createOrUpdateShipmentForProductionOrder(
                opk.getId(), kioskRequest(k1, product));

        assertThat(fromOp.getId()).isEqualTo(pending.getId());
        assertThat(fromOp.getShipmentNumber()).isEqualTo(pending.getShipmentNumber());
        assertThat(fromOp.getProductionOrderId()).isEqualTo(opk.getId());
        assertThat(fromOp.getDistributionId()).isEqualTo(dist.getId());
        assertThat(fromOp.getPendingProduction()).isFalse();

        // Dos OP ligadas a la misma distribución no rompen la consulta.
        ProductDistributionResponse reloaded = distributionService.getDistributionById(dist.getId());
        assertThat(reloaded.getLinkedProductionOrders()).hasSize(2);
        assertThat(reloaded.getProductionOrderId()).isEqualTo(opk.getId());
    }

    @Test
    void ordersOutsideKioskReplenishmentStayWithoutDistribution() throws Exception {
        LocationEntity k1 = saveKiosk("X1");
        ProductEntity product = saveProduct("OTH");

        ProductionOrderEntity opck = saveOrder("OPCK", "CLIENTE_KIOSKO", "Bodega", "Cliente especial");
        saveItem(opck, product, 1);
        ProductShipmentResponse opckShipment = distributionService.createOrUpdateShipmentForProductionOrder(
                opck.getId(), kioskRequest(k1, product));
        assertThat(opckShipment.getDistributionId()).isNull();
        assertThat(opckShipment.getDistributionOrigin()).isNull();
        assertThat(orders.findById(opck.getId()).orElseThrow().getDistributionId()).isNull();

        ProductionOrderEntity lf = saveOrder("OPV-LF", "MARCAS", "Luis Felipe", "Cliente QA");
        saveItem(lf, product, 3);
        ProductShipmentResponse lfShipment = distributionService.createOrUpdateShipmentForProductionOrder(
                lf.getId(), addressRequest(product));
        assertThat(lfShipment.getDistributionId()).isNull();
        assertThat(lfShipment.getShipmentNumber()).matches("^ENVP-\\d{5}$");

        ProductionOrderEntity opi = saveOrder("OPI-IN", "INTERNA", "Bodega", "Colaborador");
        saveItem(opi, product, 2);
        ProductShipmentResponse opiShipment = distributionService.createOrUpdateShipmentForProductionOrder(
                opi.getId(), addressRequest(product));
        assertThat(opiShipment.getDistributionId()).isNull();
        assertThat(opiShipment.getShipmentNumber()).matches("^ENVI-\\d{5}$");
    }

    @Test
    void directKioskShipmentIsWrappedInAManualDistributionAndStillListed() throws Exception {
        LocationEntity k1 = saveKiosk("D1");
        ProductEntity product = saveProduct("DIR");

        ProductShipmentResponse direct = distributionService.createStandaloneKioskShipment(
                StandaloneKioskShipmentRequest.builder()
                        .locationId(k1.getId())
                        .products(List.of(line(product, "1")))
                        .build());

        assertThat(direct.getDistributionId()).isNotNull();
        assertThat(direct.getDistributionOrigin()).isEqualTo(DistributionOrigin.MANUAL);
        assertThat(direct.getStatus()).isEqualTo("CONFIRMED");
        assertThat(direct.getShipmentNumber()).isEqualTo(k1.getCode() + "-ENV-00001");
        ProductDistributionEntity dist = distributions.findById(direct.getDistributionId()).orElseThrow();
        assertThat(dist.getDescription()).startsWith(ProductDistributionService.DIRECT_KIOSK_DISTRIBUTION_DESCRIPTION);
        assertThat(distributionService.listStandaloneKioskShipments())
                .extracting(ProductShipmentResponse::getId)
                .contains(direct.getId());
    }

    @Test
    void distributionNumberDoesNotCollideAfterDeletingOne() throws Exception {
        ProductDistributionResponse a = distributionService.createDistribution(
                com.fossiles.fossilescorebackend.application.dto.request.ProductDistributionRequest.builder()
                        .distributionDate(LocalDate.now()).build());
        ProductDistributionResponse b = distributionService.createDistribution(
                com.fossiles.fossilescorebackend.application.dto.request.ProductDistributionRequest.builder()
                        .distributionDate(LocalDate.now()).build());
        distributionService.deleteDistribution(a.getId());
        ProductDistributionResponse c = distributionService.createDistribution(
                com.fossiles.fossilescorebackend.application.dto.request.ProductDistributionRequest.builder()
                        .distributionDate(LocalDate.now()).build());

        assertThat(c.getDistributionNumber()).isNotEqualTo(b.getDistributionNumber());
        assertThat(c.getOrigin()).isEqualTo(DistributionOrigin.MANUAL);
    }

    @Test
    void nextDistributionSequenceUsesTheHighestTrailingNumber() {
        assertThat(ProductDistributionService.nextDistributionSequence(List.of())).isEqualTo(1);
        assertThat(ProductDistributionService.nextDistributionSequence(
                List.of("DIST-20261001-007", "DIST-20261102-1203", "OTRO-1", "DIST-20260101-099")))
                .isEqualTo(1204);
    }

    @Test
    void pendingProductionTagIsStrippedFromNotes() {
        assertThat(ProductDistributionService.stripPendingProductionTag(
                "Reposición\n" + ProductDistributionService.PENDING_PRODUCTION_TAG)).isEqualTo("Reposición");
        assertThat(ProductDistributionService.stripPendingProductionTag(
                ProductDistributionService.PENDING_PRODUCTION_TAG)).isNull();
        assertThat(ProductDistributionService.isPendingProductionShipment(ProductShipmentEntity.builder()
                .status("CONFIRMED").notes(ProductDistributionService.PENDING_PRODUCTION_TAG).build())).isFalse();
    }

    private KioskReplenishmentDistributionRequest.Kiosk kioskLine(
            LocationEntity kiosk, ProductEntity product, String qty, String suggested, String shortage) {
        return KioskReplenishmentDistributionRequest.Kiosk.builder()
                .locationId(kiosk.getId())
                .lines(List.of(KioskReplenishmentDistributionRequest.Line.builder()
                        .productId(product.getId())
                        .colorId(color.getId())
                        .size("")
                        .quantity(new BigDecimal(qty))
                        .suggestedQuantity(new BigDecimal(suggested))
                        .shortage(new BigDecimal(shortage))
                        .build()))
                .build();
    }

    private ProductShipmentRequest kioskRequest(LocationEntity kiosk, ProductEntity product) {
        return ProductShipmentRequest.builder()
                .locationId(kiosk.getId())
                .products(List.of(line(product, "1")))
                .build();
    }

    private ProductShipmentRequest addressRequest(ProductEntity product) {
        return ProductShipmentRequest.builder()
                .destinationAddress("Ciudad")
                .products(List.of(line(product, "1")))
                .build();
    }

    private ProductShipmentRequest.ProductShipmentDetailRequest line(ProductEntity product, String qty) {
        return ProductShipmentRequest.ProductShipmentDetailRequest.builder()
                .productId(product.getId())
                .colorId(color.getId())
                .quantity(new BigDecimal(qty))
                .build();
    }

    private LocationEntity saveKiosk(String prefix) {
        return locations.save(LocationEntity.builder()
                .code((prefix + tag()).substring(0, 9))
                .name("Kiosko " + prefix)
                .categoria("KIOSKO")
                .build());
    }

    private ProductEntity saveProduct(String code) {
        return products.save(ProductEntity.builder()
                .code(code + "-" + tag())
                .name("Producto " + code)
                .requiresMaterials(false)
                .salePrice(BigDecimal.valueOf(100))
                .build());
    }

    private ProductionOrderEntity saveOrder(String code, String orderType, String sellerName, String customerName) {
        return orders.save(ProductionOrderEntity.builder()
                .code(code + "-" + tag())
                .orderType(orderType)
                .status("IN_PROGRESS")
                .sellerName(sellerName)
                .customerName(customerName)
                .startDate(LocalDate.now())
                .build());
    }

    private ProductionOrderItemEntity saveItem(ProductionOrderEntity order, ProductEntity product, int quantity) {
        return items.save(ProductionOrderItemEntity.builder()
                .productionOrderId(order.getId())
                .productId(product.getId())
                .colorId(color.getId())
                .quantity(quantity)
                .warehouseReceivedQty(0)
                .unitPrice(BigDecimal.valueOf(100))
                .build());
    }

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
