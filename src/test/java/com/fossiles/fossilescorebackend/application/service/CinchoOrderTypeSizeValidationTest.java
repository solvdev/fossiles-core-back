package com.fossiles.fossilescorebackend.application.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fossiles.fossilescorebackend.application.dto.request.ProductionOrderItemRequest;
import com.fossiles.fossilescorebackend.application.dto.request.ProductionOrderRequest;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Validación de tallas de cincho en OPCK, OPV/MARCAS, OPI y en el PUT de edición.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:cincho_order_types;MODE=PostgreSQL;NON_KEYWORDS=YEAR,MONTH;DB_CLOSE_DELAY=-1",
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
class CinchoOrderTypeSizeValidationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final LocalDate ORIGINAL_DATE = LocalDate.of(2026, 3, 1);

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ProductionOrderRepository orders;
    @Autowired private ProductionOrderItemRepository items;
    @Autowired private ProductRepository products;
    @Autowired private ColorRepository colors;

    @MockBean private SecurityUtil securityUtil;

    private ColorEntity negro;

    @BeforeEach
    void setUp() {
        when(securityUtil.getCurrentUserId()).thenReturn(7L);
        negro = colors.save(ColorEntity.builder().name("NEGRO").build());
    }

    static Stream<Arguments> otherOrderTypes() {
        return Stream.of(
                Arguments.of("CLIENTE_KIOSKO", null),
                Arguments.of("MARCAS", "LEVIS"),
                Arguments.of("INTERNA", null));
    }

    @ParameterizedTest
    @MethodSource("otherOrderTypes")
    void beltSizesMatchingQuantityAreAccepted(String orderType, String brand) throws Exception {
        ProductEntity cincho = belt("OK");
        Map<String, Integer> sizes = new LinkedHashMap<>();
        sizes.put(" 32 ", 1);
        sizes.put("xl", 1);
        sizes.put("95", 1);
        String code = code("OK");

        mvc.perform(post("/api/production-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(orderType, code, item(cincho, 3, sizes, brand))))
                .andExpect(status().isCreated());

        ProductionOrderItemEntity saved = lineOf(cincho);
        assertThat(objectMapper.readValue(saved.getSizesData(), new TypeReference<Map<String, Integer>>() {}))
                .containsExactlyInAnyOrderEntriesOf(Map.of("32", 1, "XL", 1, "95", 1));
        assertThat(saved.getQuantity()).isEqualTo(3);
    }

    @ParameterizedTest
    @MethodSource("otherOrderTypes")
    void beltSizesMismatchReturns400(String orderType, String brand) throws Exception {
        ProductEntity cincho = belt("BAD");
        String code = code("BAD");

        mvc.perform(post("/api/production-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(orderType, code, item(cincho, 3, Map.of("32", 1, "36", 1), brand))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "La suma de tallas (2) no coincide con la cantidad (3) para " + cincho.getCode()));
        assertThat(orders.findAll()).noneMatch(order -> code.equals(order.getCode()));
    }

    @ParameterizedTest
    @MethodSource("otherOrderTypes")
    void beltWithoutSizesIsAccepted(String orderType, String brand) throws Exception {
        ProductEntity cincho = belt("NS");
        String code = code("NS");

        mvc.perform(post("/api/production-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(orderType, code, item(cincho, 4, null, brand))))
                .andExpect(status().isCreated());

        ProductionOrderItemEntity saved = lineOf(cincho);
        assertThat(saved.getSizesData()).isNull();
        assertThat(saved.getQuantity()).isEqualTo(4);
    }

    @ParameterizedTest
    @MethodSource("otherOrderTypes")
    void nonBeltWithSizesPersistsRawJson(String orderType, String brand) throws Exception {
        ProductEntity bolso = products.save(ProductEntity.builder()
                .code(code("NB")).name("Bolso").requiresMaterials(false).build());
        String orderCode = code("NB");

        mvc.perform(post("/api/production-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(orderType, orderCode, item(bolso, 5, Map.of("s", 1), brand))))
                .andExpect(status().isCreated());

        ProductionOrderItemEntity saved = lineOf(bolso);
        assertThat(saved.getSizesData()).isEqualTo("{\"s\":1}");
        assertThat(saved.getQuantity()).isEqualTo(5);
    }

    @Test
    void normalBeltWithoutSizesIsAccepted() throws Exception {
        ProductEntity cincho = belt("NN");
        String code = code("NN");

        mvc.perform(post("/api/production-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson("NORMAL", code, item(cincho, 2, null, null))))
                .andExpect(status().isCreated());

        ProductionOrderItemEntity saved = lineOf(cincho);
        assertThat(saved.getSizesData()).isNull();
        assertThat(saved.getQuantity()).isEqualTo(2);
    }

    @Test
    void updateRejectsBeltSizeMismatchAndWritesNothing() throws Exception {
        ProductEntity cincho = belt("PUT");
        ProductionOrderEntity order = orders.save(ProductionOrderEntity.builder()
                .code(code("PUT"))
                .orderType("NORMAL")
                .status("PENDING")
                .observations("ORIGINAL")
                .startDate(ORIGINAL_DATE)
                .deliveryDate(ORIGINAL_DATE)
                .build());
        ProductionOrderItemEntity line = items.save(ProductionOrderItemEntity.builder()
                .productionOrderId(order.getId())
                .productId(cincho.getId())
                .colorId(negro.getId())
                .quantity(3)
                .sizesData("{\"32\":2,\"34\":1}")
                .warehouseReceivedQty(1)
                .observations("vieja")
                .build());

        mvc.perform(put("/api/production-orders/{id}", order.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson("NORMAL", order.getCode(),
                                ProductionOrderItemRequest.builder()
                                        .id(line.getId())
                                        .productId(cincho.getId())
                                        .colorId(negro.getId())
                                        .quantity(3)
                                        .sizes(Map.of("32", 1))
                                        .observations("nueva")
                                        .build())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "La suma de tallas (1) no coincide con la cantidad (3) para " + cincho.getCode()));

        ProductionOrderEntity after = orders.findById(order.getId()).orElseThrow();
        assertThat(after.getObservations()).isEqualTo("ORIGINAL");
        assertThat(after.getDeliveryDate()).isEqualTo(ORIGINAL_DATE);
        ProductionOrderItemEntity saved = items.findById(line.getId()).orElseThrow();
        assertThat(saved.getQuantity()).isEqualTo(3);
        assertThat(saved.getSizesData()).isEqualTo("{\"32\":2,\"34\":1}");
        assertThat(saved.getWarehouseReceivedQty()).isEqualTo(1);
        assertThat(saved.getObservations()).isEqualTo("vieja");
    }

    private ProductEntity belt(String suffix) {
        return products.save(ProductEntity.builder()
                .code(code(suffix))
                .name("Cincho " + suffix)
                .cinchoType("CASUAL")
                .requiresMaterials(false)
                .build());
    }

    private ProductionOrderItemEntity lineOf(ProductEntity product) {
        return items.findAll().stream()
                .filter(line -> product.getId().equals(line.getProductId()))
                .findFirst().orElseThrow();
    }

    private ProductionOrderItemRequest item(
            ProductEntity product, int quantity, Map<String, Integer> sizes, String brand) {
        return ProductionOrderItemRequest.builder()
                .productId(product.getId())
                .colorId(negro.getId())
                .quantity(quantity)
                .sizes(sizes)
                .brandName(brand)
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

    private static String code(String prefix) {
        return prefix + SEQ.incrementAndGet();
    }
}
