package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.KioskCashSessionOpenRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskPosPromotionEstimateRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskPosSaleRequest;
import com.fossiles.fossilescorebackend.application.dto.response.KioskPosContextResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskPosSaleResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoStockEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.RoleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoMovementRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoStockRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSaleItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSaleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.RoleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.KioskPosMode;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * EC-043, EC-044 and EC-045 (tag packaging-rejected): Entrecueros rejects any packaging
 * line with HTTP 400 before a sale, line, stock movement or charge is stored.
 * The standard kiosk still sells packaging at its catalog price.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Transactional
class EntrecuerosPackagingRejectionTest {

    private static final String PACKAGING_CODE = "SUM-BOLSA";
    private static final String PACKAGING_NAME = "Bolsa para cinchos";
    private static final String REJECTION_MESSAGE = "Entrecueros no vende empaque: " + PACKAGING_NAME + ".";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private KioskPosService kioskPosService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private LocationRepository locationRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ColorRepository colorRepository;

    @Autowired
    private KioscoStockRepository kioscoStockRepository;

    @Autowired
    private KioscoMovementRepository movementRepository;

    @Autowired
    private KioskSaleRepository saleRepository;

    @Autowired
    private KioskSaleItemRepository saleItemRepository;

    @MockBean
    private SecurityUtil securityUtil;

    private UserEntity encargada;
    private LocationEntity kiosk;
    private ColorEntity negro;

    @BeforeEach
    void setUp() throws BusinessException {
        RoleEntity encargadaRole = roleRepository.save(RoleEntity.builder().name("ENCARGADA").build());
        encargada = userRepository.save(UserEntity.builder()
                .username("encargada.pack")
                .email("encargada.pack@fossiles.test")
                .password("x")
                .status("ACTIVE")
                .roles(new HashSet<>(Set.of(encargadaRole)))
                .build());

        kiosk = locationRepository.save(LocationEntity.builder()
                .code("KIOSK_PACK")
                .name("Kiosko Empaque")
                .categoria("KIOSKO")
                .encargadoId(encargada.getId())
                .posMode(KioskPosMode.STANDARD)
                .build());

        negro = colorRepository.save(ColorEntity.builder().name("NEGRO").build());

        when(securityUtil.getCurrentUserId()).thenReturn(encargada.getId());
        kioskPosService.openCashSession(KioskCashSessionOpenRequest.builder()
                .kioskLocationId(kiosk.getId())
                .build());
    }

    @Test
    @Tag("packaging-rejected")
    void EC043_sale_packagingOnly_rejected400_persistsNothing() throws Exception {
        enableEntrecueros();
        ProductEntity packaging = savePackaging(PACKAGING_CODE, PACKAGING_NAME, "5.00", 20);
        assertSaleRejected(List.of(line(packaging, 6)));
    }

    @Test
    @Tag("packaging-rejected")
    void EC043_estimate_packagingOnly_rejected400_persistsNothing() throws Exception {
        enableEntrecueros();
        ProductEntity packaging = savePackaging(PACKAGING_CODE, PACKAGING_NAME, "5.00", 20);
        assertEstimateRejected(List.of(line(packaging, 6)));
    }

    @Test
    @Tag("packaging-rejected")
    void EC044_sale_mixedPackagingWalletCasual_rejected400_persistsNothing() throws Exception {
        enableEntrecueros();
        ProductEntity packaging = savePackaging(PACKAGING_CODE, PACKAGING_NAME, "5.00", 20);
        ProductEntity wallet = saveBilletera("BL-EC044", "Billetera caballero");
        ProductEntity casual = saveCasual("FOSS-EC044", "Cincho casual");
        assertSaleRejected(List.of(line(packaging, 6), line(wallet, 1), line(casual, 2)));
    }

    @Test
    @Tag("packaging-rejected")
    void EC044_estimate_mixedPackagingWalletCasual_rejected400_persistsNothing() throws Exception {
        enableEntrecueros();
        ProductEntity packaging = savePackaging(PACKAGING_CODE, PACKAGING_NAME, "5.00", 20);
        ProductEntity wallet = saveBilletera("BL-EC044", "Billetera caballero");
        ProductEntity casual = saveCasual("FOSS-EC044", "Cincho casual");
        assertEstimateRejected(List.of(line(packaging, 6), line(wallet, 1), line(casual, 2)));
    }

    @Test
    @Tag("packaging-rejected")
    void EC045_sale_mixedCasualAndPackaging_rejected400_persistsNothing() throws Exception {
        enableEntrecueros();
        ProductEntity casual = saveCasual("FOSS-EC045", "Cincho casual");
        ProductEntity packaging = savePackaging(PACKAGING_CODE, PACKAGING_NAME, "5.00", 20);
        assertSaleRejected(List.of(line(casual, 6), line(packaging, 2)));
    }

