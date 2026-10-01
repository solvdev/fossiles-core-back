package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.KioskCashSessionOpenRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskExchangeCompleteRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskExchangePreviewRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskPosSaleRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskSimpleReturnRequest;
import com.fossiles.fossilescorebackend.application.dto.response.KioskExchangeCompleteResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskExchangePreviewResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskExchangeSlipResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskPosSaleResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoMovementEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoMovementType;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoStockEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskExchangeSlipEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSaleItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductInventoryLocation;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.RoleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoMovementRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoStockRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskExchangeSlipRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskExchangeSlipReturnedItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSaleItemRepository;
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
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class KioskExchangeServiceTest {

    @Autowired
    private KioskExchangeService kioskExchangeService;

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
    private KioskSaleRepository saleRepository;

    @Autowired
    private KioskSaleItemRepository saleItemRepository;

    @Autowired
    private KioscoMovementRepository kioscoMovementRepository;

    @Autowired
    private KioskExchangeSlipRepository exchangeSlipRepository;

    @Autowired
    private KioskExchangeSlipReturnedItemRepository exchangeSlipReturnedItemRepository;

    @MockBean
    private SecurityUtil securityUtil;

    private UserEntity encargada;
    private LocationEntity kiosk;
    private ProductEntity originalProduct;
    private ProductEntity newProduct;
    private ColorEntity negro;
    private KioskPosSaleResponse originalSale;

    @BeforeEach
    void setUp() throws Exception {
        RoleEntity encargadaRole = roleRepository.save(RoleEntity.builder().name("ENCARGADA").build());
        encargada = userRepository.save(UserEntity.builder()
                .username("encargada.exchange")
                .email("encargada.exchange@fossiles.test")
                .password("x")
                .status("ACTIVE")
                .roles(new HashSet<>(Set.of(encargadaRole)))
                .build());

        kiosk = locationRepository.save(LocationEntity.builder()
                .code("KIOSK_X")
                .name("Kiosko Exchange")
                .categoria("KIOSKO")
                .encargadoId(encargada.getId())
                .build());

        originalProduct = productRepository.save(ProductEntity.builder()
                .code("OLD-001")
                .name("Cartera Promo")
                .salePrice(new BigDecimal("180.00"))
                .build());

        newProduct = productRepository.save(ProductEntity.builder()
                .code("NEW-001")
                .name("Cartera Catalogo")
                .salePrice(new BigDecimal("250.00"))
                .build());

        negro = colorRepository.save(ColorEntity.builder().name("NEGRO").build());

        seedInventory(originalProduct.getId(), 5);
        seedInventory(newProduct.getId(), 5);

        when(securityUtil.getCurrentUserId()).thenReturn(encargada.getId());
        kioskPosService.openCashSession(KioskCashSessionOpenRequest.builder()
                .kioskLocationId(kiosk.getId())
                .build());

        originalSale = kioskPosService.createSale(KioskPosSaleRequest.builder()
                .kioskLocationId(kiosk.getId())
                .paymentMethod("EFECTIVO")
                .amountReceived(new BigDecimal("180.00"))
                .chargeWithoutDiscount(true)
                .items(List.of(item(originalProduct.getId(), negro.getId(), BigDecimal.ONE)))
                .build());
    }

    @Test
    void previewExchange_sameUnitPrice_differentStyle_zeroDifference() throws Exception {
        KioskSaleItemEntity saleItem = saleItemRepository.findByKioskSaleIdOrderByIdAsc(originalSale.getId()).get(0);

        KioskExchangePreviewResponse preview = kioskExchangeService.previewExchange(
                KioskExchangePreviewRequest.builder()
                        .kioskLocationId(kiosk.getId())
                        .originalSaleId(originalSale.getId())
                        .originalSaleItemId(saleItem.getId())
                        .givenProductId(newProduct.getId())
                        .givenColorId(negro.getId())
                        .returnedQuantity(BigDecimal.ONE)
                        .givenQuantity(BigDecimal.ONE)
                        .pricingMode("SAME_UNIT_PRICE")
                        .build());

        assertThat(preview.getReturned().getUnitPrice()).isEqualByComparingTo("180.00");
        assertThat(preview.getGiven().getUnitPrice()).isEqualByComparingTo("180.00");
        assertThat(preview.getReturnedAmount()).isEqualByComparingTo("180.00");
        assertThat(preview.getGivenAmount()).isEqualByComparingTo("180.00");
        assertThat(preview.getDifferenceAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void previewExchange_catalogGiven_forcesCatalogEvenForSameProduct() throws Exception {
        KioskPosSaleResponse discountedSale = kioskPosService.createSale(KioskPosSaleRequest.builder()
                .kioskLocationId(kiosk.getId())
                .paymentMethod("EFECTIVO")
                .amountReceived(new BigDecimal("90.00"))
                .manualDiscountPercent(new BigDecimal("50"))
                .items(List.of(item(originalProduct.getId(), negro.getId(), BigDecimal.ONE)))
                .build());
        KioskSaleItemEntity saleItem = saleItemRepository.findByKioskSaleIdOrderByIdAsc(discountedSale.getId()).get(0);

        KioskExchangePreviewResponse preview = kioskExchangeService.previewExchange(
                KioskExchangePreviewRequest.builder()
                        .kioskLocationId(kiosk.getId())
                        .originalSaleId(discountedSale.getId())
                        .originalSaleItemId(saleItem.getId())
                        .givenProductId(originalProduct.getId())
                        .givenColorId(negro.getId())
                        .returnedQuantity(BigDecimal.ONE)
                        .givenQuantity(BigDecimal.ONE)
                        .pricingMode("CATALOG_GIVEN")
                        .returnedSoldWithDiscount(true)
                        .returnedDiscountPercent(new BigDecimal("50"))
                        .build());

        assertThat(preview.getReturned().getUnitPrice()).isEqualByComparingTo("90.00");
        assertThat(preview.getGiven().getUnitPrice()).isEqualByComparingTo("180.00");
        assertThat(preview.getDifferenceAmount()).isEqualByComparingTo("90.00");
    }

    @Test
    void completeExchange_sameUnitPrice_rejectsWhenDifferenceAppears() {
        KioskSaleItemEntity saleItem = saleItemRepository.findByKioskSaleIdOrderByIdAsc(originalSale.getId()).get(0);

        assertThatThrownBy(() -> kioskExchangeService.completeExchange(
                KioskExchangeCompleteRequest.builder()
                        .kioskLocationId(kiosk.getId())
                        .originalSaleId(originalSale.getId())
                        .originalSaleItemId(saleItem.getId())
                        .givenProductId(newProduct.getId())
                        .givenColorId(negro.getId())
                        .returnedQuantity(BigDecimal.ONE)
                        .givenQuantity(new BigDecimal("2"))
                        .pricingMode("SAME_UNIT_PRICE")
                        .physicalSlipNumber("BC-SAME-REJECT")
                        .reason("Cambio estilo")
                        .build()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("sin diferencia");
    }

    @Test
    void previewExchange_usesOriginalPriceForIngresoAndCatalogForEgreso() throws Exception {
        KioskSaleItemEntity saleItem = saleItemRepository.findByKioskSaleIdOrderByIdAsc(originalSale.getId()).get(0);

        KioskExchangePreviewResponse preview = kioskExchangeService.previewExchange(
                KioskExchangePreviewRequest.builder()
                        .kioskLocationId(kiosk.getId())
                        .originalSaleId(originalSale.getId())
                        .originalSaleItemId(saleItem.getId())
                        .givenProductId(newProduct.getId())
                        .givenColorId(negro.getId())
                        .returnedQuantity(BigDecimal.ONE)
                        .givenQuantity(BigDecimal.ONE)
                        .build());

        assertThat(preview.getReturnedAmount()).isEqualByComparingTo("180.00");
        assertThat(preview.getGivenAmount()).isEqualByComparingTo("250.00");
        assertThat(preview.getDifferenceAmount()).isEqualByComparingTo("70.00");
    }

    @Test
    void completeExchange_adjustsStockAndChargesDifference() throws Exception {
        KioskSaleItemEntity saleItem = saleItemRepository.findByKioskSaleIdOrderByIdAsc(originalSale.getId()).get(0);
        int stockBefore = currentStock(newProduct.getId());

        KioskExchangeCompleteResponse result = kioskExchangeService.completeExchange(
                KioskExchangeCompleteRequest.builder()
                        .kioskLocationId(kiosk.getId())
                        .originalSaleId(originalSale.getId())
                        .originalSaleItemId(saleItem.getId())
                        .givenProductId(newProduct.getId())
                        .givenColorId(negro.getId())
                        .returnedQuantity(BigDecimal.ONE)
                        .givenQuantity(BigDecimal.ONE)
                        .physicalSlipNumber("BC-TEST-001")
                        .paymentMethod("EFECTIVO")
                        .amountReceived(new BigDecimal("100.00"))
                        .reason("Cambio de talla")
                        .build());

        assertThat(result.getSlip().getSlipNumber()).isEqualTo("BC-TEST-001");
        assertThat(result.getSlip().getStatus()).isEqualTo("COMPLETED");
        assertThat(result.getSale().getTotalAmount()).isEqualByComparingTo("70.00");
        assertThat(result.getSale().getDiscountAmount()).isEqualByComparingTo("180.00");
        assertThat(currentStock(originalProduct.getId())).isEqualTo(5);
        assertThat(currentStock(newProduct.getId())).isEqualTo(stockBefore - 1);

        List<KioscoMovementEntity> slipMoves =
                kioscoMovementRepository.findByPhysicalSlipNumber("BC-TEST-001");
        assertThat(slipMoves).filteredOn(m -> m.getStockAfter() > m.getStockBefore())
                .extracting(KioscoMovementEntity::getMovementType)
                .containsOnly(KioscoMovementType.CAMBIO);
        assertThat(slipMoves).filteredOn(m -> m.getStockAfter() < m.getStockBefore())
                .extracting(KioscoMovementEntity::getMovementType)
                .containsOnly(KioscoMovementType.CAMBIO);
        assertThat(slipMoves).noneMatch(m -> m.getMovementType() == KioscoMovementType.DEVOLUCION_A_CLIENTE);
        assertThat(slipMoves).noneMatch(m -> m.getMovementType() == KioscoMovementType.DEVOLUCION_CLIENTE);

        KioskExchangeSlipEntity slip = exchangeSlipRepository.findById(result.getSlip().getId()).orElseThrow();
        assertThat(slip.getReturnMovementId()).isNotNull();
        assertThat(slip.getGivenMovementId()).isNotNull();
    }

    @Test
    void reclassifyExchangeGivenAsCambio_movesLegacyVentaOutflowToCambio() throws Exception {
        KioskSaleItemEntity saleItem = saleItemRepository.findByKioskSaleIdOrderByIdAsc(originalSale.getId()).get(0);
        KioskExchangeCompleteResponse result = kioskExchangeService.completeExchange(
                KioskExchangeCompleteRequest.builder()
                        .kioskLocationId(kiosk.getId())
                        .originalSaleId(originalSale.getId())
                        .originalSaleItemId(saleItem.getId())
                        .givenProductId(newProduct.getId())
                        .givenColorId(negro.getId())
                        .returnedQuantity(BigDecimal.ONE)
                        .givenQuantity(BigDecimal.ONE)
                        .physicalSlipNumber("BC-RECLASS-001")
                        .paymentMethod("EFECTIVO")
                        .amountReceived(new BigDecimal("100.00"))
                        .reason("Cambio de talla")
                        .build());

        KioscoMovementEntity given = kioscoMovementRepository.findById(result.getSlip().getGivenMovementId()).orElseThrow();
        given.setMovementType(KioscoMovementType.VENTA);
        kioscoMovementRepository.saveAndFlush(given);

        int updated = kioskExchangeService.reclassifyExchangeGivenAsCambio();
        assertThat(updated).isGreaterThanOrEqualTo(1);

        KioscoMovementEntity recategorized = kioscoMovementRepository.findById(given.getId()).orElseThrow();
        assertThat(recategorized.getMovementType()).isEqualTo(KioscoMovementType.CAMBIO);
        KioscoMovementEntity returned = kioscoMovementRepository.findById(result.getSlip().getReturnMovementId()).orElseThrow();
        assertThat(returned.getMovementType()).isEqualTo(KioscoMovementType.CAMBIO);
    }

    @Test
    void completeExchange_rejectsVoidSale() {
        KioskSaleEntity sale = saleRepository.findById(originalSale.getId()).orElseThrow();
        sale.setStatus("VOID");
        saleRepository.save(sale);
        KioskSaleItemEntity saleItem = saleItemRepository.findByKioskSaleIdOrderByIdAsc(originalSale.getId()).get(0);

        assertThatThrownBy(() -> kioskExchangeService.previewExchange(
                KioskExchangePreviewRequest.builder()
                        .kioskLocationId(kiosk.getId())
                        .originalSaleId(originalSale.getId())
                        .originalSaleItemId(saleItem.getId())
                        .givenProductId(newProduct.getId())
                        .givenColorId(negro.getId())
                        .returnedQuantity(BigDecimal.ONE)
                        .givenQuantity(BigDecimal.ONE)
                        .build()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("anulada");
    }

    @Test
    void completeSimpleReturn_createsPendingReintegroWhenApto() throws Exception {
        KioskSaleItemEntity saleItem = saleItemRepository.findByKioskSaleIdOrderByIdAsc(originalSale.getId()).get(0);

        KioskExchangeSlipResponse slip = kioskExchangeService.completeSimpleReturn(
                KioskSimpleReturnRequest.builder()
                        .kioskLocationId(kiosk.getId())
                        .originalSaleId(originalSale.getId())
                        .originalSaleItemId(saleItem.getId())
                        .returnedQuantity(BigDecimal.ONE)
                        .apto(true)
                        .physicalSlipNumber("BD-TEST-001")
                        .reason("No le gusto")
                        .build());

        assertThat(slip.getSlipNumber()).isEqualTo("BD-TEST-001");

        assertThat(slip.getSlipType()).isEqualTo("RETURN");
        assertThat(slip.getStatus()).isEqualTo("PENDING_REINTEGRO");
        assertThat(currentStock(originalProduct.getId())).isEqualTo(5);
    }

    @Test
    void completeExchange_zeroDifference_createsPendingAuthorizationWithoutSale() throws Exception {
        KioskSaleItemEntity saleItem = saleItemRepository.findByKioskSaleIdOrderByIdAsc(originalSale.getId()).get(0);

        KioskExchangeCompleteResponse result = kioskExchangeService.completeExchange(
                KioskExchangeCompleteRequest.builder()
                        .kioskLocationId(kiosk.getId())
                        .originalSaleId(originalSale.getId())
                        .originalSaleItemId(saleItem.getId())
                        .givenProductId(originalProduct.getId())
                        .givenColorId(negro.getId())
                        .returnedQuantity(BigDecimal.ONE)
                        .givenQuantity(BigDecimal.ONE)
                        .physicalSlipNumber("BC-ZERO-001")
                        .reason("Cambio sin diferencia")
                        .build());

        assertThat(result.getSlip().getStatus()).isEqualTo("PENDING_AUTHORIZATION");
        assertThat(result.getSlip().getDifferenceAmount()).isEqualByComparingTo("0.00");
        assertThat(result.getSale()).isNull();
        assertThat(currentStock(originalProduct.getId())).isEqualTo(4);
        assertThat(kioscoMovementRepository.findByPhysicalSlipNumber("BC-ZERO-001")).isEmpty();
    }

    @Test
    void authorizeExchange_zeroDifference_registersCambioAndDevACliente() throws Exception {
        KioskSaleItemEntity saleItem = saleItemRepository.findByKioskSaleIdOrderByIdAsc(originalSale.getId()).get(0);

        KioskExchangeCompleteResponse pending = kioskExchangeService.completeExchange(
                KioskExchangeCompleteRequest.builder()
                        .kioskLocationId(kiosk.getId())
                        .originalSaleId(originalSale.getId())
                        .originalSaleItemId(saleItem.getId())
                        .givenProductId(originalProduct.getId())
                        .givenColorId(negro.getId())
                        .returnedQuantity(BigDecimal.ONE)
                        .givenQuantity(BigDecimal.ONE)
                        .physicalSlipNumber("BC-ZERO-AUTH-001")
                        .reason("Cambio sin diferencia")
                        .build());

        assertThat(pending.getSlip().getStatus()).isEqualTo("PENDING_AUTHORIZATION");

        RoleEntity adminRole = roleRepository.save(RoleEntity.builder().name("ADMIN").build());
        UserEntity admin = userRepository.save(UserEntity.builder()
                .username("admin.exchange")
                .email("admin.exchange@fossiles.test")
                .password("x")
                .status("ACTIVE")
                .roles(new HashSet<>(Set.of(adminRole)))
                .build());
        when(securityUtil.getCurrentUserId()).thenReturn(admin.getId());

        KioskExchangeSlipResponse authorized = kioskExchangeService.authorizeExchange(
                pending.getSlip().getId(), kiosk.getId());

        assertThat(authorized.getStatus()).isEqualTo("COMPLETED");
        assertThat(authorized.getReturnMovementId()).isNotNull();
        assertThat(authorized.getGivenMovementId()).isNotNull();
        assertThat(currentStock(originalProduct.getId())).isEqualTo(4);

        List<KioscoMovementEntity> slipMoves =
                kioscoMovementRepository.findByPhysicalSlipNumber("BC-ZERO-AUTH-001");
        assertThat(slipMoves).extracting(KioscoMovementEntity::getMovementType)
                .containsOnly(KioscoMovementType.CAMBIO);
        assertThat(slipMoves).noneMatch(m -> m.getMovementType() == KioscoMovementType.DEVOLUCION_A_CLIENTE);
        assertThat(slipMoves).noneMatch(m -> m.getMovementType() == KioscoMovementType.VENTA);
        assertThat(slipMoves).noneMatch(m -> m.getMovementType() == KioscoMovementType.DEVOLUCION_CLIENTE);
    }

    @Test
    void previewExchange_sameProductWithDiscount_hasZeroDifference() throws Exception {
        KioskPosSaleResponse discountedSale = kioskPosService.createSale(KioskPosSaleRequest.builder()
                .kioskLocationId(kiosk.getId())
                .paymentMethod("EFECTIVO")
                .amountReceived(new BigDecimal("90.00"))
                .manualDiscountPercent(new BigDecimal("50"))
                .items(List.of(item(originalProduct.getId(), negro.getId(), BigDecimal.ONE)))
                .build());
        KioskSaleItemEntity saleItem = saleItemRepository.findByKioskSaleIdOrderByIdAsc(discountedSale.getId()).get(0);

        KioskExchangePreviewResponse preview = kioskExchangeService.previewExchange(
                KioskExchangePreviewRequest.builder()
                        .kioskLocationId(kiosk.getId())
                        .originalSaleId(discountedSale.getId())
                        .originalSaleItemId(saleItem.getId())
                        .givenProductId(originalProduct.getId())
                        .givenColorId(negro.getId())
                        .returnedQuantity(BigDecimal.ONE)
                        .givenQuantity(BigDecimal.ONE)
                        .build());

        assertThat(preview.getReturnedAmount()).isEqualByComparingTo("90.00");
        assertThat(preview.getGivenAmount()).isEqualByComparingTo("90.00");
        assertThat(preview.getDifferenceAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void previewExchange_cinchoSizeChangeWithDiscount_preservesPaidPrice() throws Exception {
        ProductEntity cincho = productRepository.save(ProductEntity.builder()
                .code("FOSS-99")
                .name("CINCHO FOSS 99")
                .salePrice(new BigDecimal("200.00"))
                .build());
        seedInventory(cincho.getId(), 5);

        KioskPosSaleResponse discountedSale = kioskPosService.createSale(KioskPosSaleRequest.builder()
                .kioskLocationId(kiosk.getId())
                .paymentMethod("EFECTIVO")
                .amountReceived(new BigDecimal("160.00"))
                .manualDiscountPercent(new BigDecimal("20"))
                .items(List.of(
                        KioskPosSaleRequest.ItemRequest.builder()
                                .productId(cincho.getId())
                                .colorId(negro.getId())
                                .quantity(BigDecimal.ONE)
                                .size("34")
                                .build()))
                .build());
        KioskSaleItemEntity saleItem = saleItemRepository.findByKioskSaleIdOrderByIdAsc(discountedSale.getId()).get(0);
        saleItem.setProductName("CINCHO FOSS 99 T. 34");
        saleItemRepository.save(saleItem);

        KioskExchangePreviewResponse preview = kioskExchangeService.previewExchange(
                KioskExchangePreviewRequest.builder()
                        .kioskLocationId(kiosk.getId())
                        .originalSaleId(discountedSale.getId())
                        .originalSaleItemId(saleItem.getId())
                        .givenProductId(cincho.getId())
                        .givenColorId(negro.getId())
                        .givenSize("36")
                        .returnedQuantity(BigDecimal.ONE)
                        .givenQuantity(BigDecimal.ONE)
                        .build());

        assertThat(preview.getReturnedAmount()).isEqualByComparingTo("160.00");
        assertThat(preview.getGivenAmount()).isEqualByComparingTo("160.00");
        assertThat(preview.getDifferenceAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void previewExchange_sameProductPrice_ignoresInvoicePackaging() throws Exception {
        ProductEntity packaging = productRepository.save(ProductEntity.builder()
                .code("SUM-EX-001")
                .name("Bolsa cambio")
                .salePrice(new BigDecimal("15.00"))
                .build());
        ProductEntity samePriceProduct = productRepository.save(ProductEntity.builder()
                .code("SAME-180")
                .name("Cartera mismo precio")
                .salePrice(new BigDecimal("180.00"))
                .build());
        seedInventory(packaging.getId(), 5);
        seedInventory(samePriceProduct.getId(), 5);

        KioskPosSaleResponse sale = kioskPosService.createSale(KioskPosSaleRequest.builder()
                .kioskLocationId(kiosk.getId())
                .paymentMethod("EFECTIVO")
                .amountReceived(new BigDecimal("195.00"))
                .chargeWithoutDiscount(true)
                .items(List.of(
                        item(originalProduct.getId(), negro.getId(), BigDecimal.ONE),
                        item(packaging.getId(), negro.getId(), BigDecimal.ONE)))
                .build());
        KioskSaleItemEntity saleItem = saleItemRepository.findByKioskSaleIdOrderByIdAsc(sale.getId()).stream()
                .filter(row -> Objects.equals(row.getProductId(), originalProduct.getId()))
                .findFirst()
                .orElseThrow();

        KioskExchangePreviewResponse preview = kioskExchangeService.previewExchange(
                KioskExchangePreviewRequest.builder()
                        .kioskLocationId(kiosk.getId())
                        .originalSaleId(sale.getId())
                        .originalSaleItemId(saleItem.getId())
                        .givenProductId(samePriceProduct.getId())
                        .givenColorId(negro.getId())
                        .returnedQuantity(BigDecimal.ONE)
                        .givenQuantity(BigDecimal.ONE)
                        .build());

        assertThat(preview.getPackagingCreditAmount()).isEqualByComparingTo("15.00");
        assertThat(preview.getPackagingReturnedAmount()).isEqualByComparingTo("0.00");
        assertThat(preview.getReturnedAmount()).isEqualByComparingTo("180.00");
        assertThat(preview.getGivenAmount()).isEqualByComparingTo("180.00");
        assertThat(preview.getDifferenceAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void previewExchange_differentProductPrice_includesInvoicePackaging() throws Exception {
        ProductEntity packaging = productRepository.save(ProductEntity.builder()
                .code("SUM-EX-002")
                .name("Bolsa cambio diff")
                .salePrice(new BigDecimal("15.00"))
                .build());
        seedInventory(packaging.getId(), 5);

        KioskPosSaleResponse sale = kioskPosService.createSale(KioskPosSaleRequest.builder()
                .kioskLocationId(kiosk.getId())
                .paymentMethod("EFECTIVO")
                .amountReceived(new BigDecimal("195.00"))
                .chargeWithoutDiscount(true)
                .items(List.of(
                        item(originalProduct.getId(), negro.getId(), BigDecimal.ONE),
                        item(packaging.getId(), negro.getId(), BigDecimal.ONE)))
                .build());
        KioskSaleItemEntity saleItem = saleItemRepository.findByKioskSaleIdOrderByIdAsc(sale.getId()).stream()
                .filter(row -> Objects.equals(row.getProductId(), originalProduct.getId()))
                .findFirst()
                .orElseThrow();

        KioskExchangePreviewResponse preview = kioskExchangeService.previewExchange(
                KioskExchangePreviewRequest.builder()
                        .kioskLocationId(kiosk.getId())
                        .originalSaleId(sale.getId())
                        .originalSaleItemId(saleItem.getId())
                        .givenProductId(newProduct.getId())
                        .givenColorId(negro.getId())
                        .returnedQuantity(BigDecimal.ONE)
                        .givenQuantity(BigDecimal.ONE)
                        .build());

        assertThat(preview.getPackagingCreditAmount()).isEqualByComparingTo("15.00");
        assertThat(preview.getPackagingReturnedAmount()).isEqualByComparingTo("15.00");
        assertThat(preview.getReturnedAmount()).isEqualByComparingTo("195.00");
        assertThat(preview.getGivenAmount()).isEqualByComparingTo("250.00");
        assertThat(preview.getDifferenceAmount()).isEqualByComparingTo("55.00");
    }

    @Test
    void previewExchange_allowsNegativeDifferenceAsCustomerCredit() throws Exception {
        KioskSaleItemEntity saleItem = saleItemRepository.findByKioskSaleIdOrderByIdAsc(originalSale.getId()).get(0);
        ProductEntity cheaper = productRepository.save(ProductEntity.builder()
                .code("CHEAP-001")
                .name("Cartera barata")
                .salePrice(new BigDecimal("50.00"))
                .build());
        seedInventory(cheaper.getId(), 5);

        KioskExchangePreviewResponse preview = kioskExchangeService.previewExchange(
                KioskExchangePreviewRequest.builder()
                        .kioskLocationId(kiosk.getId())
                        .originalSaleId(originalSale.getId())
                        .originalSaleItemId(saleItem.getId())
                        .givenProductId(cheaper.getId())
                        .givenColorId(negro.getId())
                        .returnedQuantity(BigDecimal.ONE)
                        .givenQuantity(BigDecimal.ONE)
                        .build());

        assertThat(preview.getDifferenceAmount()).isEqualByComparingTo("-130.00");
    }

    @Test
    void completeExchange_negativeDifference_pendingAuthorizationWithoutSale() throws Exception {
        KioskSaleItemEntity saleItem = saleItemRepository.findByKioskSaleIdOrderByIdAsc(originalSale.getId()).get(0);
        ProductEntity cheaper = productRepository.save(ProductEntity.builder()
                .code("CHEAP-002")
                .name("Cartera barata 2")
                .salePrice(new BigDecimal("50.00"))
                .build());
        seedInventory(cheaper.getId(), 5);
        int stockOriginalBefore = currentStock(originalProduct.getId());
        int stockCheaperBefore = currentStock(cheaper.getId());

        KioskExchangeCompleteResponse result = kioskExchangeService.completeExchange(
                KioskExchangeCompleteRequest.builder()
                        .kioskLocationId(kiosk.getId())
                        .originalSaleId(originalSale.getId())
                        .originalSaleItemId(saleItem.getId())
                        .givenProductId(cheaper.getId())
                        .givenColorId(negro.getId())
                        .returnedQuantity(BigDecimal.ONE)
                        .givenQuantity(BigDecimal.ONE)
                        .physicalSlipNumber("BC-CREDIT-001")
                        .reason("Cambio con saldo a favor")
                        .build());

        assertThat(result.getSlip().getStatus()).isEqualTo("PENDING_AUTHORIZATION");
        assertThat(result.getSlip().getDifferenceAmount()).isEqualByComparingTo("-130.00");
        assertThat(result.getSale()).isNull();
        assertThat(currentStock(originalProduct.getId())).isEqualTo(stockOriginalBefore);
        assertThat(currentStock(cheaper.getId())).isEqualTo(stockCheaperBefore);
    }

    @Test
    void completeExchange_multipleGivenItems_chargesSumDifference() throws Exception {
        KioskSaleItemEntity saleItem = saleItemRepository.findByKioskSaleIdOrderByIdAsc(originalSale.getId()).get(0);
        ProductEntity extra = productRepository.save(ProductEntity.builder()
                .code("EXTRA-001")
                .name("Producto extra")
                .salePrice(new BigDecimal("40.00"))
                .build());
        seedInventory(extra.getId(), 5);
        int stockNewBefore = currentStock(newProduct.getId());
        int stockExtraBefore = currentStock(extra.getId());

        KioskExchangeCompleteResponse result = kioskExchangeService.completeExchange(
                KioskExchangeCompleteRequest.builder()
                        .kioskLocationId(kiosk.getId())
                        .originalSaleId(originalSale.getId())
                        .originalSaleItemId(saleItem.getId())
                        .returnedQuantity(BigDecimal.ONE)
                        .givenItems(List.of(
                                com.fossiles.fossilescorebackend.application.dto.request.KioskExchangeGivenItemRequest.builder()
                                        .productId(newProduct.getId())
                                        .colorId(negro.getId())
                                        .quantity(BigDecimal.ONE)
                                        .build(),
                                com.fossiles.fossilescorebackend.application.dto.request.KioskExchangeGivenItemRequest.builder()
                                        .productId(extra.getId())
                                        .colorId(negro.getId())
                                        .quantity(BigDecimal.ONE)
                                        .build()))
                        .physicalSlipNumber("BC-MULTI-001")
                        .paymentMethod("EFECTIVO")
                        .amountReceived(new BigDecimal("200.00"))
                        .reason("Cambio por dos productos")
                        .build());

        // given 250+40=290, returned 180, diff 110
        assertThat(result.getSlip().getStatus()).isEqualTo("COMPLETED");
        assertThat(result.getSlip().getGivenItems()).hasSize(2);
        assertThat(result.getSlip().getGivenAmount()).isEqualByComparingTo("290.00");
        assertThat(result.getSlip().getDifferenceAmount()).isEqualByComparingTo("110.00");
        assertThat(result.getSale().getTotalAmount()).isEqualByComparingTo("110.00");
        assertThat(currentStock(newProduct.getId())).isEqualTo(stockNewBefore - 1);
        assertThat(currentStock(extra.getId())).isEqualTo(stockExtraBefore - 1);

        List<KioscoMovementEntity> slipMoves =
                kioscoMovementRepository.findByPhysicalSlipNumber("BC-MULTI-001");
        assertThat(slipMoves).hasSize(3); // 1 ingreso + 2 egresos
        assertThat(slipMoves).filteredOn(m -> m.getStockAfter() > m.getStockBefore())
                .extracting(KioscoMovementEntity::getMovementType)
                .containsOnly(KioscoMovementType.CAMBIO);
        assertThat(slipMoves).filteredOn(m -> m.getStockAfter() < m.getStockBefore())
                .extracting(KioscoMovementEntity::getMovementType)
                .containsOnly(KioscoMovementType.CAMBIO);
    }

    // ---- Varias líneas de la factura devueltas en una misma boleta (N→M) ----

    /** Factura con dos productos de distinto precio: originalProduct (180) y cheaper (100). */
    private KioskPosSaleResponse createTwoLineSale(ProductEntity cheaper) throws Exception {
        return kioskPosService.createSale(KioskPosSaleRequest.builder()
                .kioskLocationId(kiosk.getId())
                .paymentMethod("EFECTIVO")
                .amountReceived(new BigDecimal("280.00"))
                .chargeWithoutDiscount(true)
                .items(List.of(
                        item(originalProduct.getId(), negro.getId(), BigDecimal.ONE),
                        item(cheaper.getId(), negro.getId(), BigDecimal.ONE)))
                .build());
    }

    private ProductEntity seedCheaperProduct() {
        ProductEntity cheaper = productRepository.save(ProductEntity.builder()
                .code("OLD-002")
                .name("Billetera Promo")
                .salePrice(new BigDecimal("100.00"))
                .build());
        seedInventory(cheaper.getId(), 5);
        return cheaper;
    }

    private static com.fossiles.fossilescorebackend.application.dto.request.KioskExchangeReturnedItemRequest returned(
            Long saleItemId, String qty) {
        return com.fossiles.fossilescorebackend.application.dto.request.KioskExchangeReturnedItemRequest.builder()
                .originalSaleItemId(saleItemId)
                .quantity(new BigDecimal(qty))
                .build();
    }

    private static com.fossiles.fossilescorebackend.application.dto.request.KioskExchangeGivenItemRequest given(
            Long productId, Long colorId, String qty) {
        return com.fossiles.fossilescorebackend.application.dto.request.KioskExchangeGivenItemRequest.builder()
                .productId(productId)
                .colorId(colorId)
                .quantity(new BigDecimal(qty))
                .build();
    }

    @Test
    void previewExchange_multipleReturnedLines_sameUnitPrice_usesWeightedAverage() throws Exception {
        ProductEntity cheaper = seedCheaperProduct();
        KioskPosSaleResponse sale = createTwoLineSale(cheaper);
        List<KioskSaleItemEntity> items = saleItemRepository.findByKioskSaleIdOrderByIdAsc(sale.getId());

        KioskExchangePreviewResponse preview = kioskExchangeService.previewExchange(
                KioskExchangePreviewRequest.builder()
                        .kioskLocationId(kiosk.getId())
                        .originalSaleId(sale.getId())
                        .returnedItems(List.of(returned(items.get(0).getId(), "1"), returned(items.get(1).getId(), "1")))
                        .givenItems(List.of(given(newProduct.getId(), negro.getId(), "2")))
                        .pricingMode("SAME_UNIT_PRICE")
                        .build());

        assertThat(preview.getReturnedItems()).hasSize(2);
        assertThat(preview.getReturnedItems()).extracting(l -> l.getSaleItemId())
                .containsExactly(items.get(0).getId(), items.get(1).getId());
        assertThat(preview.getReturned().getSaleItemId()).isEqualTo(items.get(0).getId());
        assertThat(preview.getReturnedAmount()).isEqualByComparingTo("280.00");
        // 180 + 100 devueltos = 280 → 140 c/u promedio; 2 unidades entregadas = 280.
        assertThat(preview.getGiven().getUnitPrice()).isEqualByComparingTo("140.00");
        assertThat(preview.getGivenAmount()).isEqualByComparingTo("280.00");
        assertThat(preview.getDifferenceAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void previewExchange_multipleReturnedLines_catalogGiven_chargesDifference() throws Exception {
        ProductEntity cheaper = seedCheaperProduct();
        KioskPosSaleResponse sale = createTwoLineSale(cheaper);
        List<KioskSaleItemEntity> items = saleItemRepository.findByKioskSaleIdOrderByIdAsc(sale.getId());

        KioskExchangePreviewResponse preview = kioskExchangeService.previewExchange(
                KioskExchangePreviewRequest.builder()
                        .kioskLocationId(kiosk.getId())
                        .originalSaleId(sale.getId())
                        .returnedItems(List.of(returned(items.get(0).getId(), "1"), returned(items.get(1).getId(), "1")))
                        .givenItems(List.of(given(newProduct.getId(), negro.getId(), "2")))
                        .pricingMode("CATALOG_GIVEN")
                        .build());

        assertThat(preview.getReturnedAmount()).isEqualByComparingTo("280.00");
        assertThat(preview.getGivenAmount()).isEqualByComparingTo("500.00");
        assertThat(preview.getDifferenceAmount()).isEqualByComparingTo("220.00");
    }

    @Test
    void completeExchange_multipleReturnedLines_registersAllIngresosAndChargesDifference() throws Exception {
        ProductEntity cheaper = seedCheaperProduct();
        KioskPosSaleResponse sale = createTwoLineSale(cheaper);
        List<KioskSaleItemEntity> items = saleItemRepository.findByKioskSaleIdOrderByIdAsc(sale.getId());
        int oldBefore = currentStock(originalProduct.getId());
        int cheaperBefore = currentStock(cheaper.getId());
        int newBefore = currentStock(newProduct.getId());

        KioskExchangeCompleteResponse result = kioskExchangeService.completeExchange(
                KioskExchangeCompleteRequest.builder()
                        .kioskLocationId(kiosk.getId())
                        .originalSaleId(sale.getId())
                        .returnedItems(List.of(returned(items.get(0).getId(), "1"), returned(items.get(1).getId(), "1")))
                        .givenItems(List.of(given(newProduct.getId(), negro.getId(), "2")))
                        .pricingMode("CATALOG_GIVEN")
                        .physicalSlipNumber("BC-MULTI-RET-001")
                        .paymentMethod("EFECTIVO")
                        .amountReceived(new BigDecimal("220.00"))
                        .reason("Cambio de dos productos")
                        .build());

        KioskExchangeSlipResponse slip = result.getSlip();
        assertThat(slip.getStatus()).isEqualTo("COMPLETED");
        assertThat(slip.getReturnedItems()).hasSize(2);
        assertThat(slip.getReturnedQuantity()).isEqualByComparingTo("2");
        assertThat(slip.getReturnedAmount()).isEqualByComparingTo("280.00");
        assertThat(slip.getDifferenceAmount()).isEqualByComparingTo("220.00");
        assertThat(result.getSale().getTotalAmount()).isEqualByComparingTo("220.00");

        assertThat(currentStock(originalProduct.getId())).isEqualTo(oldBefore + 1);
        assertThat(currentStock(cheaper.getId())).isEqualTo(cheaperBefore + 1);
        assertThat(currentStock(newProduct.getId())).isEqualTo(newBefore - 2);

        List<KioscoMovementEntity> slipMoves = kioscoMovementRepository.findByPhysicalSlipNumber("BC-MULTI-RET-001");
        assertThat(slipMoves).hasSize(3); // 2 ingresos + 1 egreso
        assertThat(slipMoves).filteredOn(m -> m.getStockAfter() > m.getStockBefore()).hasSize(2);
        assertThat(slipMoves).extracting(KioscoMovementEntity::getMovementType)
                .containsOnly(KioscoMovementType.CAMBIO);

        // Cada línea que ingresa queda ligada a su movimiento y a su línea de factura.
        var rows = exchangeSlipReturnedItemRepository.findByExchangeSlipIdOrderByLineNoAsc(slip.getId());
        assertThat(rows).extracting(r -> r.getOriginalSaleItemId())
                .containsExactly(items.get(0).getId(), items.get(1).getId());
        assertThat(rows).allMatch(r -> r.getReturnMovementId() != null);
        assertThat(rows).extracting(r -> r.getReturnMovementId()).doesNotHaveDuplicates();
    }

    @Test
    void authorizeExchange_multipleReturnedLines_registersEveryIngreso() throws Exception {
        ProductEntity cheaper = seedCheaperProduct();
        KioskPosSaleResponse sale = createTwoLineSale(cheaper);
        List<KioskSaleItemEntity> items = saleItemRepository.findByKioskSaleIdOrderByIdAsc(sale.getId());
        int oldBefore = currentStock(originalProduct.getId());
        int cheaperBefore = currentStock(cheaper.getId());

        KioskExchangeCompleteResponse pending = kioskExchangeService.completeExchange(
                KioskExchangeCompleteRequest.builder()
                        .kioskLocationId(kiosk.getId())
                        .originalSaleId(sale.getId())
                        .returnedItems(List.of(returned(items.get(0).getId(), "1"), returned(items.get(1).getId(), "1")))
                        .givenItems(List.of(given(newProduct.getId(), negro.getId(), "2")))
                        .pricingMode("SAME_UNIT_PRICE")
                        .physicalSlipNumber("BC-MULTI-RET-AUTH")
                        .reason("Cambio de estilo")
                        .build());

        assertThat(pending.getSlip().getStatus()).isEqualTo("PENDING_AUTHORIZATION");
        assertThat(pending.getSlip().getReturnedItems()).hasSize(2);
        // Hasta que la supervisora apruebe no se mueve inventario.
        assertThat(currentStock(originalProduct.getId())).isEqualTo(oldBefore);
        assertThat(kioscoMovementRepository.findByPhysicalSlipNumber("BC-MULTI-RET-AUTH")).isEmpty();

        RoleEntity adminRole = roleRepository.save(RoleEntity.builder().name("ADMIN").build());
        UserEntity admin = userRepository.save(UserEntity.builder()
                .username("admin.exchange.multi")
                .email("admin.exchange.multi@fossiles.test")
                .password("x")
                .status("ACTIVE")
                .roles(new HashSet<>(Set.of(adminRole)))
                .build());
        when(securityUtil.getCurrentUserId()).thenReturn(admin.getId());

        KioskExchangeSlipResponse authorized = kioskExchangeService.authorizeExchange(
                pending.getSlip().getId(), kiosk.getId());

        assertThat(authorized.getStatus()).isEqualTo("COMPLETED");
        assertThat(currentStock(originalProduct.getId())).isEqualTo(oldBefore + 1);
        assertThat(currentStock(cheaper.getId())).isEqualTo(cheaperBefore + 1);
        assertThat(kioscoMovementRepository.findByPhysicalSlipNumber("BC-MULTI-RET-AUTH")).hasSize(3);
        assertThat(exchangeSlipReturnedItemRepository.findByExchangeSlipIdOrderByLineNoAsc(authorized.getId()))
                .allMatch(r -> r.getReturnMovementId() != null);
    }

    @Test
    void previewExchange_multipleReturnedLines_rejectsInvalidSelections() throws Exception {
        ProductEntity cheaper = seedCheaperProduct();
        KioskPosSaleResponse sale = createTwoLineSale(cheaper);
        List<KioskSaleItemEntity> items = saleItemRepository.findByKioskSaleIdOrderByIdAsc(sale.getId());
        Long firstId = items.get(0).getId();
        var oneGiven = List.of(given(newProduct.getId(), negro.getId(), "1"));

        assertThatThrownBy(() -> kioskExchangeService.previewExchange(
                KioskExchangePreviewRequest.builder()
                        .kioskLocationId(kiosk.getId())
                        .originalSaleId(sale.getId())
                        .returnedItems(List.of(returned(firstId, "1"), returned(firstId, "1")))
                        .givenItems(oneGiven)
                        .build()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("dos veces");

        assertThatThrownBy(() -> kioskExchangeService.previewExchange(
                KioskExchangePreviewRequest.builder()
                        .kioskLocationId(kiosk.getId())
                        .originalSaleId(sale.getId())
                        .returnedItems(List.of(returned(firstId, "2")))
                        .givenItems(oneGiven)
                        .build()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("vendida");

        // Una línea que no es de esta factura (la de la factura del setUp).
        Long foreignId = saleItemRepository.findByKioskSaleIdOrderByIdAsc(originalSale.getId()).get(0).getId();
        assertThatThrownBy(() -> kioskExchangeService.previewExchange(
                KioskExchangePreviewRequest.builder()
                        .kioskLocationId(kiosk.getId())
                        .originalSaleId(sale.getId())
                        .returnedItems(List.of(returned(firstId, "1"), returned(foreignId, "1")))
                        .givenItems(oneGiven)
                        .build()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("no pertenece");
    }

    @Test
    void sharedReturnedUnitPrice_averagesOnlyWhenPricesDiffer_andKeepsTotalExact() {
        BigDecimal same = KioskExchangeService.sharedReturnedUnitPrice(
                List.of(new BigDecimal("90.00"), new BigDecimal("90.00")),
                new BigDecimal("180.00"), new BigDecimal("2"));
        assertThat(same).isEqualByComparingTo("90.00");

        BigDecimal avg = KioskExchangeService.sharedReturnedUnitPrice(
                List.of(new BigDecimal("180.00"), new BigDecimal("100.00")),
                new BigDecimal("280.00"), new BigDecimal("2"));
        assertThat(avg).isEqualByComparingTo("140.00");

        // 100 + 100 + 101 = 301 en 3 u.: 100.33 × 3 = 300.99 descuadra, se conserva la precisión.
        BigDecimal precise = KioskExchangeService.sharedReturnedUnitPrice(
                List.of(new BigDecimal("100.00"), new BigDecimal("100.00"), new BigDecimal("101.00")),
                new BigDecimal("301.00"), new BigDecimal("3"));
        assertThat(precise.multiply(new BigDecimal("3")).setScale(2, java.math.RoundingMode.HALF_UP))
                .isEqualByComparingTo("301.00");
    }

    private int currentStock(Long productId) {
        return kioscoStockRepository.findByLocationIdAndProductIdAndColorId(kiosk.getId(), productId, negro.getId())
                .map(KioscoStockEntity::getCurrentStock)
                .orElse(0);
    }

    private void seedInventory(Long productId, int quantity) {
        inventoryRepository.save(ProductInventoryLocation.builder()
                .productId(productId)
                .locationId(kiosk.getId())
                .colorId(negro.getId())
                .quantity(new BigDecimal(quantity))
                .build());

        kioscoStockRepository.save(KioscoStockEntity.builder()
                .locationId(kiosk.getId())
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
