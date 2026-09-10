package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.OpcShipmentGenerateRequest;
import com.fossiles.fossilescorebackend.application.dto.request.PartialReleaseLineRequest;
import com.fossiles.fossilescorebackend.application.dto.request.PartialReleaseUpsertRequest;
import com.fossiles.fossilescorebackend.application.dto.response.PartialReleaseResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ProductShipmentDetailResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ProductShipmentResponse;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductShipmentDetailRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ProductionOrderPartialReleaseShipmentTest {

    @Autowired
    private ProductionOrderPartialReleaseService partialReleaseService;

    @Autowired
    private ProductionOrderRepository productionOrderRepository;

    @Autowired
    private ProductionOrderItemRepository productionOrderItemRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ColorRepository colorRepository;

    @Autowired
    private ProductShipmentDetailRepository shipmentDetailRepository;

    @MockBean
    private SecurityUtil securityUtil;

    private ColorEntity color;

    @BeforeEach
    void setUp() {
        when(securityUtil.getCurrentUserId()).thenReturn(9L);
        color = colorRepository.save(ColorEntity.builder().name("NEGRO-PR").build());
    }

    @Test
    void generateOpvPartialShipment_persistsOnlyReleasedQuantity() throws Exception {
        ProductEntity included = saveProduct("PR-OPV-A");
        ProductEntity omitted = saveProduct("PR-OPV-B");
        ProductionOrderEntity order = saveOrder("OPV-PR-01", "MARCAS", "Luis Felipe");
        ProductionOrderItemEntity itemA = saveItem(order, included, 10, null);
        saveItem(order, omitted, 5, null);

        PartialReleaseResponse release = partialReleaseService.createDraft(order.getId(),
                PartialReleaseUpsertRequest.builder()
                        .status("CONFIRMED")
                        .label("Parcial 1")
                        .lines(List.of(PartialReleaseLineRequest.builder()
                                .productionOrderItemId(itemA.getId())
                                .quantity(3)
                                .build()))
                        .build());

        ProductShipmentResponse shipment = partialReleaseService.generateShipment(
                release.getId(),
                OpcShipmentGenerateRequest.builder().destinationAddress("Ciudad").build());

        assertThat(shipment.getPartialReleaseId()).isEqualTo(release.getId());
        assertThat(shipment.getProducts()).hasSize(1);
        ProductShipmentDetailResponse line = shipment.getProducts().get(0);
        assertThat(line.getProductId()).isEqualTo(included.getId());
        assertThat(line.getQuantity()).isEqualByComparingTo("3");

        BigDecimal persistedQty = shipmentDetailRepository.findByShipmentId(shipment.getId()).stream()
                .map(d -> d.getQuantity() == null ? BigDecimal.ZERO : d.getQuantity())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(persistedQty).isEqualByComparingTo("3");
    }

    @Test
    void generateOpcPartialShipment_persistsOnlyReleasedSizes() throws Exception {
        ProductEntity cincho = saveProduct("PR-OPC-A");
        ProductEntity other = saveProduct("PR-OPC-B");
        ProductionOrderEntity order = saveOrder("OPC-PR-01", "CINCHOS", "Tienda");
        ProductionOrderItemEntity itemA = saveItem(order, cincho, 10, "{\"38\":4,\"39\":6}");
        saveItem(order, other, 5, null);

        PartialReleaseResponse release = partialReleaseService.createDraft(order.getId(),
                PartialReleaseUpsertRequest.builder()
                        .status("CONFIRMED")
                        .label("Parcial 1")
                        .lines(List.of(PartialReleaseLineRequest.builder()
                                .productionOrderItemId(itemA.getId())
                                .sizes(Map.of("38", 2))
                                .build()))
                        .build());

        ProductShipmentResponse shipment = partialReleaseService.generateShipment(
                release.getId(),
                OpcShipmentGenerateRequest.builder().destinationAddress("Bodega cliente").build());

        assertThat(shipment.getPartialReleaseId()).isEqualTo(release.getId());
        assertThat(shipment.getProducts()).hasSize(1);
        ProductShipmentDetailResponse line = shipment.getProducts().get(0);
        assertThat(line.getProductId()).isEqualTo(cincho.getId());
        assertThat(line.getSize()).isEqualToIgnoringCase("38");
        assertThat(line.getQuantity()).isEqualByComparingTo("2");
    }

    @Test
    void generateEntreCuerosPartialShipment_doesNotCopyFullOrderQty() throws Exception {
        ProductEntity product = saveProduct("PR-EC-A");
        ProductionOrderEntity order = saveOrder("OPV-PR-EC", "MARCAS", "Vendedor");
        order.setCustomerName("Entre Cueros");
        order = productionOrderRepository.save(order);
        ProductionOrderItemEntity item = saveItem(order, product, 8, null);

        PartialReleaseResponse release = partialReleaseService.createDraft(order.getId(),
                PartialReleaseUpsertRequest.builder()
                        .status("CONFIRMED")
                        .label("Parcial EC")
                        .lines(List.of(PartialReleaseLineRequest.builder()
                                .productionOrderItemId(item.getId())
                                .quantity(2)
                                .build()))
                        .build());

        ProductShipmentResponse shipment = partialReleaseService.generateShipment(
                release.getId(),
                OpcShipmentGenerateRequest.builder().destinationAddress("Showroom").build());

        assertThat(shipment.getProducts()).hasSize(1);
        assertThat(shipment.getProducts().get(0).getQuantity()).isEqualByComparingTo("2");
    }

    private ProductionOrderEntity saveOrder(String code, String orderType, String sellerName) {
        return productionOrderRepository.save(ProductionOrderEntity.builder()
                .code(code)
                .orderType(orderType)
                .status("IN_PROGRESS")
                .sellerName(sellerName)
                .customerName("Cliente PR")
                .startDate(LocalDate.now())
                .build());
    }

    private ProductionOrderItemEntity saveItem(
            ProductionOrderEntity order, ProductEntity product, int quantity, String sizesData) {
        return productionOrderItemRepository.save(ProductionOrderItemEntity.builder()
                .productionOrderId(order.getId())
                .productId(product.getId())
                .colorId(color.getId())
                .quantity(quantity)
                .sizesData(sizesData)
                .warehouseReceivedQty(0)
                .unitPrice(BigDecimal.valueOf(100))
                .build());
    }

    private ProductEntity saveProduct(String code) {
        return productRepository.save(ProductEntity.builder()
                .code(code)
                .name("Producto " + code)
                .requiresMaterials(false)
                .salePrice(BigDecimal.valueOf(100))
                .build());
    }
}