    @Test
    @Tag("packaging-rejected")
    void EC045_estimate_mixedCasualAndPackaging_rejected400_persistsNothing() throws Exception {
        enableEntrecueros();
        ProductEntity casual = saveCasual("FOSS-EC045", "Cincho casual");
        ProductEntity packaging = savePackaging(PACKAGING_CODE, PACKAGING_NAME, "5.00", 20);
        assertEstimateRejected(List.of(line(casual, 6), line(packaging, 2)));
    }

    @Test
    void entrecuerosCatalog_containsNoSumProducts_includingInStock() throws Exception {
        enableEntrecueros();
        ProductEntity inStock = savePackaging("SUM-EC-STK", "Bolsa en stock", "3.00", 20);
        productRepository.save(ProductEntity.builder()
                .code("SUM-EC-CAT")
                .name("Bolsa sin stock")
                .salePrice(new BigDecimal("3.00"))
                .entrecuerosEnabled(true)
                .build());
        productRepository.save(ProductEntity.builder()
                .code("sum-caja")
                .name("Caja empaque")
                .salePrice(new BigDecimal("4.00"))
                .entrecuerosEnabled(true)
                .build());
        seedInventory(productRepository.findAll().stream()
                .filter(product -> "sum-caja".equals(product.getCode()))
                .findFirst()
                .orElseThrow()
                .getId(), 8);
        ProductEntity wallet = saveBilletera("BILL-CAT", "Billetera catalogo");

        KioskPosContextResponse context = kioskPosService.getCurrentContext(kiosk.getId(), null, null, null);

        assertThat(context.getPosMode()).isEqualTo(KioskPosMode.ENTRECUEROS);
        assertThat(context.getInventory())
                .extracting(KioskPosContextResponse.InventoryItem::getProductCode)
                .contains("BILL-CAT")
                .allSatisfy(code -> assertThat(String.valueOf(code).trim().toUpperCase()).doesNotStartWith("SUM"));
        assertThat(currentStock(inStock.getId())).isEqualTo(20);
        assertThat(currentStock(wallet.getId())).isEqualTo(10);
    }

    @Test
    void kioskPos_sellsPackagingAtFullPrice() throws Exception {
        assertThat(kiosk.getId()).isNotEqualTo(KioskPosMode.ENTRECUEROS_LOCATION_ID);
        assertThat(KioskPosMode.isEntrecueros(kiosk)).isFalse();

        ProductEntity packaging = productRepository.save(ProductEntity.builder()
                .code("SUM-POS-FULL")
                .name("Bolsa vendible")
                .salePrice(new BigDecimal("5.00"))
                .discountedPrice(new BigDecimal("1.00"))
                .build());
        seedInventory(packaging.getId(), 5);
        when(securityUtil.getCurrentUserId()).thenReturn(encargada.getId());

        KioskPosContextResponse context = kioskPosService.getCurrentContext(kiosk.getId(), null, null, null);
        assertThat(context.getPosMode()).isEqualTo(KioskPosMode.STANDARD);
        assertThat(context.getInventory())
                .filteredOn(item -> "SUM-POS-FULL".equals(item.getProductCode()))
                .singleElement()
                .extracting(KioskPosContextResponse.InventoryItem::getSuggestedUnitPrice)
                .isEqualTo(new BigDecimal("5.00"));

        KioskPosSaleResponse sale = kioskPosService.createSale(KioskPosSaleRequest.builder()
                .kioskLocationId(kiosk.getId())
                .paymentMethod("EFECTIVO")
                .amountReceived(new BigDecimal("20.00"))
                .items(List.of(item(packaging.getId(), new BigDecimal("2"))))
                .build());

        assertThat(sale.getItems()).hasSize(1);
        assertThat(sale.getItems().get(0).getUnitPrice()).isEqualByComparingTo("5.00");
        assertThat(sale.getDiscountAmount()).isEqualByComparingTo("0.00");
        assertThat(sale.getTotalAmount()).isEqualByComparingTo("10.00");
        assertThat(currentStock(packaging.getId())).isEqualTo(3);
        assertThat(movementRepository.count()).isEqualTo(1);
    }

    private void assertSaleRejected(List<CartLine> lines) throws Exception {
        PersistenceSnapshot before = snapshot(lines);
        mockMvc.perform(post("/api/kiosk-pos/sales")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(saleRequest(lines))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(REJECTION_MESSAGE));
        assertUnchanged(before);
    }

