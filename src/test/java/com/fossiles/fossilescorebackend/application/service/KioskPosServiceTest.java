package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.KioskCashSessionOpenRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskPosSaleRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskPromotionRequest;
import com.fossiles.fossilescorebackend.application.dto.response.KioskPosReportsResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskPosSaleResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoStockEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskPromotionEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
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

    private static KioskPosSaleRequest.ItemRequest item(Long productId, Long colorId, BigDecimal qty) {
        return KioskPosSaleRequest.ItemRequest.builder()
                .productId(productId)
                .colorId(colorId)
                .quantity(qty)
                .build();
    }
}
