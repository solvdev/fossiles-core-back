package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.KioskCashSessionOpenRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskPosPromotionEstimateRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskPosSaleRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskPromotionRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskPromotionTierRequest;
import com.fossiles.fossilescorebackend.application.dto.response.KioskPosReportsResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskPosSaleResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoStockEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskPromotionEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskPromotionTierEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductCategoryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductInventoryLocation;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.RoleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoStockRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskPromotionRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSaleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductInventoryLocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductCategoryRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.RoleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class KioskPosServiceTest {

    @Autowired
    private KioskPosService kioskPosService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private LocationRepository locationRepository;

    @Autowired
    private ProductCategoryRepository productCategoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ColorRepository colorRepository;

    @Autowired
    private ProductInventoryLocationRepository inventoryRepository;

    @Autowired
    private KioscoStockRepository kioscoStockRepository;

    @Autowired
    private KioskPromotionRepository promotionRepository;

    @Autowired
    private KioskSaleRepository saleRepository;

    @MockBean
    private SecurityUtil securityUtil;

    private UserEntity encargada;
    private UserEntity admin;
    private LocationEntity kioskA;
    private LocationEntity kioskB;
    private ProductEntity wallet;
    private ProductCategoryEntity billeterasCategory;
    private ColorEntity negro;

    @BeforeEach
    void setUp() throws BusinessException {
        RoleEntity encargadaRole = roleRepository.save(RoleEntity.builder().name("ENCARGADA").build());
        RoleEntity adminRole = roleRepository.save(RoleEntity.builder().name("ADMIN").build());

        encargada = userRepository.save(UserEntity.builder()
                .username("encargada.pos")
                .email("encargada@fossiles.test")
                .password("x")
                .status("ACTIVE")
                .roles(new HashSet<>(Set.of(encargadaRole)))
                .build());

        admin = userRepository.save(UserEntity.builder()
                .username("admin.pos")
                .email("admin@fossiles.test")
                .password("x")
                .status("ACTIVE")
                .roles(new HashSet<>(Set.of(adminRole)))
                .build());

        kioskA = locationRepository.save(LocationEntity.builder()
                .code("KIOSK_A")
                .name("Kiosko A")
                .categoria("KIOSKO")
                .encargadoId(encargada.getId())
                .build());

        kioskB = locationRepository.save(LocationEntity.builder()
                .code("KIOSK_B")
                .name("Kiosko B")
                .categoria("KIOSKO")
                .encargadoId(admin.getId())
                .build());

        wallet = productRepository.save(ProductEntity.builder()
                .code("BILL-001")
                .name("Billetera Clasica")
                .salePrice(new BigDecimal("250.00"))
                .build());

        billeterasCategory = productCategoryRepository.save(ProductCategoryEntity.builder()
                .code("BILL")
                .name("Billeteras")
                .build());
        wallet.setCategoryId(billeterasCategory.getId());
        wallet = productRepository.save(wallet);

        negro = colorRepository.save(ColorEntity.builder().name("NEGRO").build());

        inventoryRepository.save(ProductInventoryLocation.builder()
                .productId(wallet.getId())
                .locationId(kioskA.getId())
                .colorId(negro.getId())
                .quantity(new BigDecimal("5"))
                .build());

        inventoryRepository.save(ProductInventoryLocation.builder()
                .productId(wallet.getId())
                .locationId(kioskB.getId())
                .colorId(negro.getId())
                .quantity(new BigDecimal("3"))
                .build());

        kioscoStockRepository.save(KioscoStockEntity.builder()
                .locationId(kioskA.getId())
                .productId(wallet.getId())
                .colorId(negro.getId())
                .currentStock(5)
                .build());

        kioscoStockRepository.save(KioscoStockEntity.builder()
                .locationId(kioskB.getId())
                .productId(wallet.getId())
                .colorId(negro.getId())
                .currentStock(3)
                .build());

        when(securityUtil.getCurrentUserId()).thenReturn(encargada.getId());
        kioskPosService.openCashSession(KioskCashSessionOpenRequest.builder()
                .kioskLocationId(kioskA.getId())
                .build());
    }

    @Test
    void createSale_reducesInventory() throws Exception {
        when(securityUtil.getCurrentUserId()).thenReturn(encargada.getId());

        KioskPosSaleResponse sale = kioskPosService.createSale(KioskPosSaleRequest.builder()
                .kioskLocationId(kioskA.getId())
                .paymentMethod("EFECTIVO")
                .amountReceived(new BigDecimal("500.00"))
                .items(List.of(item(wallet.getId(), negro.getId(), new BigDecimal("2"))))
                .build());

        assertThat(sale.getSaleNumber()).startsWith("POS-");
        assertThat(sale.getTotalAmount()).isEqualByComparingTo("500.00");

        ProductInventoryLocation row = inventoryRepository
                .findByProductIdAndLocationIdAndColorId(wallet.getId(), kioskA.getId(), negro.getId())
                .orElseThrow();
        assertThat(row.getQuantity()).isEqualByComparingTo("3");
    }

    @Test
    void createSale_rejectsInsufficientStock() {
        when(securityUtil.getCurrentUserId()).thenReturn(encargada.getId());

        assertThatThrownBy(() -> kioskPosService.createSale(KioskPosSaleRequest.builder()
                .kioskLocationId(kioskA.getId())
                .paymentMethod("EFECTIVO")
                .amountReceived(new BigDecimal("2000.00"))
                .items(List.of(item(wallet.getId(), negro.getId(), new BigDecimal("10"))))
                .build()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Stock insuficiente");
    }

    @Test
    void createSale_requiresCardDataForTarjeta() {
        when(securityUtil.getCurrentUserId()).thenReturn(encargada.getId());

        assertThatThrownBy(() -> kioskPosService.createSale(KioskPosSaleRequest.builder()
                .kioskLocationId(kioskA.getId())
                .paymentMethod("TARJETA")
                .items(List.of(item(wallet.getId(), negro.getId(), BigDecimal.ONE)))
                .build()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("número de autorización");
    }

    @Test
    void createSale_rejectsInvalidCardLast4() {
        when(securityUtil.getCurrentUserId()).thenReturn(encargada.getId());

        assertThatThrownBy(() -> kioskPosService.createSale(KioskPosSaleRequest.builder()
                .kioskLocationId(kioskA.getId())
                .paymentMethod("TARJETA")
                .cardAuthNumber("123456")
                .cardLast4("12")
                .items(List.of(item(wallet.getId(), negro.getId(), BigDecimal.ONE)))
                .build()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("últimos 4 dígitos");
    }

    @Test
    void createSale_doesNotInvoiceWithoutRequestOrNit() throws Exception {
        when(securityUtil.getCurrentUserId()).thenReturn(encargada.getId());

        KioskPosSaleResponse sale = kioskPosService.createSale(KioskPosSaleRequest.builder()
                .kioskLocationId(kioskA.getId())
                .paymentMethod("TARJETA")
                .cardAuthNumber("123456")
                .cardLast4("9876")
                .items(List.of(item(wallet.getId(), negro.getId(), BigDecimal.ONE)))
                .build());

        assertThat(sale.getCardAuthNumber()).isEqualTo("123456");
        assertThat(sale.getCardLast4()).isEqualTo("9876");
        assertThat(sale.getInvoice()).isNull();
    }

    @Test
    void createSale_assignsLocationInternalNumberOnlyWhenInvoiced() throws Exception {
        kioskA.setInternalSeriesCode("A1");
        locationRepository.save(kioskA);
        when(securityUtil.getCurrentUserId()).thenReturn(encargada.getId());

        KioskPosSaleResponse firstSale = kioskPosService.createSale(KioskPosSaleRequest.builder()
                .kioskLocationId(kioskA.getId())
                .paymentMethod("EFECTIVO")
                .amountReceived(new BigDecimal("250.00"))
                .customerTaxId("CF")
                .email("cliente@example.com")
                .requestInvoice(true)
                .items(List.of(item(wallet.getId(), negro.getId(), BigDecimal.ONE)))
                .build());
        KioskPosSaleResponse secondSale = kioskPosService.createSale(KioskPosSaleRequest.builder()
                .kioskLocationId(kioskA.getId())
                .paymentMethod("EFECTIVO")
                .amountReceived(new BigDecimal("250.00"))
                .customerTaxId("CF")
                .email("cliente2@example.com")
                .requestInvoice(true)
                .items(List.of(item(wallet.getId(), negro.getId(), BigDecimal.ONE)))
                .build());

        assertThat(firstSale.getInvoice()).isNotNull();
        assertThat(firstSale.getInvoice().getInternalNumber()).isEqualTo("A1-1");
        assertThat(secondSale.getInvoice().getInternalNumber()).isEqualTo("A1-2");
    }

    @Test
    void discount_percent_and_fixed() throws Exception {
        when(securityUtil.getCurrentUserId()).thenReturn(admin.getId());

        KioskPromotionEntity percentPromo = promotionRepository.save(KioskPromotionEntity.builder()
                .name("10%")
                .discountType("PERCENT")
                .discountValue(new BigDecimal("10"))
                .active(true)
                .build());

        KioskPosSaleResponse percentSale = kioskPosService.createSale(KioskPosSaleRequest.builder()
                .kioskLocationId(kioskA.getId())
                .paymentMethod("TARJETA")
                .cardAuthNumber("123456")
                .cardLast4("1234")
                .promotionId(percentPromo.getId())
                .items(List.of(item(wallet.getId(), negro.getId(), BigDecimal.ONE)))
                .build());

        assertThat(percentSale.getSubtotal()).isEqualByComparingTo("250.00");
        assertThat(percentSale.getDiscountAmount()).isEqualByComparingTo("25.00");
        assertThat(percentSale.getTotalAmount()).isEqualByComparingTo("225.00");

        KioskPromotionEntity fixedPromo = promotionRepository.save(KioskPromotionEntity.builder()
                .name("Q50")
                .discountType("FIXED")
                .discountValue(new BigDecimal("50"))
                .active(true)
                .build());

        KioskPosSaleResponse fixedSale = kioskPosService.createSale(KioskPosSaleRequest.builder()
                .kioskLocationId(kioskA.getId())
                .paymentMethod("TARJETA")
                .cardAuthNumber("123456")
                .cardLast4("1234")
                .promotionId(fixedPromo.getId())
                .items(List.of(item(wallet.getId(), negro.getId(), BigDecimal.ONE)))
                .build());

        assertThat(fixedSale.getDiscountAmount()).isEqualByComparingTo("50.00");
        assertThat(fixedSale.getTotalAmount()).isEqualByComparingTo("200.00");
    }

    @Test
    void discount_tiered_percent_by_audience_line() throws Exception {
        when(securityUtil.getCurrentUserId()).thenReturn(admin.getId());

        ProductCategoryEntity bolsosCategory = productCategoryRepository.save(ProductCategoryEntity.builder()
                .code("BOL")
                .name("Bolsos dama")
                .build());
        ProductCategoryEntity tarjeterosCategory = productCategoryRepository.save(ProductCategoryEntity.builder()
                .code("TAR")
                .name("Tarjeteros")
                .build());

        ProductEntity damaProduct = productRepository.save(ProductEntity.builder()
                .code("DAMA-001")
                .name("Bolso Dama")
                .salePrice(new BigDecimal("100.00"))
                .audienceCategory("DAMA")
                .categoryId(bolsosCategory.getId())
                .build());
        ProductEntity caballeroProduct = productRepository.save(ProductEntity.builder()
                .code("CAB-001")
                .name("Billetera Caballero")
                .salePrice(new BigDecimal("100.00"))
                .audienceCategory("CABALLERO")
                .categoryId(billeterasCategory.getId())
                .build());
        ProductEntity unisexProduct = productRepository.save(ProductEntity.builder()
                .code("UNI-001")
                .name("Tarjetero Unisex")
                .salePrice(new BigDecimal("100.00"))
                .audienceCategory("UNISEX")
                .categoryId(tarjeterosCategory.getId())
                .build());

        seedInventory(damaProduct.getId(), 5);
        seedInventory(caballeroProduct.getId(), 5);
        seedInventory(unisexProduct.getId(), 5);

        var created = kioskPosService.createPromotion(KioskPromotionRequest.builder()
                .name("Liquidacion por linea")
                .discountType("TIERED_PERCENT")
                .active(true)
                .tiers(List.of(
                        KioskPromotionTierRequest.builder()
                                .audienceCategory("DAMA")
                                .categoryId(bolsosCategory.getId())
                                .discountValue(new BigDecimal("15"))
                                .build(),
                        KioskPromotionTierRequest.builder()
                                .audienceCategory("CABALLERO")
                                .categoryId(billeterasCategory.getId())
                                .discountValue(new BigDecimal("10"))
                                .build(),
                        KioskPromotionTierRequest.builder()
                                .audienceCategory("UNISEX")
                                .categoryId(tarjeterosCategory.getId())
                                .discountValue(new BigDecimal("5"))
                                .build()))
                .build());

        KioskPosSaleResponse sale = kioskPosService.createSale(KioskPosSaleRequest.builder()
                .kioskLocationId(kioskA.getId())
                .paymentMethod("TARJETA")
                .cardAuthNumber("123456")
                .cardLast4("1234")
                .promotionId(created.getId())
                .items(List.of(
                        item(damaProduct.getId(), negro.getId(), BigDecimal.ONE),
                        item(caballeroProduct.getId(), negro.getId(), BigDecimal.ONE),
                        item(unisexProduct.getId(), negro.getId(), BigDecimal.ONE)))
                .build());

        assertThat(sale.getSubtotal()).isEqualByComparingTo("300.00");
        assertThat(sale.getDiscountAmount()).isEqualByComparingTo("30.00");
        assertThat(sale.getTotalAmount()).isEqualByComparingTo("270.00");
    }

    @Test
    void discount_tiered_percent_zero_tier_skips_line() throws Exception {
        when(securityUtil.getCurrentUserId()).thenReturn(admin.getId());

        ProductCategoryEntity bolsosCategory = productCategoryRepository.save(ProductCategoryEntity.builder()
                .code("BOL2")
                .name("Bolsos teen")
                .build());
        ProductCategoryEntity tarjeterosCategory = productCategoryRepository.save(ProductCategoryEntity.builder()
                .code("TAR2")
                .name("Monederos")
                .build());

        ProductEntity damaProduct = productRepository.save(ProductEntity.builder()
                .code("DAMA-002")
                .name("Bolso Dama Promo")
                .salePrice(new BigDecimal("200.00"))
                .audienceCategory("DAMA")
                .categoryId(bolsosCategory.getId())
                .build());
        ProductEntity unisexProduct = productRepository.save(ProductEntity.builder()
                .code("UNI-002")
                .name("Monedero Unisex Promo")
                .salePrice(new BigDecimal("100.00"))
                .audienceCategory("UNISEX")
                .categoryId(tarjeterosCategory.getId())
                .build());

        seedInventory(damaProduct.getId(), 5);
        seedInventory(unisexProduct.getId(), 5);

        KioskPromotionEntity tieredPromo = promotionRepository.save(KioskPromotionEntity.builder()
                .name("Solo dama")
                .discountType("TIERED_PERCENT")
                .discountValue(BigDecimal.ZERO)
                .active(true)
                .build());
        tieredPromo.setTiers(new ArrayList<>(List.of(
                KioskPromotionTierEntity.builder()
                        .promotion(tieredPromo)
                        .audienceCategory("DAMA")
                        .categoryId(bolsosCategory.getId())
                        .discountValue(new BigDecimal("10"))
                        .build(),
                KioskPromotionTierEntity.builder()
                        .promotion(tieredPromo)
                        .audienceCategory("UNISEX")
                        .categoryId(tarjeterosCategory.getId())
                        .discountValue(BigDecimal.ZERO)
                        .build())));
        promotionRepository.save(tieredPromo);

        KioskPosSaleResponse sale = kioskPosService.createSale(KioskPosSaleRequest.builder()
                .kioskLocationId(kioskA.getId())
                .paymentMethod("TARJETA")
                .cardAuthNumber("123456")
                .cardLast4("1234")
                .promotionId(tieredPromo.getId())
                .items(List.of(
                        item(damaProduct.getId(), negro.getId(), BigDecimal.ONE),
                        item(unisexProduct.getId(), negro.getId(), BigDecimal.ONE)))
                .build());

        assertThat(sale.getSubtotal()).isEqualByComparingTo("300.00");
        assertThat(sale.getDiscountAmount()).isEqualByComparingTo("20.00");
        assertThat(sale.getTotalAmount()).isEqualByComparingTo("280.00");
    }

    @Test
    void discount_auto_applies_tier_without_manual_selection() throws Exception {
        when(securityUtil.getCurrentUserId()).thenReturn(encargada.getId());

        ProductEntity caballeroProduct = productRepository.save(ProductEntity.builder()
                .code("CAB-AUTO")
                .name("Billetera Auto")
                .salePrice(new BigDecimal("100.00"))
                .audienceCategory("CABALLERO")
                .categoryId(billeterasCategory.getId())
                .build());
        seedInventory(caballeroProduct.getId(), 5);

        KioskPromotionEntity tieredPromo = promotionRepository.save(KioskPromotionEntity.builder()
                .name("Auto caballero billeteras")
                .discountType("TIERED_PERCENT")
                .discountValue(BigDecimal.ZERO)
                .kioskLocationId(kioskA.getId())
                .active(true)
                .build());
        tieredPromo.setTiers(new ArrayList<>(List.of(
                KioskPromotionTierEntity.builder()
                        .promotion(tieredPromo)
                        .audienceCategory("CABALLERO")
                        .categoryId(billeterasCategory.getId())
                        .discountValue(new BigDecimal("10"))
                        .build())));
        promotionRepository.save(tieredPromo);

        KioskPosSaleResponse sale = kioskPosService.createSale(KioskPosSaleRequest.builder()
                .kioskLocationId(kioskA.getId())
                .paymentMethod("TARJETA")
                .cardAuthNumber("123456")
                .cardLast4("1234")
                .items(List.of(item(caballeroProduct.getId(), negro.getId(), BigDecimal.ONE)))
                .build());

        assertThat(sale.getDiscountAmount()).isEqualByComparingTo("10.00");
        assertThat(sale.getTotalAmount()).isEqualByComparingTo("90.00");
        assertThat(sale.getPromotionName()).isEqualTo("Promoción automática");
    }

    @Test
    void discount_never_applies_to_packaging() throws Exception {
        when(securityUtil.getCurrentUserId()).thenReturn(encargada.getId());

        ProductEntity packaging = productRepository.save(ProductEntity.builder()
                .code("SUM-001")
                .name("Empaque regalo")
                .salePrice(new BigDecimal("20.00"))
                .categoryId(billeterasCategory.getId())
                .build());
        seedInventory(packaging.getId(), 5);

        KioskPromotionEntity tieredPromo = promotionRepository.save(KioskPromotionEntity.builder()
                .name("No empaques")
                .discountType("TIERED_PERCENT")
                .discountValue(BigDecimal.ZERO)
                .kioskLocationId(kioskA.getId())
                .active(true)
                .build());
        tieredPromo.setTiers(new ArrayList<>(List.of(
                KioskPromotionTierEntity.builder()
                        .promotion(tieredPromo)
                        .audienceCategory("UNISEX")
                        .categoryId(billeterasCategory.getId())
                        .discountValue(new BigDecimal("50"))
                        .build())));
        promotionRepository.save(tieredPromo);

        KioskPosSaleResponse sale = kioskPosService.createSale(KioskPosSaleRequest.builder()
                .kioskLocationId(kioskA.getId())
                .paymentMethod("TARJETA")
                .cardAuthNumber("123456")
                .cardLast4("1234")
                .items(List.of(item(packaging.getId(), negro.getId(), BigDecimal.ONE)))
                .build());

        assertThat(sale.getDiscountAmount()).isEqualByComparingTo("0.00");
        assertThat(sale.getTotalAmount()).isEqualByComparingTo("20.00");
    }

    @Test
    void discount_manual_percent_excludes_packaging() throws Exception {
        when(securityUtil.getCurrentUserId()).thenReturn(encargada.getId());

        ProductEntity packaging = productRepository.save(ProductEntity.builder()
                .code("SUM-002")
                .name("Bolsa SUM")
                .salePrice(new BigDecimal("20.00"))
                .build());
        seedInventory(packaging.getId(), 5);

        KioskPosSaleResponse sale = kioskPosService.createSale(KioskPosSaleRequest.builder()
                .kioskLocationId(kioskA.getId())
                .paymentMethod("TARJETA")
                .cardAuthNumber("123456")
                .cardLast4("1234")
                .manualDiscountPercent(new BigDecimal("10"))
                .items(List.of(
                        item(wallet.getId(), negro.getId(), BigDecimal.ONE),
                        item(packaging.getId(), negro.getId(), BigDecimal.ONE)))
                .build());

        assertThat(sale.getSubtotal()).isEqualByComparingTo("270.00");
        assertThat(sale.getDiscountAmount()).isEqualByComparingTo("25.00");
        assertThat(sale.getTotalAmount()).isEqualByComparingTo("245.00");
    }

    @Test
    void discount_auto_applies_percent_without_manual_selection() throws Exception {
        when(securityUtil.getCurrentUserId()).thenReturn(encargada.getId());

        KioskPromotionEntity percentPromo = promotionRepository.save(KioskPromotionEntity.builder()
                .name("10% auto")
                .discountType("PERCENT")
                .discountValue(new BigDecimal("10"))
                .kioskLocationId(kioskA.getId())
                .active(true)
                .build());

        KioskPosSaleResponse sale = kioskPosService.createSale(KioskPosSaleRequest.builder()
                .kioskLocationId(kioskA.getId())
                .paymentMethod("TARJETA")
                .cardAuthNumber("123456")
                .cardLast4("1234")
                .items(List.of(item(wallet.getId(), negro.getId(), BigDecimal.ONE)))
                .build());

        assertThat(sale.getDiscountAmount()).isEqualByComparingTo("25.00");
        assertThat(sale.getTotalAmount()).isEqualByComparingTo("225.00");
        assertThat(sale.getPromotionId()).isEqualTo(percentPromo.getId());
        assertThat(sale.getPromotionName()).isEqualTo("10% auto");
    }

    @Test
    void discount_auto_applies_combo_without_manual_selection() throws Exception {
        when(securityUtil.getCurrentUserId()).thenReturn(encargada.getId());

        KioskPromotionEntity combo = promotionRepository.save(KioskPromotionEntity.builder()
                .name("2x1 auto")
                .discountType("COMBO")
                .discountValue(BigDecimal.ZERO)
                .comboBuyQty(2)
                .comboPayQty(1)
                .kioskLocationId(kioskA.getId())
                .active(true)
                .build());

        KioskPosSaleResponse sale = kioskPosService.createSale(KioskPosSaleRequest.builder()
                .kioskLocationId(kioskA.getId())
                .paymentMethod("TARJETA")
                .cardAuthNumber("123456")
                .cardLast4("1234")
                .items(List.of(item(wallet.getId(), negro.getId(), new BigDecimal("2"))))
                .build());

        assertThat(sale.getDiscountAmount()).isEqualByComparingTo("250.00");
        assertThat(sale.getTotalAmount()).isEqualByComparingTo("250.00");
        assertThat(sale.getPromotionId()).isEqualTo(combo.getId());
        assertThat(sale.getPromotionName()).isEqualTo("2x1 auto");
    }

    @Test
    void admin_manage_view_lists_all_promotions_including_other_kiosk() throws Exception {
        when(securityUtil.getCurrentUserId()).thenReturn(admin.getId());

        KioskPromotionEntity promoA = promotionRepository.save(KioskPromotionEntity.builder()
                .name("Promo kiosko A")
                .discountType("TIERED_PERCENT")
                .discountValue(BigDecimal.ZERO)
                .kioskLocationId(kioskA.getId())
                .active(true)
                .build());
        promoA.setTiers(new ArrayList<>(List.of(
                KioskPromotionTierEntity.builder()
                        .promotion(promoA)
                        .audienceCategory("CABALLERO")
                        .categoryId(billeterasCategory.getId())
                        .discountValue(new BigDecimal("10"))
                        .build())));
        promotionRepository.save(promoA);

        var rows = kioskPosService.getPromotions(false, kioskB.getId());

        assertThat(rows.stream().anyMatch(row -> "Promo kiosko A".equals(row.getName()))).isTrue();
    }

    @Test
    void managerDashboard_reflects_completed_sales() throws Exception {
        when(securityUtil.getCurrentUserId()).thenReturn(encargada.getId());

        KioskPosSaleResponse sale = kioskPosService.createSale(KioskPosSaleRequest.builder()
                .kioskLocationId(kioskA.getId())
                .paymentMethod("TARJETA")
                .cardAuthNumber("123456")
                .cardLast4("1234")
                .items(List.of(item(wallet.getId(), negro.getId(), BigDecimal.ONE)))
                .build());

        var dashboard = kioskPosService.getManagerDashboard(kioskA.getId());

        assertThat(dashboard.getToday().getCount()).isEqualTo(1);
        assertThat(dashboard.getToday().getAmount()).isEqualByComparingTo(sale.getTotalAmount());
        assertThat(dashboard.getMonthToDate().getCount()).isEqualTo(1);
        assertThat(dashboard.getMonthToDate().getAmount()).isEqualByComparingTo(sale.getTotalAmount());
    }

    @Test
    void discount_percent_with_audience_line_keeps_legacy_behavior() throws Exception {
        when(securityUtil.getCurrentUserId()).thenReturn(admin.getId());

        ProductEntity damaProduct = productRepository.save(ProductEntity.builder()
                .code("DAMA-003")
                .name("Bolso Dama Legacy")
                .salePrice(new BigDecimal("100.00"))
                .audienceCategory("DAMA")
                .build());
        ProductEntity caballeroProduct = productRepository.save(ProductEntity.builder()
                .code("CAB-003")
                .name("Billetera Caballero Legacy")
                .salePrice(new BigDecimal("100.00"))
                .audienceCategory("CABALLERO")
                .build());

        seedInventory(damaProduct.getId(), 5);
        seedInventory(caballeroProduct.getId(), 5);

        KioskPromotionEntity damaPromo = promotionRepository.save(KioskPromotionEntity.builder()
                .name("10% Dama")
                .discountType("PERCENT")
                .discountValue(new BigDecimal("10"))
                .audienceCategory("DAMA")
                .active(true)
                .build());

        KioskPosSaleResponse sale = kioskPosService.createSale(KioskPosSaleRequest.builder()
                .kioskLocationId(kioskA.getId())
                .paymentMethod("TARJETA")
                .cardAuthNumber("123456")
                .cardLast4("1234")
                .promotionId(damaPromo.getId())
                .items(List.of(
                        item(damaProduct.getId(), negro.getId(), BigDecimal.ONE),
                        item(caballeroProduct.getId(), negro.getId(), BigDecimal.ONE)))
                .build());

        assertThat(sale.getSubtotal()).isEqualByComparingTo("200.00");
        assertThat(sale.getDiscountAmount()).isEqualByComparingTo("10.00");
        assertThat(sale.getTotalAmount()).isEqualByComparingTo("190.00");
    }

    @Test
    void discount_combo_2x1() throws Exception {
        when(securityUtil.getCurrentUserId()).thenReturn(admin.getId());

        KioskPromotionEntity combo = promotionRepository.save(KioskPromotionEntity.builder()
                .name("2x1")
                .discountType("COMBO")
                .discountValue(BigDecimal.ZERO)
                .comboBuyQty(2)
                .comboPayQty(1)
                .active(true)
                .build());

        KioskPosSaleResponse sale = kioskPosService.createSale(KioskPosSaleRequest.builder()
                .kioskLocationId(kioskA.getId())
                .paymentMethod("TARJETA")
                .cardAuthNumber("123456")
                .cardLast4("1234")
                .promotionId(combo.getId())
                .items(List.of(item(wallet.getId(), negro.getId(), new BigDecimal("2"))))
                .build());

        assertThat(sale.getSubtotal()).isEqualByComparingTo("500.00");
        assertThat(sale.getDiscountAmount()).isEqualByComparingTo("250.00");
        assertThat(sale.getTotalAmount()).isEqualByComparingTo("250.00");
    }

    @Test
    void cash_change_calculation() throws Exception {
        when(securityUtil.getCurrentUserId()).thenReturn(encargada.getId());

        KioskPosSaleResponse sale = kioskPosService.createSale(KioskPosSaleRequest.builder()
                .kioskLocationId(kioskA.getId())
                .paymentMethod("EFECTIVO")
                .amountReceived(new BigDecimal("300.00"))
                .items(List.of(item(wallet.getId(), negro.getId(), BigDecimal.ONE)))
                .build());

        assertThat(sale.getTotalAmount()).isEqualByComparingTo("250.00");
        assertThat(sale.getAmountReceived()).isEqualByComparingTo("300.00");
        assertThat(sale.getChangeAmount()).isEqualByComparingTo("50.00");
    }

    @Test
    void report_matches_sales() throws Exception {
        when(securityUtil.getCurrentUserId()).thenReturn(encargada.getId());

        kioskPosService.createSale(KioskPosSaleRequest.builder()
                .kioskLocationId(kioskA.getId())
                .paymentMethod("EFECTIVO")
                .amountReceived(new BigDecimal("250.00"))
                .items(List.of(item(wallet.getId(), negro.getId(), BigDecimal.ONE)))
                .build());

        kioskPosService.createSale(KioskPosSaleRequest.builder()
                .kioskLocationId(kioskA.getId())
                .paymentMethod("TARJETA")
                .cardAuthNumber("123456")
                .cardLast4("1234")
                .items(List.of(item(wallet.getId(), negro.getId(), BigDecimal.ONE)))
                .build());

        LocalDate today = LocalDate.now();
        KioskPosReportsResponse report = kioskPosService.getCurrentKioskReport(today, today, kioskA.getId());

        assertThat(report.getSalesCount()).isEqualTo(2);
        assertThat(report.getTotalItems()).isEqualByComparingTo("2");
        assertThat(report.getTotalAmount()).isEqualByComparingTo("500.00");
        assertThat(report.getAverageTicket()).isEqualByComparingTo("250.00");
    }

    @Test
    void encargada_cannot_access_other_kiosk() {
        when(securityUtil.getCurrentUserId()).thenReturn(encargada.getId());

        assertThatThrownBy(() -> kioskPosService.getCurrentContext(kioskB.getId(), null, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("No tienes acceso");
    }

    @Test
    void findAvailabilityInKiosks_scoped_to_assigned_kiosk() throws Exception {
        when(securityUtil.getCurrentUserId()).thenReturn(encargada.getId());

        var availability = kioskPosService.findAvailabilityInKiosks(
                wallet.getId(), negro.getId(), true, kioskA.getId());

        assertThat(availability).hasSize(1);
        assertThat(availability.get(0).getKioskId()).isEqualTo(kioskA.getId());
    }

    @Test
    void encargada_cannot_query_promotions_for_other_kiosk() {
        when(securityUtil.getCurrentUserId()).thenReturn(encargada.getId());

        assertThatThrownBy(() -> kioskPosService.getPromotions(true, kioskB.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("No tienes acceso");
    }

    @Test
    void admin_can_create_promotion() throws Exception {
        when(securityUtil.getCurrentUserId()).thenReturn(admin.getId());

        var created = kioskPosService.createPromotion(KioskPromotionRequest.builder()
                .name("Promo test")
                .discountType("PERCENT")
                .discountValue(new BigDecimal("5"))
                .active(true)
                .build());

        assertThat(created.getName()).isEqualTo("Promo test");
    }

    @Test
    void pendingDeposit_applies_to_test_sale_cash() {
        KioskSaleEntity sale = KioskSaleEntity.builder()
                .status("COMPLETED")
                .paymentMethod("EFECTIVO")
                .totalAmount(new BigDecimal("100.00"))
                .cashAmount(new BigDecimal("100.00"))
                .testSale(true)
                .build();

        assertThat(KioskPosService.isPendingDeposit(sale)).isTrue();
        assertThat(KioskPosService.pendingDepositCashAmount(sale)).isEqualByComparingTo("100.00");
    }

    @Test
    void pendingDeposit_applies_to_mixto_with_cash() {
        KioskSaleEntity sale = KioskSaleEntity.builder()
                .status("COMPLETED")
                .paymentMethod("MIXTO")
                .totalAmount(new BigDecimal("150.00"))
                .cashAmount(new BigDecimal("50.00"))
                .cardAmount(new BigDecimal("100.00"))
                .testSale(true)
                .build();

        assertThat(KioskPosService.isPendingDeposit(sale)).isTrue();
        assertThat(KioskPosService.pendingDepositCashAmount(sale)).isEqualByComparingTo("50.00");
    }

    @Test
    void pendingDeposit_does_not_apply_to_card_only() {
        KioskSaleEntity sale = KioskSaleEntity.builder()
                .status("COMPLETED")
                .paymentMethod("TARJETA")
                .totalAmount(new BigDecimal("100.00"))
                .cardAmount(new BigDecimal("100.00"))
                .build();

        assertThat(KioskPosService.isPendingDeposit(sale)).isFalse();
    }

    private void seedInventory(Long productId, int quantity) {
        inventoryRepository.save(ProductInventoryLocation.builder()
                .productId(productId)
                .locationId(kioskA.getId())
                .colorId(negro.getId())
                .quantity(new BigDecimal(quantity))
                .build());

        kioscoStockRepository.save(KioscoStockEntity.builder()
                .locationId(kioskA.getId())
                .productId(productId)
                .colorId(negro.getId())
                .currentStock(quantity)
                .build());
    }

    private static KioskPosSaleRequest.ItemRequest item(Long productId, Long colorId, BigDecimal qty) {
        return KioskPosSaleRequest.ItemRequest.builder()
                .productId(productId)
                .colorId(colorId)
                .quantity(qty)
                .build();
    }
}