    private void assertEstimateRejected(List<CartLine> lines) throws Exception {
        PersistenceSnapshot before = snapshot(lines);
        mockMvc.perform(post("/api/kiosk-pos/promotions/estimate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(estimateRequest(lines))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(REJECTION_MESSAGE));
        assertUnchanged(before);
    }

    private void assertUnchanged(PersistenceSnapshot before) {
        assertThat(saleRepository.count()).isEqualTo(before.sales());
        assertThat(saleItemRepository.count()).isEqualTo(before.lines());
        assertThat(movementRepository.count()).isEqualTo(before.movements());
        assertThat(chargedTotal()).isEqualByComparingTo(before.charged());
        before.stock().forEach((productId, stock) ->
                assertThat(currentStock(productId)).isEqualTo(stock));
    }

    private PersistenceSnapshot snapshot(List<CartLine> lines) {
        java.util.Map<Long, Integer> stock = new java.util.LinkedHashMap<>();
        for (CartLine line : lines) {
            stock.put(line.product().getId(), currentStock(line.product().getId()));
        }
        return new PersistenceSnapshot(
                saleRepository.count(),
                saleItemRepository.count(),
                movementRepository.count(),
                chargedTotal(),
                stock);
    }

    private BigDecimal chargedTotal() {
        return saleRepository.findAll().stream()
                .map(KioskSaleEntity::getTotalAmount)
                .map(amount -> amount != null ? amount : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private KioskPosSaleRequest saleRequest(List<CartLine> lines) {
        return KioskPosSaleRequest.builder()
                .kioskLocationId(kiosk.getId())
                .paymentMethod("EFECTIVO")
                .amountReceived(new BigDecimal("5000.00"))
                .shippingSheetNumber("1842")
                .requestInvoice(false)
                .items(lines.stream().map(line -> item(line.product().getId(), BigDecimal.valueOf(line.quantity()))).toList())
                .build();
    }

    private KioskPosPromotionEstimateRequest estimateRequest(List<CartLine> lines) {
        return KioskPosPromotionEstimateRequest.builder()
                .kioskLocationId(kiosk.getId())
                .items(lines.stream()
                        .map(line -> KioskPosPromotionEstimateRequest.ItemRequest.builder()
                                .productId(line.product().getId())
                                .quantity(BigDecimal.valueOf(line.quantity()))
                                .build())
                        .toList())
                .build();
    }

    private void enableEntrecueros() {
        kiosk.setPosMode(KioskPosMode.ENTRECUEROS);
        kiosk = locationRepository.save(kiosk);
    }

    private ProductEntity savePackaging(String code, String name, String salePrice, int stock) {
        ProductEntity packaging = productRepository.save(ProductEntity.builder()
                .code(code)
                .name(name)
                .salePrice(new BigDecimal(salePrice))
                .entrecuerosEnabled(true)
                .build());
        seedInventory(packaging.getId(), stock);
        return packaging;
    }

    private ProductEntity saveCasual(String code, String name) {
        ProductEntity casual = productRepository.save(ProductEntity.builder()
                .code(code)
                .name(name)
                .cinchoType("CASUAL")
                .salePrice(new BigDecimal("100.00"))
                .entrecuerosEnabled(true)
                .entrecuerosPriceUnit(new BigDecimal("100.00"))
                .build());
        seedInventory(casual.getId(), 10);
        return casual;
    }

    private ProductEntity saveBilletera(String code, String name) {
        ProductEntity wallet = productRepository.save(ProductEntity.builder()
                .code(code)
                .name(name)
                .salePrice(new BigDecimal("100.00"))
                .entrecuerosEnabled(true)
                .build());
        seedInventory(wallet.getId(), 10);
        return wallet;
    }

    private void seedInventory(Long productId, int quantity) {
        kioscoStockRepository.save(KioscoStockEntity.builder()
                .locationId(kiosk.getId())
                .productId(productId)
                .colorId(negro.getId())
                .currentStock(quantity)
                .build());
    }

    private int currentStock(Long productId) {
        return kioscoStockRepository
                .findByLocationIdAndProductIdAndColorId(kiosk.getId(), productId, negro.getId())
                .map(row -> row.getCurrentStock() != null ? row.getCurrentStock() : 0)
                .orElse(0);
    }

    private KioskPosSaleRequest.ItemRequest item(Long productId, BigDecimal quantity) {
        return KioskPosSaleRequest.ItemRequest.builder()
                .productId(productId)
                .colorId(negro.getId())
                .quantity(quantity)
                .build();
    }

    private static CartLine line(ProductEntity product, int quantity) {
        return new CartLine(product, quantity);
    }

    private record CartLine(ProductEntity product, int quantity) {
    }

    private record PersistenceSnapshot(
            long sales,
            long lines,
            long movements,
            BigDecimal charged,
            java.util.Map<Long, Integer> stock
    ) {
    }
}
