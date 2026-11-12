package com.fossiles.fossilescorebackend.application.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fossiles.fossilescorebackend.application.dto.request.ProductionOrderItemRequest;
import com.fossiles.fossilescorebackend.application.dto.request.ProductionOrderRequest;
import com.fossiles.fossilescorebackend.application.dto.request.WarehouseUnitReceiptRequest;
import com.fossiles.fossilescorebackend.application.dto.response.WarehouseWorkspaceResponse;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.InventoryLocationTypeEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderWarehouseUnitEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.InventoryLocationTypeRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineSaleItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineSaleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderWarehouseUnitRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tallas de cincho en OPL, recepción de PT y validación de la suma en un tipo que no es OPC.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:cincho_opl_sizes;MODE=PostgreSQL;NON_KEYWORDS=YEAR,MONTH;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "sentry.enabled=false",
        "sentry.dsn=",
        "spring.task.scheduling.enabled=false"
})
@AutoConfigureMockMvc(addFilters = false)
@Transactional
class CinchoOplSizeDataTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private OnlineSaleProductionOrderService oplService;
    @Autowired private ProductionOrderWarehouseUnitService warehouseUnitService;
    @Autowired private ProductionOrderRepository orders;
    @Autowired private ProductionOrderItemRepository items;
    @Autowired private ProductionOrderWarehouseUnitRepository units;
    @Autowired private ProductRepository products;
    @Autowired private ColorRepository colors;
    @Autowired private OnlineSaleRepository sales;
    @Autowired private OnlineSaleItemRepository saleItems;
    @Autowired private InventoryLocationTypeRepository locationTypes;
    @Autowired private LocationRepository locations;

    @MockBean private SecurityUtil securityUtil;

    private ColorEntity negro;
    private ColorEntity cafe;

    @BeforeEach
    void setUp() {
        when(securityUtil.getCurrentUserId()).thenReturn(7L);
        negro = colors.save(ColorEntity.builder().name("NEGRO").build());
        cafe = colors.save(ColorEntity.builder().name("CAFE").build());
        locationTypes.save(InventoryLocationTypeEntity.builder()
                .code("BODEGA_PT").name("Bodega PT").isActive(true).build());
        locations.save(LocationEntity.builder()
                .code("BODEGA_PT").name("Bodega Producto Terminado").categoria("BODEGA_PT").build());
    }

    @Test
    void oplCreationWritesSizesDataAndAggregatesRepeatedSizes() throws Exception {
        ProductEntity cincho = products.save(ProductEntity.builder()
                .code("CIN-OPL-01").name("Cincho clasico").cinchoType("CASUAL").requiresMaterials(false).build());
        ProductEntity bolso = products.save(ProductEntity.builder()
                .code("BOL-OPL-01").name("Bolso").requiresMaterials(false).build());
        OnlineSaleEntity sale = sales.save(OnlineSaleEntity.builder()
                .saleNumber("V-OPL-SIZES")
                .customerName("Cliente tallas")
                .status("PENDIENTE")
                .paymentMethod("TARJETA_PAGADO")
                .saleDate(LocalDate.now())
                .observations("Nota venta")
                .inProductionOrder(false)
                .build());
        saleLine(sale, cincho, negro, " 34 ", 2);
        saleLine(sale, cincho, negro, "34", 1);
        saleLine(sale, cincho, negro, "36", 1);
        saleLine(sale, cincho, negro, "m", 1);
        saleLine(sale, cincho, negro, "M", 1);
        OnlineSaleItemEntity cafeLine = saleLine(sale, cincho, cafe, "32", 1);
        saleLine(sale, bolso, negro, null, 1);

        oplService.createOplFromSales(List.of(sale.getId()));

        List<ProductionOrderItemEntity> lines = items.findAll().stream()
                .filter(line -> cincho.getId().equals(line.getProductId()) || bolso.getId().equals(line.getProductId()))
                .toList();
        assertThat(lines).hasSize(3);

        ProductionOrderItemEntity merged = lines.stream()
                .filter(line -> cincho.getId().equals(line.getProductId()) && negro.getId().equals(line.getColorId()))
                .findFirst().orElseThrow();
        assertThat(merged.getQuantity()).isEqualTo(6);
        assertThat(merged.getOnlineSaleItemId()).isNull();
        assertThat(merged.getOnlineSaleId()).isEqualTo(sale.getId());
        Map<String, Integer> sizes = objectMapper.readValue(merged.getSizesData(), new TypeReference<>() {});
        assertThat(sizes).containsExactlyInAnyOrderEntriesOf(Map.of("34", 3, "36", 1, "M", 2));
        assertThat(sizes.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(merged.getQuantity());
        assertThat(merged.getObservations()).contains("Talla: 34", "Talla: 36", "Talla: m", "Talla: M", "Nota venta");

        ProductionOrderItemEntity single = lines.stream()
                .filter(line -> cafe.getId().equals(line.getColorId()))
                .findFirst().orElseThrow();
        assertThat(single.getQuantity()).isEqualTo(1);
        assertThat(single.getOnlineSaleItemId()).isEqualTo(cafeLine.getId());
        assertThat(objectMapper.readValue(single.getSizesData(), new TypeReference<Map<String, Integer>>() {}))
                .containsExactlyInAnyOrderEntriesOf(Map.of("32", 1));
        assertThat(single.getObservations()).isEqualTo(
                "Venta #V-OPL-SIZES - Cliente tallas | Talla: 32 | Obs: Nota venta");

        ProductionOrderItemEntity other = lines.stream()
                .filter(line -> bolso.getId().equals(line.getProductId()))
                .findFirst().orElseThrow();
        assertThat(other.getSizesData()).isNull();
        assertThat(other.getQuantity()).isEqualTo(1);
        assertThat(other.getObservations()).isEqualTo("Venta #V-OPL-SIZES - Cliente tallas | Obs: Nota venta");
    }

    @Test
    void receiptResolvesSizeByOnlineSaleItemWhenSameBeltAndColor() throws Exception {
        ProductEntity cincho = products.save(ProductEntity.builder()
                .code("FOSS-CIN-RC").name("Cincho foss").cinchoType("CASUAL").requiresMaterials(false).build());
        OnlineSaleEntity sale = sales.save(OnlineSaleEntity.builder()
                .saleNumber("V-OPL-RC")
                .customerName("Cliente recepcion")
                .status("EN_PRODUCCION")
                .paymentMethod("TARJETA_PAGADO")
                .saleDate(LocalDate.now())
                .inProductionOrder(true)
                .build());
        OnlineSaleItemEntity size34 = saleLine(sale, cincho, negro, " 34 ", 1);
        OnlineSaleItemEntity size36 = saleLine(sale, cincho, negro, "36", 1);
        ProductionOrderEntity order = orders.save(ProductionOrderEntity.builder()
                .code("OPL-RC-1")
                .orderType("VENTA_EN_LINEA")
                .status("IN_PROGRESS")
                .customerName(sale.getCustomerName())
                .startDate(LocalDate.now())
                .build());
        ProductionOrderItemEntity item34 = poItem(order, sale, size34, cincho, negro);
        ProductionOrderItemEntity item36 = poItem(order, sale, size36, cincho, negro);

        WarehouseWorkspaceResponse workspace = warehouseUnitService.getWorkspace(order.getId());
        assertThat(workspace.getUnits()).hasSize(2);
        assertThat(workspace.getUnits()).allMatch(unit -> unit.getSizeKey() == null || unit.getSizeKey().isBlank());

        warehouseUnitService.updateUnitsReceipt(order.getId(), WarehouseUnitReceiptRequest.builder()
                .units(workspace.getUnits().stream()
                        .map(unit -> WarehouseUnitReceiptRequest.UnitUpdate.builder()
                                .unitId(unit.getId())
                                .receiptStatus("RECEIVED")
                                .build())
                        .toList())
                .build());

        Map<Long, String> sizeByItem = new LinkedHashMap<>();
        for (ProductionOrderWarehouseUnitEntity unit : units
                .findByProductionOrderIdOrderByProductionOrderItemIdAscSizeKeyAscUnitSeqAsc(order.getId())) {
            sizeByItem.put(unit.getProductionOrderItemId(), unit.getSizeKey());
        }
        assertThat(sizeByItem).containsEntry(item34.getId(), "34").containsEntry(item36.getId(), "36");
    }

    @Test
    void normalOrderRejectsBeltSizesThatDoNotMatchQuantity() throws Exception {
        ProductEntity cincho = products.save(ProductEntity.builder()
                .code("CIN-VAL-01").name("Cincho validacion").cinchoType("CASUAL").requiresMaterials(false).build());

        mvc.perform(post("/api/production-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson("NORMAL", "OPK-VAL-BAD", item(cincho, negro, 3, Map.of("34", 2, "36", 2)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "La suma de tallas (4) no coincide con la cantidad (3) para CIN-VAL-01"));
        assertThat(orders.findAll()).noneMatch(order -> "OPK-VAL-BAD".equals(order.getCode()));
    }

    @Test
    void normalOrderPersistsBeltSizesAndLeavesNonBeltSizesUntouched() throws Exception {
        ProductEntity cincho = products.save(ProductEntity.builder()
                .code("CIN-VAL-02").name("Cincho validacion").cinchoType("CASUAL").requiresMaterials(false).build());
        ProductEntity bolso = products.save(ProductEntity.builder()
                .code("BOL-VAL-01").name("Bolso validacion").requiresMaterials(false).build());

        Map<String, Integer> sizes = new LinkedHashMap<>();
        sizes.put(" 34 ", 1);
        sizes.put("34", 1);
        sizes.put("m", 1);
        mvc.perform(post("/api/production-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson("NORMAL", "OPK-VAL-OK", item(cincho, negro, 3, sizes))))
                .andExpect(status().isCreated());

        ProductionOrderItemEntity saved = items.findAll().stream()
                .filter(line -> cincho.getId().equals(line.getProductId()))
                .findFirst().orElseThrow();
        assertThat(objectMapper.readValue(saved.getSizesData(), new TypeReference<Map<String, Integer>>() {}))
                .containsExactlyInAnyOrderEntriesOf(Map.of("34", 2, "M", 1));
        assertThat(saved.getQuantity()).isEqualTo(3);

        mvc.perform(post("/api/production-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson("NORMAL", "OPK-VAL-NB", item(bolso, negro, 5, Map.of("s", 1)))))
                .andExpect(status().isCreated());
        ProductionOrderItemEntity nonBelt = items.findAll().stream()
                .filter(line -> bolso.getId().equals(line.getProductId()))
                .findFirst().orElseThrow();
        assertThat(nonBelt.getSizesData()).isEqualTo("{\"s\":1}");
        assertThat(nonBelt.getQuantity()).isEqualTo(5);
    }

    private OnlineSaleItemEntity saleLine(
            OnlineSaleEntity sale, ProductEntity product, ColorEntity color, String size, int quantity) {
        return saleItems.save(OnlineSaleItemEntity.builder()
                .onlineSaleId(sale.getId())
                .productId(product.getId())
                .productCode(product.getCode())
                .productName(product.getName())
                .colorId(color.getId())
                .colorName(color.getName())
                .size(size)
                .quantity(quantity)
                .unitPrice(BigDecimal.TEN)
                .build());
    }

    private ProductionOrderItemEntity poItem(
            ProductionOrderEntity order,
            OnlineSaleEntity sale,
            OnlineSaleItemEntity saleItem,
            ProductEntity product,
            ColorEntity color) {
        return items.save(ProductionOrderItemEntity.builder()
                .productionOrderId(order.getId())
                .onlineSaleId(sale.getId())
                .onlineSaleItemId(saleItem.getId())
                .productId(product.getId())
                .colorId(color.getId())
                .quantity(1)
                .warehouseReceivedQty(0)
                .build());
    }

    private ProductionOrderItemRequest item(
            ProductEntity product, ColorEntity color, int quantity, Map<String, Integer> sizes) {
        return ProductionOrderItemRequest.builder()
                .productId(product.getId())
                .colorId(color.getId())
                .quantity(quantity)
                .sizes(sizes)
                .build();
    }

    private String orderJson(String orderType, String code, ProductionOrderItemRequest item) throws Exception {
        return objectMapper.writeValueAsString(ProductionOrderRequest.builder()
                .code(code)
                .orderType(orderType)
                .customerName("Taller")
                .startDate(LocalDate.now())
                .items(List.of(item))
                .build());
    }
}
