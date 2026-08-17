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
        assertThat(slipMoves).extracting(KioscoMovementEntity::getMovementType)
                .containsExactlyInAnyOrder(KioscoMovementType.CAMBIO, KioscoMovementType.DEVOLUCION_A_CLIENTE);
        assertThat(slipMoves).noneMatch(m -> m.getMovementType() == KioscoMovementType.DEVOLUCION_CLIENTE);
        assertThat(slipMoves).noneMatch(m -> m.getMovementType() == KioscoMovementType.VENTA);

        KioskExchangeSlipEntity slip = exchangeSlipRepository.findById(result.getSlip().getId()).orElseThrow();
        assertThat(slip.getReturnMovementId()).isNotNull();
        assertThat(slip.getGivenMovementId()).isNotNull();

        // La venta POS de diferencia no debe haber creado VENTA de stock del producto entregado.
        long ventasNewProduct = kioscoMovementRepository
                .findByLocationIdOrderByCreatedAtDesc(kiosk.getId()).stream()
                .filter(m -> m.getMovementType() == KioscoMovementType.VENTA)
                .filter(m -> {
                    KioscoStockEntity stock = kioscoStockRepository.findById(m.getKioscoStockId()).orElse(null);
                    return stock != null && Objects.equals(stock.getProductId(), newProduct.getId());
                })
                .count();
        assertThat(ventasNewProduct).isZero();
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
                .containsExactlyInAnyOrder(KioscoMovementType.CAMBIO, KioscoMovementType.DEVOLUCION_A_CLIENTE);
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
