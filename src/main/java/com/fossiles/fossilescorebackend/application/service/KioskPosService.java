package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.KioskMainSheetCertificationRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskCashExpenseRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskCashSessionCloseRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskCashSessionOpenRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskPosDepositSlipUpdateRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskPosPromotionEstimateRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskPosSalePaymentUpdateRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskPosSaleRestoreRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskPosSaleRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskSaleVoidRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskPromotionRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskPromotionTierRequest;
import com.fossiles.fossilescorebackend.application.dto.response.KioskCashExpenseResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskCashSessionResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskCashSessionDailySummaryResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskCashCloseReportResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskCashSessionHistoryItemResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskPendingDepositSummaryResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskPosContextResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskCustomerProfileResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskPromotionResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskPromotionTierResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskPosManagerDashboardResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskPosPromotionEstimateResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskDisbursementReportRowResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskBankDepositReportResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskBankDepositReportRowResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskMainSheetReportResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskVoucherReportResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskVoucherReportRowResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskPosReportsResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskPosSaleResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskProductAvailabilityResponse;
import com.fossiles.fossilescorebackend.application.dto.response.TaxpayerLookupResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.util.KioskAccessHelper;
import com.fossiles.fossilescorebackend.application.util.ProductAudienceCategory;
import com.fossiles.fossilescorebackend.application.util.ProductHardwareCondition;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoPhysicalCountEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskCashExpenseEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskCashSessionEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskPromotionEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskPromotionTierEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSaleItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSaleSequenceEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoStockEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductCategoryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductInventoryLocation;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaxInvoiceEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoPhysicalCountRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskCashExpenseRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskCashSessionRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskPromotionRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskPromotionTierRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSaleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSaleSequenceRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoStockRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductCategoryRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductInventoryLocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaxInvoiceRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import com.fossiles.fossilescorebackend.infrastructure.config.FelEmissionProperties;
import com.fossiles.fossilescorebackend.infrastructure.config.KioskPosDepositReportProperties;
import com.fossiles.fossilescorebackend.infrastructure.config.KioskPosVoucherReportProperties;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductInventorySizesJson;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class KioskPosService {

    private static final DateTimeFormatter SALE_NUMBER_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final BigDecimal CASH_OPENING_AMOUNT = new BigDecimal("300");
    private static final String CASH_SESSION_OPEN = "OPEN";
    private static final String CASH_SESSION_CLOSED = "CLOSED";
    private static final String SALE_STATUS_VOID = "VOID";
    private static final Pattern CARD_LAST4_PATTERN = Pattern.compile("^\\d{4}$");
    private static final Set<String> ALLOWED_CARD_BRANDS = Set.of("VISA", "MC", "AMEX");
    private static final Pattern POS_SALE_NUMBER_PATTERN = Pattern.compile("^POS-(\\d{8})-(\\d{4})$", Pattern.CASE_INSENSITIVE);
    /** Supervisores autorizados para certificar la hoja principal. */
    private static final List<String> MAIN_SHEET_REVIEWERS = List.of(
            "GUSTAVO CASTRO",
            "ROBERTO LIQUE",
            "FATIMA ZACARIAS"
    );

    private final SecurityUtil securityUtil;
    private final UserRepository userRepository;
    private final LocationRepository locationRepository;
    private final ProductRepository productRepository;
    private final ColorRepository colorRepository;
    private final ProductCategoryRepository productCategoryRepository;
    private final KioscoStockRepository kioscoStockRepository;
    private final ProductInventoryLocationRepository productInventoryLocationRepository;
    private final ProductInventoryService productInventoryService;
    private final KioskSaleRepository kioskSaleRepository;
    private final KioskCashSessionRepository kioskCashSessionRepository;
    private final KioskCashExpenseRepository kioskCashExpenseRepository;
    private final KioskSaleSequenceRepository kioskSaleSequenceRepository;
    private final KioskPromotionRepository kioskPromotionRepository;
    private final KioskPromotionTierRepository kioskPromotionTierRepository;
    private final FelReceptorLookupService felReceptorLookupService;
    private final TaxInvoiceService taxInvoiceService;
    private final FelEmissionProperties felEmissionProperties;
    private final KioskPosDepositReportProperties depositReportProperties;
    private final KioskPosVoucherReportProperties voucherReportProperties;
    private final TaxInvoiceRepository taxInvoiceRepository;
    private final KioscoPhysicalCountRepository kioscoPhysicalCountRepository;
    private final KioscoInventoryService kioscoInventoryService;

    @Transactional(readOnly = true)
    public KioskPosContextResponse getCurrentContext(
            Long kioskLocationId,
            String search,
            Long categoryId,
            String colorName
    ) throws BusinessException {
        UserEntity user = getCurrentUserOrThrow();
        boolean admin = KioskAccessHelper.hasAllKiosksAccess(user);
        List<LocationEntity> availableKiosks = resolveAvailableKiosks(user, admin);
        LocationEntity kiosk = resolveTargetKiosk(availableKiosks, kioskLocationId);

        List<KioscoStockEntity> kioscoStockRows = kioscoStockRepository
                .findByLocationIdOrderByProductIdAscColorIdAscHardwareConditionAsc(kiosk.getId());
        List<ProductInventoryLocation> legacyRowsAtKiosk = productInventoryLocationRepository
                .findByLocationId(kiosk.getId());

        List<ProductInventoryLocation> legacyRows = kioscoStockRows.isEmpty()
                ? legacyRowsAtKiosk
                : List.of();

        Set<Long> productIds = (kioscoStockRows.isEmpty() ? legacyRows.stream().map(ProductInventoryLocation::getProductId)
                        : kioscoStockRows.stream().map(KioscoStockEntity::getProductId))
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toSet());

        Map<Long, ProductEntity> productsById = productRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(ProductEntity::getId, item -> item));

        Set<Long> categoryIds = productsById.values().stream()
                .map(ProductEntity::getCategoryId)
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toSet());
        Map<Long, ProductCategoryEntity> categoriesById = productCategoryRepository.findAllById(categoryIds).stream()
                .collect(Collectors.toMap(ProductCategoryEntity::getId, item -> item));

        String searchNorm = safeTrim(search).toLowerCase(Locale.ROOT);
        String colorNorm = safeTrim(colorName).toLowerCase(Locale.ROOT);

        List<KioskPosContextResponse.InventoryItem> rawInventory = kioscoStockRows.isEmpty()
                ? legacyRows.stream()
                .map(row -> {
                    ProductEntity product = productsById.get(row.getProductId());
                    ProductCategoryEntity category = product != null && product.getCategoryId() != null
                            ? categoriesById.get(product.getCategoryId())
                            : null;
                    return KioskPosContextResponse.InventoryItem.builder()
                            .productId(row.getProductId())
                            .productCode(product != null ? product.getCode() : "")
                            .productName(product != null ? product.getName() : "Producto")
                            .productImageUrl(product != null ? safeTrim(product.getImageUrl()) : "")
                            .colorId(row.getColorId())
                            .colorName(row.getColor() != null ? row.getColor().getName() : "")
                            .categoryId(category != null ? category.getId() : null)
                            .categoryName(category != null ? category.getName() : "")
                            .audienceCategory(product != null
                                    ? ProductAudienceCategory.normalizeProductAudience(product.getAudienceCategory())
                                    : ProductAudienceCategory.UNISEX)
                            .quantity(row.getQuantity() != null ? row.getQuantity() : BigDecimal.ZERO)
                            .suggestedUnitPrice(resolvePosUnitPrice(product))
                            .sizes(positiveSizesMap(row.getSizesData()))
                            .hardwareCondition(ProductHardwareCondition.NUEVO)
                            .hardwareLabel(ProductHardwareCondition.label(ProductHardwareCondition.NUEVO))
                            .build();
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList())
                : kioscoStockRows.stream()
                .map(row -> {
                    ProductEntity product = productsById.get(row.getProductId());
                    ProductCategoryEntity category = product != null && product.getCategoryId() != null
                            ? categoriesById.get(product.getCategoryId())
                            : null;
                    String hardware = ProductHardwareCondition.normalize(row.getHardwareCondition());
                    if (hardware == null) {
                        hardware = ProductHardwareCondition.NUEVO;
                    }
                    // Solo kiosco_stock: no mezclar tallas legacy (causaba "hay stock" en UI y 0 al cobrar).
                    Map<String, BigDecimal> sizes = resolveKioscoSizes(row);
                    BigDecimal quantity = sizes != null && !sizes.isEmpty()
                            ? sizes.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                            : BigDecimal.valueOf(row.getCurrentStock() != null ? row.getCurrentStock() : 0);
                    return KioskPosContextResponse.InventoryItem.builder()
                            .productId(row.getProductId())
                            .productCode(product != null ? product.getCode() : "")
                            .productName(product != null ? product.getName() : "Producto")
                            .productImageUrl(product != null ? safeTrim(product.getImageUrl()) : "")
                            .colorId(row.getColorId())
                            .colorName(row.getColor() != null ? row.getColor().getName() : "")
                            .categoryId(category != null ? category.getId() : null)
                            .categoryName(category != null ? category.getName() : "")
                            .audienceCategory(product != null
                                    ? ProductAudienceCategory.normalizeProductAudience(product.getAudienceCategory())
                                    : ProductAudienceCategory.UNISEX)
                            .quantity(quantity)
                            .suggestedUnitPrice(resolvePosUnitPrice(product))
                            .sizes(sizes)
                            .hardwareCondition(hardware)
                            .hardwareLabel(ProductHardwareCondition.label(hardware))
                            .build();
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        appendMissingPackagingCatalogItems(rawInventory, productsById, categoriesById);

        List<KioskPosContextResponse.InventoryItem> inventory = rawInventory.stream()
                .filter(item -> categoryId == null || Objects.equals(item.getCategoryId(), categoryId))
                .filter(item -> colorNorm.isEmpty()
                        || safeTrim(item.getColorName()).toLowerCase(Locale.ROOT).contains(colorNorm))
                .filter(item -> {
                    if (searchNorm.isEmpty()) {
                        return true;
                    }
                    String text = (item.getProductCode() + " " + item.getProductName() + " " + item.getColorName())
                            .toLowerCase(Locale.ROOT);
                    return text.contains(searchNorm);
                })
                .sorted(Comparator.comparing(KioskPosContextResponse.InventoryItem::getProductName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(item -> String.valueOf(item.getColorName()), String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());

        return KioskPosContextResponse.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .fullName(buildUserFullName(user))
                .admin(admin)
                .kioskId(kiosk.getId())
                .kioskCode(kiosk.getCode())
                .kioskName(kiosk.getName())
                .posTestMode(isPosTestSale(kiosk))
                .posOpeningCashAmount(resolvePosOpeningCashAmount(kiosk))
                .kiosks(availableKiosks.stream()
                        .map(item -> KioskPosContextResponse.KioskOption.builder()
                                .kioskId(item.getId())
                                .kioskCode(item.getCode())
                                .kioskName(item.getName())
                                .posOpeningCashAmount(resolvePosOpeningCashAmount(item))
                                .build())
                        .collect(Collectors.toList()))
                .inventory(inventory)
                .build();
    }

    public KioskPosSaleResponse createSale(KioskPosSaleRequest request) throws BusinessException, ResourceNotFoundException {
        if (request == null || request.getItems() == null || request.getItems().isEmpty()) {
            throw new BusinessException("Debes agregar al menos un producto para registrar la venta.");
        }

        UserEntity user = getCurrentUserOrThrow();
        boolean admin = KioskAccessHelper.hasAllKiosksAccess(user);
        List<LocationEntity> availableKiosks = resolveAvailableKiosks(user, admin);
        LocationEntity kiosk = resolveTargetKiosk(availableKiosks, request.getKioskLocationId());
        KioskCashSessionEntity openSession = requireOpenCashSession(kiosk.getId());
        LocalDate saleDate = request.getSaleDate() != null ? request.getSaleDate() : GuatemalaDateTime.today();
        String normalizedPaymentMethod = normalizePaymentMethod(request.getPaymentMethod());
        validateCardFields(
                normalizedPaymentMethod,
                request.getCardAmount(),
                request.getCardAuthNumber(),
                request.getCardLast4(),
                request.getCardBrand(),
                request.getCardVoucherAmount(),
                true
        );

        String normalizedTaxId = normalizeTaxId(request.getCustomerTaxId());
        if (normalizedTaxId != null && !"CF".equals(normalizedTaxId) && !isValidGuatemalaNit(normalizedTaxId)) {
            throw new BusinessException("El NIT ingresado no es válido para Guatemala.");
        }

        boolean exchangeSale = request.getExchangeCreditAmount() != null
                && request.getExchangeCreditAmount().compareTo(BigDecimal.ZERO) > 0;
        KioskPromotionEntity promotion = null;
        if (!exchangeSale
                && (request.getManualDiscountPercent() == null
                || request.getManualDiscountPercent().compareTo(BigDecimal.ZERO) <= 0)
                && request.getPromotionId() != null) {
            promotion = resolvePromotionIfAny(request.getPromotionId(), saleDate, kiosk.getId());
        }

        Map<String, BigDecimal> aggregatedQty = aggregateItemQuantities(request.getItems());
        Map<String, ProductInventoryLocation> lockedInventory = lockAndValidateStock(kiosk.getId(), aggregatedQty);

        List<PreparedLine> preparedLines = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal totalItems = BigDecimal.ZERO;

        for (KioskPosSaleRequest.ItemRequest itemRequest : request.getItems()) {
            if (itemRequest == null || itemRequest.getProductId() == null) {
                throw new BusinessException("Todos los renglones deben tener producto.");
            }
            BigDecimal quantity = itemRequest.getQuantity();
            if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException("La cantidad debe ser mayor a cero para todos los productos.");
            }

            ProductEntity product = productRepository.findById(itemRequest.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product", itemRequest.getProductId()));

            ColorEntity color = null;
            if (itemRequest.getColorId() != null) {
                color = colorRepository.findById(itemRequest.getColorId())
                        .orElseThrow(() -> new ResourceNotFoundException("Color", itemRequest.getColorId()));
            }

            String sizeLabel = ProductInventorySizesJson.normalizeKey(itemRequest.getSize());
            String hardware = resolveItemHardwareCondition(itemRequest.getHardwareCondition());
            Optional<KioscoStockEntity> kioscoRowOpt = kioscoStockRepository
                    .findByLocationIdAndProductIdAndColorIdAndHardwareCondition(
                            kiosk.getId(), itemRequest.getProductId(), itemRequest.getColorId(), hardware);
            Optional<ProductInventoryLocation> invRowOpt = productInventoryLocationRepository
                    .findByProductIdAndLocationIdAndColorId(
                            itemRequest.getProductId(), kiosk.getId(), itemRequest.getColorId());
            boolean requiresSize = kioscoRowOpt
                    .map(row -> hasPositiveSizeBreakdown(row.getSizesData()))
                    .orElse(false);
            boolean kioscoModuleActive = !kioscoStockRepository
                    .findByLocationIdOrderByProductIdAscColorIdAscHardwareConditionAsc(kiosk.getId())
                    .isEmpty();
            // Con módulo kiosco activo no exigir talla por inventario legacy (desfasado).
            if (!kioscoModuleActive) {
                requiresSize = requiresSize || invRowOpt
                        .map(row -> hasPositiveSizeBreakdown(row.getSizesData()))
                        .orElse(false);
            }
            if (requiresSize && sizeLabel.isEmpty()) {
                throw new BusinessException("Debe seleccionar talla para " + product.getName() + ".");
            }

            BigDecimal unitPrice = resolvePosUnitPrice(product);
            if (exchangeSale
                    && itemRequest.getUnitPrice() != null
                    && itemRequest.getUnitPrice().compareTo(BigDecimal.ZERO) > 0) {
                unitPrice = itemRequest.getUnitPrice().setScale(2, RoundingMode.HALF_UP);
            }
            BigDecimal lineTotal = unitPrice.multiply(quantity).setScale(2, RoundingMode.HALF_UP);

            preparedLines.add(new PreparedLine(
                    product,
                    color,
                    sizeLabel.isEmpty() ? null : sizeLabel,
                    quantity,
                    unitPrice,
                    lineTotal
            ));
            subtotal = subtotal.add(lineTotal);
            totalItems = totalItems.add(quantity);
        }

        DiscountResolution discountResolution = resolveSaleDiscount(
                subtotal,
                preparedLines,
                exchangeSale,
                request,
                promotion,
                kiosk.getId(),
                saleDate
        );
        BigDecimal discountAmount = discountResolution.discountAmount();
        BigDecimal totalAmount = subtotal.subtract(discountAmount).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);

        validateSplitCardPayment(
                normalizedPaymentMethod,
                totalAmount,
                request.getCardAmount(),
                request.getCard2Amount(),
                request.getCard2AuthNumber(),
                request.getCard2Last4(),
                request.getCard2Brand(),
                request.getCard2VoucherAmount(),
                true
        );

        PaymentSnapshot payment = resolvePaymentSnapshot(
                normalizedPaymentMethod,
                totalAmount,
                request.getAmountReceived(),
                request.getCashAmount(),
                request.getCardAmount()
        );

        String saleNumber = generateSaleNumber(saleDate);

        KioskSaleEntity sale = KioskSaleEntity.builder()
                .saleNumber(saleNumber)
                .kioskLocationId(kiosk.getId())
                .soldByUserId(user.getId())
                .saleDate(saleDate)
                .soldAt(GuatemalaDateTime.now())
                .customerTaxId(normalizedTaxId)
                .customerName(safeTrim(request.getCustomerName()))
                .address(safeTrim(request.getAddress()))
                .phone(safeTrim(request.getPhone()))
                .email(normalizeFelReceptorEmail(safeTrim(request.getEmail())))
                .paymentMethod(normalizedPaymentMethod)
                .status("COMPLETED")
                .notes(safeTrim(request.getNotes()))
                .comments(safeTrim(request.getComments()))
                .subtotal(subtotal.setScale(2, RoundingMode.HALF_UP))
                .totalAmount(totalAmount)
                .discountAmount(discountAmount)
                .amountReceived(payment.amountReceived())
                .changeAmount(payment.changeAmount())
                .cashAmount(payment.cashAmount())
                .cardAmount(resolveStoredCardAmount(
                        normalizedPaymentMethod,
                        payment.cardAmount(),
                        request.getCardAmount(),
                        request.getCard2Amount()
                ))
                .cardAuthNumber(safeTrim(request.getCardAuthNumber()))
                .cardLast4(safeTrim(request.getCardLast4()))
                .cardBrand(resolveStoredCardBrand(
                        normalizedPaymentMethod,
                        resolveStoredCardAmount(
                                normalizedPaymentMethod,
                                payment.cardAmount(),
                                request.getCardAmount(),
                                request.getCard2Amount()
                        ),
                        request.getCardBrand()
                ))
                .cardVoucherAmount(resolveStoredCardVoucherAmount(
                        normalizedPaymentMethod,
                        resolveStoredCardAmount(
                                normalizedPaymentMethod,
                                payment.cardAmount(),
                                request.getCardAmount(),
                                request.getCard2Amount()
                        ),
                        request.getCardVoucherAmount()
                ))
                .card2Amount(isSplitCardPayment(request.getCard2Amount())
                        ? request.getCard2Amount().setScale(2, RoundingMode.HALF_UP)
                        : null)
                .card2AuthNumber(isSplitCardPayment(request.getCard2Amount())
                        ? safeTrim(request.getCard2AuthNumber())
                        : "")
                .card2Last4(isSplitCardPayment(request.getCard2Amount())
                        ? safeTrim(request.getCard2Last4())
                        : "")
                .card2Brand(isSplitCardPayment(request.getCard2Amount())
                        ? resolveStoredCardBrand(
                                normalizedPaymentMethod,
                                request.getCard2Amount(),
                                request.getCard2Brand()
                        )
                        : "")
                .card2VoucherAmount(isSplitCardPayment(request.getCard2Amount())
                        ? (request.getCard2VoucherAmount() != null
                                && request.getCard2VoucherAmount().compareTo(BigDecimal.ZERO) > 0
                                ? request.getCard2VoucherAmount().setScale(2, RoundingMode.HALF_UP)
                                : request.getCard2Amount().setScale(2, RoundingMode.HALF_UP))
                        : null)
                .promotionId(exchangeSale
                        ? null
                        : (promotion != null ? promotion.getId() : discountResolution.promotionId()))
                .promotionName(exchangeSale
                        ? buildExchangePromotionName(request.getExchangeSlipNumber())
                        : discountResolution.promotionName())
                .totalItems(totalItems)
                .testSale(isPosTestSale(kiosk))
                .cashSessionId(openSession.getId())
                .createdBy(user.getId())
                .items(new ArrayList<>())
                .build();

        for (PreparedLine line : preparedLines) {
            String displayName = line.product().getName();
            if (line.size() != null && !line.size().isBlank()) {
                displayName = displayName + " T." + line.size();
            }
            KioskSaleItemEntity saleItem = KioskSaleItemEntity.builder()
                    .kioskSale(sale)
                    .productId(line.product().getId())
                    .productCode(line.product().getCode())
                    .productName(displayName)
                    .colorId(line.color() != null ? line.color().getId() : null)
                    .colorName(line.color() != null ? line.color().getName() : "")
                    .quantity(line.quantity())
                    .unitPrice(line.unitPrice())
                    .lineTotal(line.lineTotal())
                    .build();
            sale.getItems().add(saleItem);
        }

        KioskSaleEntity saved = kioskSaleRepository.save(sale);

        for (Map.Entry<String, BigDecimal> entry : aggregatedQty.entrySet()) {
            ParsedInventoryKey parsed = parseInventoryKey(entry.getKey());
            BigDecimal qty = entry.getValue();
            boolean hasLegacyRow = productInventoryLocationRepository
                    .findByProductIdAndLocationIdAndColorId(parsed.productId(), kiosk.getId(), parsed.colorId())
                    .isPresent();
            if (hasLegacyRow) {
                productInventoryService.decrementInventory(
                        parsed.productId(),
                        kiosk.getId(),
                        parsed.colorId(),
                        qty,
                        "KIOSK_SALE",
                        saved.getId(),
                        saved.getSaleNumber(),
                        "Venta POS en kiosko " + kiosk.getName(),
                        parsed.size()
                );
            }
            kioscoInventoryService.registrarVentaDesdeIntegracion(
                    kiosk.getId(),
                    parsed.productId(),
                    parsed.colorId(),
                    qty,
                    saved.getId(),
                    user.getId(),
                    parsed.size(),
                    parsed.hardwareCondition()
            );
        }

        return toSaleResponse(saved, kiosk, user);
    }

    /**
     * Restaura una venta POS borrada por error con el mismo {@code saleNumber}.
     * Solo administradores. No descuenta inventario ni requiere caja abierta.
     */
    public KioskPosSaleResponse restoreSale(KioskPosSaleRestoreRequest request)
            throws BusinessException, ResourceNotFoundException {
        if (request == null || request.getItems() == null || request.getItems().isEmpty()) {
            throw new BusinessException("Debes agregar al menos un producto para restaurar la venta.");
        }

        UserEntity user = getCurrentUserOrThrow();
        boolean admin = KioskAccessHelper.hasAllKiosksAccess(user);
        if (!admin) {
            throw new BusinessException("Solo administradores pueden restaurar ventas POS eliminadas.");
        }

        List<LocationEntity> availableKiosks = resolveAvailableKiosks(user, admin);
        LocationEntity kiosk = resolveTargetKiosk(availableKiosks, request.getKioskLocationId());

        String saleNumber = safeTrim(request.getSaleNumber());
        if (saleNumber.isBlank()) {
            throw new BusinessException("El número de venta es obligatorio.");
        }
        if (kioskSaleRepository.existsByKioskLocationIdAndSaleNumberIgnoreCase(kiosk.getId(), saleNumber)) {
            throw new BusinessException("Ya existe una venta con el número " + saleNumber + " en este kiosko.");
        }

        LocalDate saleDate = resolveRestoreSaleDate(request, saleNumber);
        LocalDateTime soldAt = request.getSoldAt() != null ? request.getSoldAt() : saleDate.atTime(12, 0);

        String normalizedPaymentMethod = normalizePaymentMethod(request.getPaymentMethod());
        validateCardFields(
                normalizedPaymentMethod,
                request.getCardAmount(),
                request.getCardAuthNumber(),
                request.getCardLast4(),
                request.getCardBrand(),
                null,
                false
        );

        String normalizedTaxId = normalizeTaxId(request.getCustomerTaxId());

        List<PreparedLine> preparedLines = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal totalItems = BigDecimal.ZERO;

        for (KioskPosSaleRestoreRequest.RestoreItemRequest itemRequest : request.getItems()) {
            if (itemRequest == null || itemRequest.getProductId() == null) {
                throw new BusinessException("Todos los renglones deben tener producto.");
            }
            BigDecimal quantity = itemRequest.getQuantity();
            if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException("La cantidad debe ser mayor a cero para todos los productos.");
            }

            ProductEntity product = productRepository.findById(itemRequest.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product", itemRequest.getProductId()));

            ColorEntity color = null;
            if (itemRequest.getColorId() != null) {
                color = colorRepository.findById(itemRequest.getColorId())
                        .orElseThrow(() -> new ResourceNotFoundException("Color", itemRequest.getColorId()));
            }

            String sizeLabel = ProductInventorySizesJson.normalizeKey(itemRequest.getSize());
            BigDecimal unitPrice = itemRequest.getUnitPrice() != null
                    ? itemRequest.getUnitPrice().setScale(2, RoundingMode.HALF_UP)
                    : resolvePosUnitPrice(product);
            BigDecimal lineTotal = itemRequest.getLineTotal() != null
                    ? itemRequest.getLineTotal().setScale(2, RoundingMode.HALF_UP)
                    : unitPrice.multiply(quantity).setScale(2, RoundingMode.HALF_UP);

            preparedLines.add(new PreparedLine(
                    product,
                    color,
                    sizeLabel.isEmpty() ? null : sizeLabel,
                    quantity,
                    unitPrice,
                    lineTotal
            ));
            subtotal = subtotal.add(lineTotal);
            totalItems = totalItems.add(quantity);
        }

        BigDecimal discountAmount = request.getDiscountAmount() != null
                ? request.getDiscountAmount().setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal resolvedSubtotal = request.getSubtotal() != null
                ? request.getSubtotal().setScale(2, RoundingMode.HALF_UP)
                : subtotal.setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalAmount = request.getTotalAmount() != null
                ? request.getTotalAmount().setScale(2, RoundingMode.HALF_UP)
                : resolvedSubtotal.subtract(discountAmount).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);

        PaymentSnapshot payment = resolvePaymentSnapshot(
                normalizedPaymentMethod,
                totalAmount,
                request.getAmountReceived(),
                request.getCashAmount(),
                request.getCardAmount()
        );

        KioskSaleEntity sale = KioskSaleEntity.builder()
                .saleNumber(saleNumber)
                .kioskLocationId(kiosk.getId())
                .soldByUserId(user.getId())
                .saleDate(saleDate)
                .soldAt(soldAt)
                .customerTaxId(normalizedTaxId)
                .customerName(safeTrim(request.getCustomerName()))
                .address(safeTrim(request.getAddress()))
                .phone(safeTrim(request.getPhone()))
                .email(normalizeFelReceptorEmail(safeTrim(request.getEmail())))
                .paymentMethod(normalizedPaymentMethod)
                .status("COMPLETED")
                .notes(appendRestoreNote(safeTrim(request.getNotes())))
                .comments(safeTrim(request.getComments()))
                .subtotal(resolvedSubtotal)
                .totalAmount(totalAmount)
                .discountAmount(discountAmount)
                .amountReceived(payment.amountReceived())
                .changeAmount(payment.changeAmount())
                .cashAmount(payment.cashAmount())
                .cardAmount(payment.cardAmount())
                .cardAuthNumber(safeTrim(request.getCardAuthNumber()))
                .cardLast4(safeTrim(request.getCardLast4()))
                .cardBrand(resolveStoredCardBrand(
                        normalizedPaymentMethod,
                        payment.cardAmount(),
                        request.getCardBrand()
                ))
                .totalItems(totalItems)
                .testSale(false)
                .cashSessionId(null)
                .depositSlipNumber(safeTrim(request.getDepositSlipNumber()))
                .felStatus(safeTrim(request.getFelStatus()))
                .felUuid(safeTrim(request.getFelUuid()))
                .felSerie(safeTrim(request.getFelSerie()))
                .felNumero(safeTrim(request.getFelNumero()))
                .felError(safeTrim(request.getFelError()))
                .felCertifiedAt(request.getFelCertifiedAt())
                .createdBy(user.getId())
                .items(new ArrayList<>())
                .build();

        for (PreparedLine line : preparedLines) {
            String displayName = line.product().getName();
            if (line.size() != null && !line.size().isBlank()) {
                displayName = displayName + " T." + line.size();
            }
            KioskSaleItemEntity saleItem = KioskSaleItemEntity.builder()
                    .kioskSale(sale)
                    .productId(line.product().getId())
                    .productCode(line.product().getCode())
                    .productName(displayName)
                    .colorId(line.color() != null ? line.color().getId() : null)
                    .colorName(line.color() != null ? line.color().getName() : "")
                    .quantity(line.quantity())
                    .unitPrice(line.unitPrice())
                    .lineTotal(line.lineTotal())
                    .build();
            sale.getItems().add(saleItem);
        }

        KioskSaleEntity saved = kioskSaleRepository.save(sale);
        syncSaleSequenceFloor(saleNumber, saleDate);

        boolean createDraft = request.getCreateTaxInvoiceDraft() == null || Boolean.TRUE.equals(request.getCreateTaxInvoiceDraft());
        if (createDraft) {
            taxInvoiceService.createDraftFromKioskSaleId(saved.getId());
            saved = kioskSaleRepository.findById(saved.getId()).orElse(saved);
        }

        return toSaleResponse(saved, kiosk, user);
    }

    private LocalDate resolveRestoreSaleDate(KioskPosSaleRestoreRequest request, String saleNumber) {
        if (request.getSaleDate() != null) {
            return request.getSaleDate();
        }
        Matcher matcher = POS_SALE_NUMBER_PATTERN.matcher(saleNumber);
        if (matcher.matches()) {
            return LocalDate.parse(matcher.group(1), SALE_NUMBER_DATE);
        }
        return GuatemalaDateTime.today();
    }

    private void syncSaleSequenceFloor(String saleNumber, LocalDate fallbackDate) {
        Matcher matcher = POS_SALE_NUMBER_PATTERN.matcher(safeTrim(saleNumber));
        LocalDate sequenceDate = fallbackDate;
        Integer sequenceValue = null;
        if (matcher.matches()) {
            sequenceDate = LocalDate.parse(matcher.group(1), SALE_NUMBER_DATE);
            sequenceValue = Integer.parseInt(matcher.group(2));
        }
        if (sequenceValue == null) {
            return;
        }
        final LocalDate lockedSequenceDate = sequenceDate;
        KioskSaleSequenceEntity sequence = kioskSaleSequenceRepository.findWithLockBySaleDate(lockedSequenceDate)
                .orElseGet(() -> KioskSaleSequenceEntity.builder()
                        .saleDate(lockedSequenceDate)
                        .lastNumber(0)
                        .build());
        int current = sequence.getLastNumber() != null ? sequence.getLastNumber() : 0;
        if (sequenceValue > current) {
            sequence.setLastNumber(sequenceValue);
            kioskSaleSequenceRepository.save(sequence);
        }
    }

    private static String appendRestoreNote(String notes) {
        String marker = "[Restaurada manualmente]";
        if (notes == null || notes.isBlank()) {
            return marker;
        }
        if (notes.contains(marker)) {
            return notes;
        }
        return marker + " " + notes.trim();
    }

    public KioskPosSaleResponse updateSaleInvoiceContact(
            Long saleId,
            Long kioskLocationId,
            com.fossiles.fossilescorebackend.application.dto.request.KioskPosSaleInvoiceContactRequest request
    ) throws BusinessException, ResourceNotFoundException {
        UserEntity user = getCurrentUserOrThrow();
        boolean admin = KioskAccessHelper.hasAllKiosksAccess(user);
        List<LocationEntity> availableKiosks = resolveAvailableKiosks(user, admin);
        LocationEntity kiosk = resolveTargetKiosk(availableKiosks, kioskLocationId);

        KioskSaleEntity sale = kioskSaleRepository.findById(saleId)
                .orElseThrow(() -> new ResourceNotFoundException("KioskSale", saleId));
        if (!Objects.equals(sale.getKioskLocationId(), kiosk.getId())) {
            throw new BusinessException("No tienes acceso a esta venta.");
        }
        if (request != null) {
            if (request.getEmail() != null) {
                sale.setEmail(normalizeFelReceptorEmail(safeTrim(request.getEmail())));
            }
            if (request.getPhone() != null) {
                sale.setPhone(safeTrim(request.getPhone()));
            }
        }
        KioskSaleEntity saved = kioskSaleRepository.save(sale);
        return toSaleResponse(saved, kiosk, user);
    }

    public KioskPosSaleResponse createExchangeSale(KioskPosSaleRequest request, String slipNumber)
            throws BusinessException, ResourceNotFoundException {
        if (request == null) {
            throw new BusinessException("Debes indicar los datos de la venta de cambio.");
        }
        if (request.getExchangeCreditAmount() == null
                || request.getExchangeCreditAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("El crédito de la boleta de cambio es obligatorio.");
        }
        request.setPromotionId(null);
        request.setManualDiscountPercent(null);
        request.setExchangeSlipNumber(slipNumber);
        return createSale(request);
    }

    private static String buildExchangePromotionName(String slipNumber) {
        String ref = slipNumber != null ? slipNumber.trim() : "";
        return ref.isBlank() ? "Boleta de cambio" : "Boleta de cambio " + ref;
    }

    private static String resolvePromotionDisplayName(KioskPromotionEntity promotion, BigDecimal manualDiscountPercent) {
        if (promotion != null && promotion.getName() != null && !promotion.getName().isBlank()) {
            return promotion.getName();
        }
        if (manualDiscountPercent != null && manualDiscountPercent.compareTo(BigDecimal.ZERO) > 0) {
            return "Descuento " + manualDiscountPercent.stripTrailingZeros().toPlainString() + "%";
        }
        return null;
    }

    private static String normalizeFelReceptorEmail(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String joined = java.util.Arrays.stream(raw.split("[;,]"))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .collect(java.util.stream.Collectors.joining(";"));
        return joined.isBlank() ? null : joined;
    }

    @Transactional(readOnly = true)
    public KioskPosSaleResponse getSaleById(Long saleId, Long kioskLocationId)
            throws BusinessException, ResourceNotFoundException {
        UserEntity user = getCurrentUserOrThrow();
        boolean admin = KioskAccessHelper.hasAllKiosksAccess(user);
        List<LocationEntity> availableKiosks = resolveAvailableKiosks(user, admin);
        LocationEntity kiosk = resolveTargetKiosk(availableKiosks, kioskLocationId);

        KioskSaleEntity sale = kioskSaleRepository.findById(saleId)
                .orElseThrow(() -> new ResourceNotFoundException("KioskSale", saleId));
        if (!Objects.equals(sale.getKioskLocationId(), kiosk.getId())) {
            throw new BusinessException("No tienes acceso a esta venta.");
        }
        return toSaleResponse(sale, kiosk, user);
    }

    public KioskPosSaleResponse updateSalePayment(
            Long saleId,
            Long kioskLocationId,
            KioskPosSalePaymentUpdateRequest request
    ) throws BusinessException, ResourceNotFoundException {
        if (request == null || request.getPaymentMethod() == null) {
            throw new BusinessException("Debes indicar la forma de pago.");
        }
        UserEntity user = getCurrentUserOrThrow();
        boolean admin = KioskAccessHelper.hasAllKiosksAccess(user);
        List<LocationEntity> availableKiosks = resolveAvailableKiosks(user, admin);
        LocationEntity kiosk = resolveTargetKiosk(availableKiosks, kioskLocationId);

        KioskSaleEntity sale = kioskSaleRepository.findById(saleId)
                .orElseThrow(() -> new ResourceNotFoundException("KioskSale", saleId));
        if (!Objects.equals(sale.getKioskLocationId(), kiosk.getId())) {
            throw new BusinessException("No tienes acceso a esta venta.");
        }
        if (isVoidSale(sale)) {
            throw new BusinessException("No se puede modificar una venta anulada.");
        }
        if (!"COMPLETED".equalsIgnoreCase(safeTrim(sale.getStatus()))) {
            throw new BusinessException("Solo se pueden corregir ventas completadas.");
        }
        if (sale.getCashSessionId() == null) {
            throw new BusinessException("La venta no está ligada a una sesión de caja.");
        }

        KioskCashSessionEntity session = kioskCashSessionRepository.findById(sale.getCashSessionId())
                .orElseThrow(() -> new ResourceNotFoundException("KioskCashSession", sale.getCashSessionId()));
        if (!CASH_SESSION_OPEN.equalsIgnoreCase(safeTrim(session.getStatus()))) {
            throw new BusinessException("Solo puedes corregir pagos mientras la caja esté abierta.");
        }

        String normalizedPaymentMethod = normalizePaymentMethod(request.getPaymentMethod());
        validateCardFields(
                normalizedPaymentMethod,
                request.getCardAmount(),
                request.getCardAuthNumber(),
                request.getCardLast4(),
                request.getCardBrand(),
                request.getCardVoucherAmount(),
                true
        );
        BigDecimal totalAmount = sale.getTotalAmount() != null ? sale.getTotalAmount() : BigDecimal.ZERO;
        validateSplitCardPayment(
                normalizedPaymentMethod,
                totalAmount,
                request.getCardAmount(),
                request.getCard2Amount(),
                request.getCard2AuthNumber(),
                request.getCard2Last4(),
                request.getCard2Brand(),
                request.getCard2VoucherAmount(),
                true
        );
        PaymentSnapshot payment = resolvePaymentSnapshot(
                normalizedPaymentMethod,
                totalAmount,
                request.getAmountReceived(),
                request.getCashAmount(),
                request.getCardAmount()
        );

        sale.setPaymentMethod(normalizedPaymentMethod);
        sale.setAmountReceived(payment.amountReceived());
        sale.setChangeAmount(payment.changeAmount());
        sale.setCashAmount(payment.cashAmount());
        sale.setCardAmount(resolveStoredCardAmount(
                normalizedPaymentMethod,
                payment.cardAmount(),
                request.getCardAmount(),
                request.getCard2Amount()
        ));
        boolean hasCardData = requiresCardData(
                normalizedPaymentMethod,
                resolveStoredCardAmount(
                        normalizedPaymentMethod,
                        payment.cardAmount(),
                        request.getCardAmount(),
                        request.getCard2Amount()
                )
        );
        sale.setCardAuthNumber(hasCardData ? safeTrim(request.getCardAuthNumber()) : "");
        sale.setCardLast4(hasCardData ? safeTrim(request.getCardLast4()) : "");
        sale.setCardBrand(hasCardData
                ? resolveStoredCardBrand(
                        normalizedPaymentMethod,
                        resolveStoredCardAmount(
                                normalizedPaymentMethod,
                                payment.cardAmount(),
                                request.getCardAmount(),
                                request.getCard2Amount()
                        ),
                        request.getCardBrand()
                )
                : "");
        sale.setCardVoucherAmount(hasCardData
                ? resolveStoredCardVoucherAmount(
                        normalizedPaymentMethod,
                        resolveStoredCardAmount(
                                normalizedPaymentMethod,
                                payment.cardAmount(),
                                request.getCardAmount(),
                                request.getCard2Amount()
                        ),
                        request.getCardVoucherAmount()
                )
                : null);
        if (isSplitCardPayment(request.getCard2Amount())) {
            sale.setCard2Amount(request.getCard2Amount().setScale(2, RoundingMode.HALF_UP));
            sale.setCard2AuthNumber(safeTrim(request.getCard2AuthNumber()));
            sale.setCard2Last4(safeTrim(request.getCard2Last4()));
            sale.setCard2Brand(resolveStoredCardBrand(
                    normalizedPaymentMethod,
                    request.getCard2Amount(),
                    request.getCard2Brand()
            ));
            sale.setCard2VoucherAmount(
                    request.getCard2VoucherAmount() != null
                            && request.getCard2VoucherAmount().compareTo(BigDecimal.ZERO) > 0
                            ? request.getCard2VoucherAmount().setScale(2, RoundingMode.HALF_UP)
                            : request.getCard2Amount().setScale(2, RoundingMode.HALF_UP)
            );
        } else {
            sale.setCard2Amount(null);
            sale.setCard2AuthNumber("");
            sale.setCard2Last4("");
            sale.setCard2Brand("");
            sale.setCard2VoucherAmount(null);
        }
        KioskSaleEntity saved = kioskSaleRepository.save(sale);
        return toSaleResponse(saved, kiosk, user);
    }

    public KioskPosSaleResponse voidSale(Long saleId, Long kioskLocationId, KioskSaleVoidRequest request)
            throws BusinessException, ResourceNotFoundException {
        if (request == null || safeTrim(request.getReason()).isBlank()) {
            throw new BusinessException("Debes indicar el motivo de anulación.");
        }
        UserEntity user = getCurrentUserOrThrow();
        boolean admin = KioskAccessHelper.hasAllKiosksAccess(user);
        List<LocationEntity> availableKiosks = resolveAvailableKiosks(user, admin);
        LocationEntity kiosk = resolveTargetKiosk(availableKiosks, kioskLocationId);

        KioskSaleEntity sale = kioskSaleRepository.findById(saleId)
                .orElseThrow(() -> new ResourceNotFoundException("KioskSale", saleId));
        if (!Objects.equals(sale.getKioskLocationId(), kiosk.getId())) {
            throw new BusinessException("No tienes acceso a esta venta.");
        }
        if (isVoidSale(sale)) {
            throw new BusinessException("La venta ya está anulada.");
        }

        if (sale.getCashSessionId() != null) {
            KioskCashSessionEntity openSession = kioskCashSessionRepository
                    .findFirstByKioskLocationIdAndStatusOrderByOpenedAtDesc(kiosk.getId(), CASH_SESSION_OPEN)
                    .orElse(null);
            if (openSession == null) {
                throw new BusinessException("Debes tener caja abierta para anular ventas.");
            }
            if (!Objects.equals(sale.getCashSessionId(), openSession.getId())) {
                throw new BusinessException("Solo puedes anular ventas registradas en la caja abierta actual.");
            }
        }

        if ("CERTIFIED".equalsIgnoreCase(safeTrim(sale.getFelStatus())) && sale.getInvoiceId() != null) {
            try {
                taxInvoiceService.voidInvoice(sale.getInvoiceId(), request.getReason().trim());
            } catch (BusinessException ex) {
                throw ex;
            } catch (ResourceNotFoundException ex) {
                throw new BusinessException("No se encontró la factura asociada a la venta.");
            }
        }

        List<KioskSaleItemEntity> saleItems = sale.getItems() != null ? sale.getItems() : List.of();
        for (KioskSaleItemEntity item : saleItems) {
                if (item.getProductId() == null || item.getQuantity() == null) {
                    continue;
                }
                String sizeKey = extractSizeFromSaleItemName(item.getProductName());
                boolean hasLegacyRow = productInventoryLocationRepository
                        .findByProductIdAndLocationIdAndColorId(item.getProductId(), kiosk.getId(), item.getColorId())
                        .isPresent();
                if (hasLegacyRow) {
                    productInventoryService.incrementInventory(
                            item.getProductId(),
                            kiosk.getId(),
                            item.getColorId(),
                            item.getQuantity(),
                            null,
                            "KIOSK_SALE_VOID",
                            sale.getId(),
                            sale.getSaleNumber(),
                            "Anulacion venta POS",
                            sizeKey
                    );
                }
                kioscoInventoryService.anularFacturaDesdeIntegracion(
                        sale.getId(),
                        kiosk.getId(),
                        item.getProductId(),
                        item.getColorId(),
                        item.getQuantity(),
                        request.getReason(),
                        user.getId(),
                        sizeKey
                );
        }

        sale.setStatus(SALE_STATUS_VOID);
        sale.setFelStatus(sale.getInvoiceId() != null ? "VOID" : sale.getFelStatus());
        KioskSaleEntity saved = kioskSaleRepository.save(sale);
        return toSaleResponse(saved, kiosk, user);
    }

    public KioskPosSaleResponse registerDepositSlip(
            Long saleId,
            Long kioskLocationId,
            KioskPosDepositSlipUpdateRequest request
    ) throws BusinessException, ResourceNotFoundException {
        if (request == null || safeTrim(request.getDepositSlipNumber()).isBlank()) {
            throw new BusinessException("Debes indicar el número de boleta de depósito.");
        }
        UserEntity user = getCurrentUserOrThrow();
        boolean admin = KioskAccessHelper.hasAllKiosksAccess(user);
        List<LocationEntity> availableKiosks = resolveAvailableKiosks(user, admin);
        LocationEntity kiosk = resolveTargetKiosk(availableKiosks, kioskLocationId);

        KioskSaleEntity sale = kioskSaleRepository.findById(saleId)
                .orElseThrow(() -> new ResourceNotFoundException("KioskSale", saleId));
        if (!Objects.equals(sale.getKioskLocationId(), kiosk.getId())) {
            throw new BusinessException("No tienes acceso a esta venta.");
        }
        BigDecimal linkedDisbursements = safeAmount(kioskCashExpenseRepository.sumAmountByKioskSaleId(sale.getId()));
        if (!isPendingDeposit(sale, linkedDisbursements)) {
            throw new BusinessException("Esta venta no requiere boleta de depósito o ya fue registrada.");
        }
        BigDecimal netDeposit = resolveNetDepositAmount(sale, linkedDisbursements);
        if (netDeposit.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("Esta venta no requiere boleta de depósito (efectivo totalmente desembolsado).");
        }

        sale.setDepositSlipNumber(safeTrim(request.getDepositSlipNumber()));
        sale.setDepositRecordedAt(GuatemalaDateTime.now());
        sale.setDepositRecordedBy(user.getId());
        KioskSaleEntity saved = kioskSaleRepository.save(sale);
        return toSaleResponse(saved, kiosk, user);
    }

    @Transactional(readOnly = true)
    public KioskPendingDepositSummaryResponse getPendingDepositSummary(Long kioskLocationId) throws BusinessException {
        UserEntity user = getCurrentUserOrThrow();
        boolean admin = KioskAccessHelper.hasAllKiosksAccess(user);
        List<LocationEntity> availableKiosks = resolveAvailableKiosks(user, admin);
        LocationEntity kiosk = resolveTargetKiosk(availableKiosks, kioskLocationId);

        List<KioskSaleEntity> pendingSales = kioskSaleRepository.findPendingDepositsByKioskLocationId(kiosk.getId());
        Map<Long, BigDecimal> disbursementTotals = loadDisbursementTotalsBySaleIds(
                pendingSales.stream().map(KioskSaleEntity::getId).filter(Objects::nonNull).collect(Collectors.toSet()));
        List<KioskSaleEntity> netPendingSales = pendingSales.stream()
                .filter(sale -> isPendingDeposit(sale, disbursementTotals.getOrDefault(sale.getId(), BigDecimal.ZERO)))
                .toList();
        BigDecimal pendingAmount = netPendingSales.stream()
                .map(sale -> pendingDepositCashAmount(sale, disbursementTotals.getOrDefault(sale.getId(), BigDecimal.ZERO)))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        return KioskPendingDepositSummaryResponse.builder()
                .kioskLocationId(kiosk.getId())
                .pendingCount(netPendingSales.size())
                .pendingAmount(pendingAmount)
                .build();
    }

    @Transactional(readOnly = true)
    public List<KioskPosSaleResponse> getCurrentKioskSales(
            LocalDate startDate,
            LocalDate endDate,
            Long kioskLocationId
    ) throws BusinessException {
        UserEntity user = getCurrentUserOrThrow();
        boolean admin = KioskAccessHelper.hasAllKiosksAccess(user);
        List<LocationEntity> availableKiosks = resolveAvailableKiosks(user, admin);
        LocationEntity kiosk = resolveTargetKiosk(availableKiosks, kioskLocationId);
        List<KioskSaleEntity> sales = findSalesByDateRangeForKiosk(kiosk.getId(), startDate, endDate);
        return sales.stream().map(sale -> toSaleResponse(sale, kiosk, user)).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public KioskPosManagerDashboardResponse getManagerDashboard(Long kioskLocationId) throws BusinessException {
        UserEntity user = getCurrentUserOrThrow();
        boolean admin = KioskAccessHelper.hasAllKiosksAccess(user);
        List<LocationEntity> availableKiosks = resolveAvailableKiosks(user, admin);
        LocationEntity kiosk = resolveTargetKiosk(availableKiosks, kioskLocationId);

        LocalDate today = GuatemalaDateTime.today();
        LocalDate todayLastYear = today.minusYears(1);
        LocalDate lastMonthStart = today.minusMonths(1).withDayOfMonth(1);
        LocalDate lastMonthEnd = today.withDayOfMonth(1).minusDays(1);
        LocalDate monthToDateStart = today.withDayOfMonth(1);
        LocalDate rangeStart = todayLastYear.isBefore(lastMonthStart)
                ? todayLastYear
                : lastMonthStart;

        List<KioskSaleEntity> sales = findSalesByDateRangeForKiosk(kiosk.getId(), rangeStart, today).stream()
                .filter(KioskPosService::countsForManagerDashboard)
                .toList();

        KioskPosManagerDashboardResponse.Metric todayMetric = buildDashboardMetric(sales, today, today);
        KioskPosManagerDashboardResponse.Metric todayLastYearMetric = buildDashboardMetric(
                sales,
                todayLastYear,
                todayLastYear
        );
        KioskPosManagerDashboardResponse.Metric lastMonthMetric = buildDashboardMetric(
                sales,
                lastMonthStart,
                lastMonthEnd
        );
        KioskPosManagerDashboardResponse.Metric monthToDateMetric = buildDashboardMetric(
                sales,
                monthToDateStart,
                today
        );

        return KioskPosManagerDashboardResponse.builder()
                .today(todayMetric)
                .todayLastYear(todayLastYearMetric)
                .lastMonth(lastMonthMetric)
                .monthToDate(monthToDateMetric)
                .growthVsLastYearPercent(growthPercent(todayMetric.getAmount(), todayLastYearMetric.getAmount()))
                .build();
    }

    @Transactional(readOnly = true)
    public KioskPosReportsResponse getCurrentKioskReport(
            LocalDate startDate,
            LocalDate endDate,
            Long kioskLocationId
    ) throws BusinessException {
        UserEntity user = getCurrentUserOrThrow();
        boolean admin = KioskAccessHelper.hasAllKiosksAccess(user);
        List<LocationEntity> availableKiosks = resolveAvailableKiosks(user, admin);
        LocationEntity kiosk = resolveTargetKiosk(availableKiosks, kioskLocationId);
        List<KioskSaleEntity> sales = findSalesByDateRangeForKiosk(kiosk.getId(), startDate, endDate);
        return buildReportResponse(sales, startDate, endDate, null);
    }

    @Transactional(readOnly = true)
    public KioskPosReportsResponse getGeneralReport(LocalDate startDate, LocalDate endDate, String paymentKind)
            throws BusinessException {
        UserEntity user = getCurrentUserOrThrow();
        if (!KioskAccessHelper.hasKioskReportsAccess(user)) {
            throw new BusinessException(
                    "Solo administradores, logística o contabilidad pueden ver el reporte general de kioskos.");
        }
        String normalizedKind = normalizeReportPaymentKind(paymentKind);
        List<KioskSaleEntity> sales = findSalesByDateRange(startDate, endDate).stream()
                .filter(KioskPosService::countsForProductionMetrics)
                .filter(sale -> matchesReportPaymentKind(sale, normalizedKind))
                .collect(Collectors.toList());
        return buildReportResponse(sales, startDate, endDate, normalizedKind);
    }

    /** Ventas detalladas (con ítems) para exportar REPORTE DE VENTAS desde admin. */
    @Transactional(readOnly = true)
    public List<KioskPosSaleResponse> getGeneralSalesDetail(
            LocalDate startDate,
            LocalDate endDate,
            Long kioskLocationId,
            String paymentKind
    ) throws BusinessException {
        UserEntity user = getCurrentUserOrThrow();
        if (!KioskAccessHelper.hasKioskReportsAccess(user)) {
            throw new BusinessException(
                    "Solo administradores, logística o contabilidad pueden exportar el reporte general de ventas.");
        }
        String normalizedKind = normalizeReportPaymentKind(paymentKind);
        Map<Long, LocationEntity> kioskById = locationRepository.findAll().stream()
                .filter(this::isKioskLocation)
                .collect(Collectors.toMap(LocationEntity::getId, item -> item, (a, b) -> a));

        List<KioskSaleEntity> sales = findSalesByDateRange(startDate, endDate).stream()
                .filter(KioskPosService::countsForProductionMetrics)
                .filter(sale -> kioskLocationId == null || Objects.equals(sale.getKioskLocationId(), kioskLocationId))
                .filter(sale -> matchesReportPaymentKind(sale, normalizedKind))
                .sorted(Comparator
                        .comparing(KioskSaleEntity::getKioskLocationId, Comparator.nullsLast(Long::compareTo))
                        .thenComparing(KioskSaleEntity::getSoldAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        List<KioskPosSaleResponse> result = new ArrayList<>();
        for (KioskSaleEntity sale : sales) {
            LocationEntity kiosk = kioskById.get(sale.getKioskLocationId());
            if (kiosk == null) {
                kiosk = locationRepository.findById(sale.getKioskLocationId()).orElse(null);
            }
            if (kiosk == null) {
                continue;
            }
            result.add(toSaleResponse(sale, kiosk, user));
        }
        return result;
    }

    /**
     * Reporte de ventas consolidadas (contabilidad): incluye anuladas, acepta varios
     * kioskos y siempre excluye kioskos/ventas piloto. Orden cronológico ascendente.
     */
    @Transactional(readOnly = true)
    public List<KioskPosSaleResponse> getConsolidatedSalesReport(
            LocalDate startDate,
            LocalDate endDate,
            List<Long> kioskLocationIds
    ) throws BusinessException {
        UserEntity user = getCurrentUserOrThrow();
        if (!KioskAccessHelper.hasKioskReportsAccess(user)) {
            throw new BusinessException(
                    "Solo administradores, logística o contabilidad pueden ver el reporte de ventas consolidadas.");
        }
        Map<Long, LocationEntity> nonPilotKioskById = locationRepository.findAll().stream()
                .filter(this::isKioskLocation)
                .filter(location -> !isPosTestSale(location))
                .collect(Collectors.toMap(LocationEntity::getId, item -> item, (a, b) -> a));

        Set<Long> targetKioskIds = (kioskLocationIds == null || kioskLocationIds.isEmpty())
                ? nonPilotKioskById.keySet()
                : kioskLocationIds.stream().filter(nonPilotKioskById::containsKey).collect(Collectors.toSet());

        List<KioskSaleEntity> sales = findSalesByDateRange(startDate, endDate).stream()
                .filter(sale -> !Boolean.TRUE.equals(sale.getTestSale()))
                .filter(sale -> targetKioskIds.contains(sale.getKioskLocationId()))
                .sorted(Comparator.comparing(KioskSaleEntity::getSoldAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        List<KioskPosSaleResponse> result = new ArrayList<>();
        for (KioskSaleEntity sale : sales) {
            LocationEntity kiosk = nonPilotKioskById.get(sale.getKioskLocationId());
            if (kiosk == null) {
                continue;
            }
            result.add(toSaleResponse(sale, kiosk, user));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<KioskDisbursementReportRowResponse> getGeneralDisbursements(
            LocalDate startDate,
            LocalDate endDate,
            Long kioskLocationId
    ) throws BusinessException {
        UserEntity user = getCurrentUserOrThrow();
        boolean globalReports = KioskAccessHelper.hasKioskReportsAccess(user);
        List<LocationEntity> availableKiosks = resolveAvailableKiosks(user, globalReports);

        Long effectiveKioskId;
        if (globalReports) {
            effectiveKioskId = kioskLocationId;
        } else {
            LocationEntity kiosk = resolveTargetKiosk(availableKiosks, kioskLocationId);
            effectiveKioskId = kiosk.getId();
        }

        LocalDate[] range = normalizeSaleDateRange(startDate, endDate);
        LocalDate from = range[0] != null ? range[0] : GuatemalaDateTime.today();
        LocalDate to = range[1] != null ? range[1] : from;
        LocalDateTime startAt = from.atStartOfDay();
        LocalDateTime endAt = to.plusDays(1).atStartOfDay();

        List<KioskCashExpenseEntity> expenses = kioskCashExpenseRepository.findForReport(
                startAt, endAt, effectiveKioskId);

        Set<Long> sessionIds = expenses.stream()
                .map(KioskCashExpenseEntity::getCashSessionId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, KioskCashSessionEntity> sessionsById = kioskCashSessionRepository.findAllById(sessionIds).stream()
                .collect(Collectors.toMap(KioskCashSessionEntity::getId, item -> item, (a, b) -> a));

        Set<Long> kioskIds = sessionsById.values().stream()
                .map(KioskCashSessionEntity::getKioskLocationId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, LocationEntity> kiosksById = locationRepository.findAllById(kioskIds).stream()
                .collect(Collectors.toMap(LocationEntity::getId, item -> item, (a, b) -> a));

        List<KioskDisbursementReportRowResponse> rows = new ArrayList<>();
        Set<Long> linkedSaleIds = expenses.stream()
                .map(KioskCashExpenseEntity::getKioskSaleId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, KioskSaleEntity> salesById = linkedSaleIds.isEmpty()
                ? Map.of()
                : kioskSaleRepository.findAllById(linkedSaleIds).stream()
                        .collect(Collectors.toMap(KioskSaleEntity::getId, item -> item, (a, b) -> a));
        Set<Long> invoiceIds = salesById.values().stream()
                .map(KioskSaleEntity::getInvoiceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, TaxInvoiceEntity> invoicesById = invoiceIds.isEmpty()
                ? Map.of()
                : taxInvoiceRepository.findAllById(invoiceIds).stream()
                        .collect(Collectors.toMap(TaxInvoiceEntity::getId, item -> item, (a, b) -> a));

        for (KioskCashExpenseEntity expense : expenses) {
            KioskCashSessionEntity session = sessionsById.get(expense.getCashSessionId());
            LocationEntity kiosk = session != null ? kiosksById.get(session.getKioskLocationId()) : null;
            UserEntity createdBy = expense.getCreatedByUserId() != null
                    ? userRepository.findById(expense.getCreatedByUserId()).orElse(null)
                    : null;
            KioskSaleEntity linkedSale = expense.getKioskSaleId() != null
                    ? salesById.get(expense.getKioskSaleId())
                    : null;
            rows.add(KioskDisbursementReportRowResponse.builder()
                    .id(expense.getId())
                    .cashSessionId(expense.getCashSessionId())
                    .kioskSaleId(expense.getKioskSaleId())
                    .saleNumber(linkedSale != null ? linkedSale.getSaleNumber() : null)
                    .internalNumber(linkedSale != null
                            ? resolveSaleInvoiceLabel(linkedSale, invoicesById)
                            : null)
                    .kioskLocationId(session != null ? session.getKioskLocationId() : null)
                    .kioskCode(kiosk != null ? kiosk.getCode() : "")
                    .kioskName(kiosk != null ? kiosk.getName() : "Kiosko")
                    .amount(safeAmount(expense.getAmount()).setScale(2, RoundingMode.HALF_UP))
                    .description(expense.getDescription())
                    .createdAt(expense.getCreatedAt())
                    .createdByUserId(expense.getCreatedByUserId())
                    .createdByName(buildUserFullName(createdBy))
                    .build());
        }
        return rows;
    }

    @Transactional(readOnly = true)
    public KioskBankDepositReportResponse getBankDeposits(
            LocalDate startDate,
            LocalDate endDate,
            Long kioskLocationId
    ) throws BusinessException {
        UserEntity user = getCurrentUserOrThrow();
        boolean globalReports = KioskAccessHelper.hasKioskReportsAccess(user);
        List<LocationEntity> availableKiosks = resolveAvailableKiosks(user, globalReports);

        Long effectiveKioskId;
        if (globalReports) {
            effectiveKioskId = kioskLocationId;
        } else {
            LocationEntity kiosk = resolveTargetKiosk(availableKiosks, kioskLocationId);
            effectiveKioskId = kiosk.getId();
        }

        LocalDate[] range = normalizeSaleDateRange(startDate, endDate);
        LocalDate from = range[0] != null ? range[0] : GuatemalaDateTime.today();
        LocalDate to = range[1] != null ? range[1] : from;
        LocalDateTime startAt = from.atStartOfDay();
        LocalDateTime endAt = to.plusDays(1).atStartOfDay();

        List<KioskSaleEntity> sales = kioskSaleRepository.findForBankDepositReport(
                startAt, endAt, effectiveKioskId).stream()
                .filter(KioskPosService::countsForProductionMetrics)
                .filter(KioskPosService::qualifiesForBankDepositReport)
                .toList();

        Set<Long> kioskIds = sales.stream()
                .map(KioskSaleEntity::getKioskLocationId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, LocationEntity> kiosksById = locationRepository.findAllById(kioskIds).stream()
                .collect(Collectors.toMap(LocationEntity::getId, item -> item, (a, b) -> a));

        Set<Long> userIds = new HashSet<>();
        for (KioskSaleEntity sale : sales) {
            if (sale.getDepositRecordedBy() != null) {
                userIds.add(sale.getDepositRecordedBy());
            }
            if (sale.getSoldByUserId() != null) {
                userIds.add(sale.getSoldByUserId());
            }
        }
        Map<Long, UserEntity> usersById = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(UserEntity::getId, item -> item, (a, b) -> a));

        Set<Long> saleIds = sales.stream().map(KioskSaleEntity::getId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, BigDecimal> disbursementTotals = loadDisbursementTotalsBySaleIds(saleIds);

        String accountNumber = safeTrim(depositReportProperties.getBankAccount());
        String bankName = safeTrim(depositReportProperties.getBankName());
        String accountName = safeTrim(depositReportProperties.getAccountName());

        List<KioskBankDepositReportRowResponse> rows = new ArrayList<>();
        for (KioskSaleEntity sale : sales) {
            LocationEntity kiosk = kiosksById.get(sale.getKioskLocationId());
            UserEntity recordedBy = sale.getDepositRecordedBy() != null
                    ? usersById.get(sale.getDepositRecordedBy())
                    : null;
            UserEntity soldBy = sale.getSoldByUserId() != null
                    ? usersById.get(sale.getSoldByUserId())
                    : null;
            String userName = buildUserFullName(recordedBy);
            if (userName == null || userName.isBlank()) {
                userName = buildUserFullName(soldBy);
            }
            LocalDateTime recordedAt = sale.getDepositRecordedAt() != null
                    ? sale.getDepositRecordedAt()
                    : sale.getSoldAt();
            BigDecimal grossCash = resolveCashAmountForDeposit(sale).setScale(2, RoundingMode.HALF_UP);
            BigDecimal linkedDisbursements = disbursementTotals.getOrDefault(sale.getId(), BigDecimal.ZERO)
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal netAmount = resolveNetDepositAmount(sale, linkedDisbursements);
            rows.add(KioskBankDepositReportRowResponse.builder()
                    .id(sale.getId())
                    .saleId(sale.getId())
                    .accountNumber(accountNumber)
                    .bankName(bankName)
                    .documentNumber(safeTrim(sale.getDepositSlipNumber()))
                    .grossCashAmount(grossCash)
                    .disbursementsTotal(linkedDisbursements)
                    .amount(netAmount)
                    .userName(userName)
                    .description(buildBankDepositDescription(sale, kiosk))
                    .recordedAt(recordedAt)
                    .kioskLocationId(sale.getKioskLocationId())
                    .kioskCode(kiosk != null ? kiosk.getCode() : "")
                    .kioskName(kiosk != null ? kiosk.getName() : "Kiosko")
                    .build());
        }

        return KioskBankDepositReportResponse.builder()
                .accountNumber(accountNumber)
                .accountName(accountName)
                .bankName(bankName)
                .rows(rows)
                .build();
    }

    @Transactional(readOnly = true)
    public KioskVoucherReportResponse getVoucherReport(
            LocalDate startDate,
            LocalDate endDate,
            Long kioskLocationId
    ) throws BusinessException {
        UserEntity user = getCurrentUserOrThrow();
        boolean globalReports = KioskAccessHelper.hasKioskReportsAccess(user);
        List<LocationEntity> availableKiosks = resolveAvailableKiosks(user, globalReports);

        Long effectiveKioskId;
        LocationEntity headerKiosk;
        if (globalReports) {
            effectiveKioskId = kioskLocationId;
            headerKiosk = kioskLocationId != null
                    ? locationRepository.findById(kioskLocationId).orElse(null)
                    : null;
        } else {
            headerKiosk = resolveTargetKiosk(availableKiosks, kioskLocationId);
            effectiveKioskId = headerKiosk.getId();
        }

        LocalDate[] range = normalizeSaleDateRange(startDate, endDate);
        LocalDate from = range[0] != null ? range[0] : GuatemalaDateTime.today();
        LocalDate to = range[1] != null ? range[1] : from;

        List<KioskSaleEntity> sales = findSalesForReportBySaleDate(from, to, effectiveKioskId).stream()
                .filter(KioskPosService::countsForProductionMetrics)
                .filter(KioskPosService::qualifiesForVoucherReport)
                .toList();

        Set<Long> kioskIds = sales.stream()
                .map(KioskSaleEntity::getKioskLocationId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, LocationEntity> kiosksById = locationRepository.findAllById(kioskIds).stream()
                .collect(Collectors.toMap(LocationEntity::getId, item -> item, (a, b) -> a));

        Set<Long> invoiceIds = sales.stream()
                .map(KioskSaleEntity::getInvoiceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, TaxInvoiceEntity> invoicesById = taxInvoiceRepository.findAllById(invoiceIds).stream()
                .collect(Collectors.toMap(TaxInvoiceEntity::getId, item -> item, (a, b) -> a));

        String defaultCardBrand = safeTrim(voucherReportProperties.getDefaultCardBrand());
        if (defaultCardBrand.isBlank()) {
            defaultCardBrand = "VISA";
        }

        List<KioskVoucherReportRowResponse> rows = new ArrayList<>();
        for (KioskSaleEntity sale : sales) {
            LocationEntity kiosk = kiosksById.get(sale.getKioskLocationId());
            if (hasSplitCardPayment(sale)) {
                rows.add(buildVoucherReportRow(sale, kiosk, invoicesById, defaultCardBrand, 1));
                rows.add(buildVoucherReportRow(sale, kiosk, invoicesById, defaultCardBrand, 2));
            } else {
                rows.add(buildVoucherReportRow(sale, kiosk, invoicesById, defaultCardBrand, 0));
            }
        }

        String headerKioskName;
        String headerKioskCode = "";
        if (headerKiosk != null) {
            headerKioskName = headerKiosk.getName();
            headerKioskCode = safeTrim(headerKiosk.getCode());
        } else if (kioskIds.size() == 1) {
            LocationEntity only = kiosksById.values().iterator().next();
            headerKioskName = only.getName();
            headerKioskCode = safeTrim(only.getCode());
        } else {
            headerKioskName = "TODOS LOS KIOSKOS";
        }

        return KioskVoucherReportResponse.builder()
                .kioskName(headerKioskName)
                .kioskCode(headerKioskCode)
                .rows(rows)
                .build();
    }

    @Transactional(readOnly = true)
    public KioskMainSheetReportResponse getMainSheetReport(Long physicalCountId)
            throws BusinessException, ResourceNotFoundException {
        if (physicalCountId == null) {
            throw new BusinessException("Debes indicar el corte de conteo físico.");
        }
        UserEntity user = getCurrentUserOrThrow();
        boolean globalReports = KioskAccessHelper.hasKioskReportsAccess(user);
        List<LocationEntity> availableKiosks = resolveAvailableKiosks(user, globalReports);

        KioscoPhysicalCountEntity count = kioscoPhysicalCountRepository.findById(physicalCountId)
                .orElseThrow(() -> new ResourceNotFoundException("KioscoPhysicalCount", physicalCountId));

        LocationEntity kiosk = locationRepository.findById(count.getLocationId())
                .orElseThrow(() -> new ResourceNotFoundException("Location", count.getLocationId()));
        if (!globalReports) {
            boolean allowed = availableKiosks.stream()
                    .anyMatch(item -> Objects.equals(item.getId(), kiosk.getId()));
            if (!allowed) {
                throw new BusinessException("No tienes acceso a este kiosko.");
            }
        }

        LocalDate from = count.getPeriodFrom();
        LocalDate to = count.getPeriodTo();
        if (from == null || to == null) {
            throw new BusinessException("El corte de conteo no tiene un rango de fechas válido.");
        }
        LocalDateTime startAt = from.atStartOfDay();
        LocalDateTime endAt = to.plusDays(1).atStartOfDay();

        List<KioskSaleEntity> sales = kioskSaleRepository
                .findByKioskLocationIdAndSaleDateBetweenOrderBySoldAtDesc(kiosk.getId(), from, to)
                .stream()
                .filter(KioskPosService::countsForProductionMetrics)
                .toList();

        Set<Long> invoiceIds = sales.stream()
                .map(KioskSaleEntity::getInvoiceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, TaxInvoiceEntity> invoicesById = taxInvoiceRepository.findAllById(invoiceIds).stream()
                .collect(Collectors.toMap(TaxInvoiceEntity::getId, item -> item, (a, b) -> a));

        Set<Long> saleIdsForDeposits = sales.stream()
                .filter(KioskPosService::qualifiesForBankDepositReport)
                .map(KioskSaleEntity::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, BigDecimal> disbursementTotals = loadDisbursementTotalsBySaleIds(saleIdsForDeposits);

        Map<LocalDate, BigDecimal> dailyTotals = new TreeMap<>();
        BigDecimal totalSold = BigDecimal.ZERO;
        BigDecimal cardsTotal = BigDecimal.ZERO;
        BigDecimal depositsTotal = BigDecimal.ZERO;
        List<String> invoiceLabels = new ArrayList<>();

        for (KioskSaleEntity sale : sales) {
            LocalDate day = sale.getSaleDate() != null
                    ? sale.getSaleDate()
                    : (sale.getSoldAt() != null ? sale.getSoldAt().toLocalDate() : from);
            BigDecimal amount = safeAmount(sale.getTotalAmount());
            totalSold = totalSold.add(amount);
            dailyTotals.merge(day, amount, BigDecimal::add);
            cardsTotal = cardsTotal.add(resolveCardAmountForReport(sale));
            if (qualifiesForBankDepositReport(sale)) {
                BigDecimal linkedDisbursements = disbursementTotals.getOrDefault(sale.getId(), BigDecimal.ZERO);
                depositsTotal = depositsTotal.add(resolveNetDepositAmount(sale, linkedDisbursements));
            }
            String invoiceLabel = resolveSaleInvoiceLabel(sale, invoicesById);
            if (!safeTrim(invoiceLabel).isBlank()) {
                invoiceLabels.add(invoiceLabel);
            }
        }

        List<KioskCashExpenseEntity> expenses = kioskCashExpenseRepository.findForReport(
                startAt, endAt, kiosk.getId());
        BigDecimal expensesTotal = expenses.stream()
                .map(expense -> safeAmount(expense.getAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        totalSold = totalSold.setScale(2, RoundingMode.HALF_UP);
        cardsTotal = cardsTotal.setScale(2, RoundingMode.HALF_UP);
        depositsTotal = depositsTotal.setScale(2, RoundingMode.HALF_UP);
        expensesTotal = expensesTotal.setScale(2, RoundingMode.HALF_UP);
        BigDecimal reconciledTotal = cardsTotal.add(depositsTotal).add(expensesTotal)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal difference = reconciledTotal.subtract(totalSold).setScale(2, RoundingMode.HALF_UP);

        List<KioskMainSheetReportResponse.DailySaleRow> dailySales = dailyTotals.entrySet().stream()
                .map(entry -> KioskMainSheetReportResponse.DailySaleRow.builder()
                        .saleDate(entry.getKey())
                        .amount(entry.getValue().setScale(2, RoundingMode.HALF_UP))
                        .build())
                .toList();

        InternalInvoiceRange invoiceRange = resolveInternalInvoiceRange(invoiceLabels);

        return KioskMainSheetReportResponse.builder()
                .physicalCountId(count.getId())
                .periodFrom(from)
                .periodTo(to)
                .physicalCountStatus(count.getStatus() != null ? count.getStatus().name() : "")
                .kioskLocationId(kiosk.getId())
                .kioskCode(safeTrim(kiosk.getCode()))
                .kioskName(safeTrim(kiosk.getName()))
                .encargadaName(resolveKioskEncargadaName(kiosk))
                .invoiceFrom(invoiceRange.from())
                .invoiceTo(invoiceRange.to())
                .totalSold(totalSold)
                .cardsTotal(cardsTotal)
                .depositsTotal(depositsTotal)
                .expensesTotal(expensesTotal)
                .reconciledTotal(reconciledTotal)
                .difference(difference)
                .mainSheetCertifiedBy(safeTrim(count.getMainSheetCertifiedBy()))
                .mainSheetReviewedBy(safeTrim(count.getMainSheetReviewedBy()))
                .mainSheetCertifiedAt(count.getMainSheetCertifiedAt())
                .mainSheetInventoryFrom(count.getMainSheetInventoryFrom())
                .mainSheetInventoryTo(count.getMainSheetInventoryTo())
                .mainSheetSalesFrom(count.getMainSheetSalesFrom())
                .mainSheetSalesTo(count.getMainSheetSalesTo())
                .dailySales(dailySales)
                .build();
    }

    @Transactional
    public KioskMainSheetReportResponse certifyMainSheetReport(
            Long physicalCountId,
            KioskMainSheetCertificationRequest request
    ) throws BusinessException, ResourceNotFoundException {
        if (physicalCountId == null) {
            throw new BusinessException("Debes indicar el corte de conteo físico.");
        }
        if (request == null) {
            throw new BusinessException("Debes indicar los datos de certificación.");
        }
        UserEntity user = getCurrentUserOrThrow();
        if (!KioskAccessHelper.hasKioskReportsAccess(user)) {
            throw new BusinessException("No tienes permiso para certificar la hoja principal.");
        }

        KioscoPhysicalCountEntity count = kioscoPhysicalCountRepository.findById(physicalCountId)
                .orElseThrow(() -> new ResourceNotFoundException("KioscoPhysicalCount", physicalCountId));
        assertMainSheetKioskAccess(user, count.getLocationId());

        String certifiedBy = resolveMainSheetReviewerName(request.getCertifiedBy());
        String reviewedBy = resolveMainSheetReviewerName(request.getReviewedBy());
        validateMainSheetDateRange("inventario digital", request.getInventoryFrom(), request.getInventoryTo());
        validateMainSheetDateRange("ventas", request.getSalesFrom(), request.getSalesTo());

        count.setMainSheetCertifiedBy(certifiedBy);
        count.setMainSheetReviewedBy(reviewedBy);
        count.setMainSheetCertifiedAt(GuatemalaDateTime.now());
        count.setMainSheetInventoryFrom(request.getInventoryFrom());
        count.setMainSheetInventoryTo(request.getInventoryTo());
        count.setMainSheetSalesFrom(request.getSalesFrom());
        count.setMainSheetSalesTo(request.getSalesTo());
        kioscoPhysicalCountRepository.save(count);
        return getMainSheetReport(physicalCountId);
    }

    private void assertMainSheetKioskAccess(UserEntity user, Long kioskLocationId) throws BusinessException {
        boolean globalReports = KioskAccessHelper.hasKioskReportsAccess(user);
        if (globalReports) {
            return;
        }
        List<LocationEntity> availableKiosks = resolveAvailableKiosks(user, false);
        boolean allowed = availableKiosks.stream()
                .anyMatch(item -> Objects.equals(item.getId(), kioskLocationId));
        if (!allowed) {
            throw new BusinessException("No tienes acceso a este kiosko.");
        }
    }

    private static String resolveMainSheetReviewerName(String rawName) throws BusinessException {
        String normalized = normalizeMainSheetReviewerName(rawName);
        if (normalized == null) {
            throw new BusinessException("Debes seleccionar un revisor válido de la lista.");
        }
        return normalized;
    }

    private static void validateMainSheetDateRange(String label, LocalDate from, LocalDate to)
            throws BusinessException {
        if (from == null || to == null) {
            throw new BusinessException("Debes indicar el rango de fechas de " + label + ".");
        }
        if (from.isAfter(to)) {
            throw new BusinessException("La fecha inicial no puede ser posterior a la final en " + label + ".");
        }
    }

    static String normalizeMainSheetReviewerName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return null;
        }
        String candidate = rawName.trim().toUpperCase(Locale.ROOT)
                .replace("Á", "A").replace("É", "E").replace("Í", "I")
                .replace("Ó", "O").replace("Ú", "U");
        for (String allowed : MAIN_SHEET_REVIEWERS) {
            if (allowed.equals(candidate)) {
                return allowed;
            }
        }
        return null;
    }

    @Transactional(readOnly = true)
    public TaxpayerLookupResponse lookupTaxpayer(String taxId) throws BusinessException {
        String normalizedTaxId = normalizeTaxId(taxId);
        if (normalizedTaxId == null || "CF".equals(normalizedTaxId)) {
            return TaxpayerLookupResponse.builder()
                    .taxId("CF")
                    .customerName("CONSUMIDOR FINAL")
                    .build();
        }
        if (!isValidGuatemalaNit(normalizedTaxId)) {
            throw new BusinessException("El NIT ingresado no es válido para Guatemala.");
        }
        return felReceptorLookupService.lookup(normalizedTaxId);
    }

    @Transactional(readOnly = true)
    public KioskCustomerProfileResponse getCustomerByTaxId(String taxId) throws BusinessException {
        TaxpayerLookupResponse lookup = lookupTaxpayer(taxId);
        return KioskCustomerProfileResponse.builder()
                .taxId(lookup.getTaxId())
                .customerName(lookup.getCustomerName())
                .build();
    }

    @Transactional(readOnly = true)
    public List<KioskPromotionResponse> getPromotions(Boolean activeOnly, Long kioskLocationId) throws BusinessException {
        UserEntity user = getCurrentUserOrThrow();
        boolean admin = KioskAccessHelper.hasAllKiosksAccess(user);
        Set<Long> allowedKioskIds = null;
        if (!admin) {
            List<LocationEntity> availableKiosks = resolveAvailableKiosks(user, false);
            allowedKioskIds = availableKiosks.stream()
                    .map(LocationEntity::getId)
                    .collect(Collectors.toSet());
            if (kioskLocationId != null && !allowedKioskIds.contains(kioskLocationId)) {
                throw new BusinessException("No tienes acceso al kiosko seleccionado.");
            }
        }

        boolean onlyActive = activeOnly == null || activeOnly;
        LocalDate today = GuatemalaDateTime.today();
        List<KioskPromotionEntity> promotions = onlyActive
                ? kioskPromotionRepository.findByActiveTrueOrderByNameAsc()
                : kioskPromotionRepository.findAll().stream()
                .sorted(Comparator.comparing(item -> safeTrim(item.getName()), String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());

        final Set<Long> scopedKioskIds = allowedKioskIds;
        boolean manageView = !onlyActive && admin;
        return promotions.stream()
                .filter(p -> !onlyActive || isPromotionActiveOnDate(p, today))
                .filter(p -> manageView
                        || p.getKioskLocationId() == null
                        || (kioskLocationId != null && Objects.equals(p.getKioskLocationId(), kioskLocationId)))
                .filter(p -> manageView
                        || admin
                        || scopedKioskIds == null
                        || p.getKioskLocationId() == null
                        || scopedKioskIds.contains(p.getKioskLocationId()))
                .map(this::toPromotionResponse)
                .collect(Collectors.toList());
    }

    public KioskPromotionResponse createPromotion(KioskPromotionRequest request) throws BusinessException {
        UserEntity user = getCurrentUserOrThrow();
        if (!KioskAccessHelper.hasAllKiosksAccess(user)) {
            throw new BusinessException("Solo un administrador o logística puede crear promociones.");
        }
        validatePromotionRequest(request);
        KioskPromotionEntity entity = toPromotionEntity(request, user.getId());
        syncPromotionTiers(entity, request);
        return toPromotionResponse(kioskPromotionRepository.save(entity));
    }

    public KioskPromotionResponse updatePromotion(Long id, KioskPromotionRequest request)
            throws BusinessException, ResourceNotFoundException {
        UserEntity user = getCurrentUserOrThrow();
        if (!KioskAccessHelper.hasAllKiosksAccess(user)) {
            throw new BusinessException("Solo un administrador o logística puede editar promociones.");
        }
        validatePromotionRequest(request);
        KioskPromotionEntity entity = kioskPromotionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("KioskPromotion", id));
        applyPromotionRequest(entity, request, user.getId());
        syncPromotionTiers(entity, request);
        return toPromotionResponse(kioskPromotionRepository.save(entity));
    }

    public void deletePromotion(Long id) throws BusinessException, ResourceNotFoundException {
        UserEntity user = getCurrentUserOrThrow();
        if (!KioskAccessHelper.hasAllKiosksAccess(user)) {
            throw new BusinessException("Solo un administrador o logística puede eliminar promociones.");
        }
        KioskPromotionEntity entity = kioskPromotionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("KioskPromotion", id));
        kioskPromotionRepository.delete(entity);
    }

    @Transactional(readOnly = true)
    public List<KioskProductAvailabilityResponse> findAvailabilityInKiosks(
            Long productId,
            Long colorId,
            boolean includeCurrentKiosk,
            Long kioskLocationId
    ) throws BusinessException {
        if (productId == null) {
            throw new BusinessException("Debes indicar el producto para consultar disponibilidad.");
        }

        UserEntity user = getCurrentUserOrThrow();
        boolean admin = KioskAccessHelper.hasAllKiosksAccess(user);
        List<LocationEntity> availableKiosks = resolveAvailableKiosks(user, admin);
        LocationEntity currentKiosk = resolveTargetKiosk(availableKiosks, kioskLocationId);

        List<ProductInventoryLocation> rows = colorId != null
                ? productInventoryLocationRepository.findByProductIdAndColorId(productId, colorId)
                : productInventoryLocationRepository.findByProductId(productId);

        Set<Long> locationIds = rows.stream()
                .map(ProductInventoryLocation::getLocationId)
                .collect(Collectors.toSet());
        Map<Long, LocationEntity> locations = locationRepository.findAllById(locationIds).stream()
                .collect(Collectors.toMap(LocationEntity::getId, location -> location));

        Set<Long> allowedKioskIds = availableKiosks.stream()
                .map(LocationEntity::getId)
                .collect(Collectors.toSet());

        return rows.stream()
                .filter(row -> allowedKioskIds.contains(row.getLocationId()))
                .filter(row -> row.getQuantity() != null && row.getQuantity().compareTo(BigDecimal.ZERO) > 0)
                .filter(row -> includeCurrentKiosk
                        || row.getLocationId() == null
                        || !java.util.Objects.equals(row.getLocationId(), currentKiosk.getId()))
                .map(row -> {
                    LocationEntity location = locations.get(row.getLocationId());
                    return KioskProductAvailabilityResponse.builder()
                            .kioskId(row.getLocationId())
                            .kioskCode(location != null ? location.getCode() : "")
                            .kioskName(location != null ? location.getName() : "Kiosko")
                            .available(true)
                            .quantity(row.getQuantity())
                            .build();
                })
                .sorted(Comparator.comparing(KioskProductAvailabilityResponse::getKioskName, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());
    }

    Map<String, BigDecimal> aggregateItemQuantities(List<KioskPosSaleRequest.ItemRequest> items) {
        Map<String, BigDecimal> aggregated = new LinkedHashMap<>();
        for (KioskPosSaleRequest.ItemRequest item : items) {
            if (item == null || item.getProductId() == null) {
                continue;
            }
            String key = inventoryKey(
                    item.getProductId(),
                    item.getColorId(),
                    resolveItemHardwareCondition(item.getHardwareCondition()),
                    item.getSize());
            BigDecimal qty = item.getQuantity() != null ? item.getQuantity() : BigDecimal.ZERO;
            aggregated.merge(key, qty, BigDecimal::add);
        }
        return aggregated;
    }

    Map<String, ProductInventoryLocation> lockAndValidateStock(Long kioskId, Map<String, BigDecimal> aggregatedQty)
            throws BusinessException {
        List<String> sortedKeys = aggregatedQty.keySet().stream().sorted().toList();
        Map<String, ProductInventoryLocation> locked = new LinkedHashMap<>();
        boolean kioscoModuleActive = !kioscoStockRepository
                .findByLocationIdOrderByProductIdAscColorIdAscHardwareConditionAsc(kioskId).isEmpty();

        for (String key : sortedKeys) {
            ParsedInventoryKey parsed = parseInventoryKey(key);
            BigDecimal requested = aggregatedQty.get(key);
            ProductEntity product = productRepository.findById(parsed.productId()).orElse(null);
            String label = product != null ? product.getName() : "Producto";

            if (kioscoModuleActive) {
                String hardware = parsed.hardwareCondition();
                KioscoStockEntity kioscoRow = kioscoStockRepository
                        .findForUpdateByHardware(kioskId, parsed.productId(), parsed.colorId(), hardware)
                        .orElse(null);
                if (kioscoRow == null) {
                    throw buildInsufficientStockException(label, parsed.size(), BigDecimal.ZERO, requested);
                }
                validateKioscoStock(kioscoRow, parsed, requested, label);
            } else {
                ProductInventoryLocation row = productInventoryLocationRepository
                        .findWithLockByProductIdAndLocationIdAndColorId(
                                parsed.productId(), kioskId, parsed.colorId())
                        .orElseThrow(() -> new BusinessException(
                                "Stock insuficiente: no hay inventario para el producto solicitado en este kiosko."));
                validateLegacyStock(row, parsed, requested, label);
                locked.put(key, row);
                continue;
            }

            productInventoryLocationRepository
                    .findWithLockByProductIdAndLocationIdAndColorId(
                            parsed.productId(), kioskId, parsed.colorId())
                    .ifPresent(row -> locked.put(key, row));
        }
        return locked;
    }

    private void validateKioscoStock(
            KioscoStockEntity row,
            ParsedInventoryKey parsed,
            BigDecimal requested,
            String label
    ) throws BusinessException {
        Map<String, BigDecimal> positiveSizes = positiveSizesMap(row.getSizesData());
        BigDecimal available;
        if (positiveSizes != null && !positiveSizes.isEmpty()) {
            String sizeKey = ProductInventorySizesJson.normalizeKey(parsed.size());
            if (sizeKey.isEmpty()) {
                throw new BusinessException("Debe seleccionar talla para " + label + ".");
            }
            available = positiveSizes.getOrDefault(sizeKey, BigDecimal.ZERO);
            if (available.compareTo(BigDecimal.ZERO) <= 0) {
                available = positiveSizes.entrySet().stream()
                        .filter(e -> ProductInventorySizesJson.normalizeKey(e.getKey())
                                .equalsIgnoreCase(sizeKey))
                        .map(Map.Entry::getValue)
                        .findFirst()
                        .orElse(BigDecimal.ZERO);
            }
        } else {
            // sizes_data vacío o solo ceros: no inventar tallas desde legacy; usar current_stock.
            available = BigDecimal.valueOf(row.getCurrentStock() != null ? row.getCurrentStock() : 0);
        }
        if (requested.compareTo(available) > 0) {
            throw buildInsufficientStockException(label, parsed.size(), available, requested);
        }
    }

    private void validateLegacyStock(
            ProductInventoryLocation row,
            ParsedInventoryKey parsed,
            BigDecimal requested,
            String label
    ) throws BusinessException {
        Map<String, BigDecimal> positiveSizes = positiveSizesMap(row.getSizesData());
        BigDecimal available;
        if (positiveSizes != null && !positiveSizes.isEmpty()) {
            String sizeKey = ProductInventorySizesJson.normalizeKey(parsed.size());
            if (sizeKey.isEmpty()) {
                throw new BusinessException("Debe seleccionar talla para " + label + ".");
            }
            available = positiveSizes.getOrDefault(sizeKey, BigDecimal.ZERO);
            if (available.compareTo(BigDecimal.ZERO) <= 0) {
                available = positiveSizes.entrySet().stream()
                        .filter(e -> ProductInventorySizesJson.normalizeKey(e.getKey())
                                .equalsIgnoreCase(sizeKey))
                        .map(Map.Entry::getValue)
                        .findFirst()
                        .orElse(BigDecimal.ZERO);
            }
        } else {
            available = row.getQuantity() != null ? row.getQuantity() : BigDecimal.ZERO;
        }
        if (requested.compareTo(available) > 0) {
            throw buildInsufficientStockException(label, parsed.size(), available, requested);
        }
    }

    private BusinessException buildInsufficientStockException(
            String label,
            String size,
            BigDecimal available,
            BigDecimal requested
    ) {
        String sizeHint = size != null && !size.isBlank() ? " (talla " + size + ")" : "";
        return new BusinessException(String.format(
                "Stock insuficiente para %s%s. Disponible: %s, solicitado: %s.",
                label,
                sizeHint,
                available.stripTrailingZeros().toPlainString(),
                requested.stripTrailingZeros().toPlainString()));
    }

    /** Solo tallas con stock > 0 desde kiosco_stock (sin fallback a inventario legacy). */
    private Map<String, BigDecimal> resolveKioscoSizes(KioscoStockEntity kioscoRow) {
        if (kioscoRow == null) {
            return null;
        }
        return positiveSizesMap(kioscoRow.getSizesData());
    }

    private boolean hasPositiveSizeBreakdown(String sizesDataJson) {
        return positiveSizesMap(sizesDataJson) != null;
    }

    private String extractSizeFromSaleItemName(String productName) {
        if (productName == null) {
            return null;
        }
        int idx = productName.lastIndexOf(" T.");
        if (idx < 0) {
            return null;
        }
        String size = productName.substring(idx + 3).trim();
        return size.isEmpty() ? null : size;
    }

    @Transactional(readOnly = true)
    public KioskPosPromotionEstimateResponse estimatePromotionDiscount(
            KioskPosPromotionEstimateRequest request
    ) throws BusinessException, ResourceNotFoundException {
        if (request == null || request.getItems() == null || request.getItems().isEmpty()) {
            throw new BusinessException("Debes indicar al menos un producto para estimar el descuento.");
        }
        UserEntity user = getCurrentUserOrThrow();
        boolean admin = KioskAccessHelper.hasAllKiosksAccess(user);
        List<LocationEntity> availableKiosks = resolveAvailableKiosks(user, admin);
        LocationEntity kiosk = resolveTargetKiosk(availableKiosks, request.getKioskLocationId());
        LocalDate saleDate = GuatemalaDateTime.today();

        List<PreparedLine> preparedLines = buildPreparedLinesForEstimate(kiosk.getId(), request.getItems());
        BigDecimal subtotal = preparedLines.stream()
                .map(PreparedLine::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        KioskPromotionEntity promotion = null;
        if ((request.getManualDiscountPercent() == null
                || request.getManualDiscountPercent().compareTo(BigDecimal.ZERO) <= 0)
                && request.getPromotionId() != null) {
            promotion = resolvePromotionIfAny(request.getPromotionId(), saleDate, kiosk.getId());
        }

        KioskPosSaleRequest saleRequest = KioskPosSaleRequest.builder()
                .manualDiscountPercent(request.getManualDiscountPercent())
                .build();
        DiscountResolution resolution = resolveSaleDiscount(
                subtotal,
                preparedLines,
                false,
                saleRequest,
                promotion,
                kiosk.getId(),
                saleDate
        );

        return KioskPosPromotionEstimateResponse.builder()
                .subtotal(subtotal.setScale(2, RoundingMode.HALF_UP))
                .discountAmount(resolution.discountAmount())
                .totalAmount(subtotal.subtract(resolution.discountAmount()).max(BigDecimal.ZERO)
                        .setScale(2, RoundingMode.HALF_UP))
                .autoApplied(resolution.autoApplied())
                .promotionId(promotion != null ? promotion.getId() : resolution.promotionId())
                .promotionName(resolution.promotionName())
                .manualDiscountPercent(request.getManualDiscountPercent())
                .build();
    }

    BigDecimal calculatePromotionDiscount(
            BigDecimal subtotal,
            KioskPromotionEntity promotion,
            List<PreparedLine> lines
    ) {
        if (promotion == null || subtotal == null || subtotal.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        String type = normalizeDiscountType(promotion.getDiscountType());
        if ("TIERED_PERCENT".equals(type)) {
            return calculateTieredPercentDiscount(lines, List.of(promotion));
        }
        List<PreparedLine> eligibleLines = filterLinesByPromotionAudience(lines, promotion.getAudienceCategory());
        BigDecimal eligibleSubtotal = eligibleLines.stream()
                .map(PreparedLine::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (eligibleSubtotal.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        if ("COMBO".equals(type)) {
            return calculateComboDiscount(promotion, eligibleLines);
        }
        BigDecimal value = promotion.getDiscountValue() != null ? promotion.getDiscountValue() : BigDecimal.ZERO;
        if (value.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        if ("PERCENT".equals(type)) {
            // Líneas de la audiencia → % promo; resto elegible → 10% base (empaque 0%).
            BigDecimal discount = BigDecimal.ZERO;
            for (PreparedLine line : filterDiscountEligibleLines(lines)) {
                boolean inPromo = ProductAudienceCategory.productMatchesPromotion(
                        line.product().getAudienceCategory(),
                        promotion.getAudienceCategory()
                );
                BigDecimal pct = inPromo ? value : DEFAULT_POS_DISCOUNT_PERCENT;
                discount = discount.add(line.lineTotal()
                        .multiply(pct)
                        .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP));
            }
            return discount.max(BigDecimal.ZERO).min(subtotal).setScale(2, RoundingMode.HALF_UP);
        }
        return value.max(BigDecimal.ZERO).min(eligibleSubtotal).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Descuento por línea: si hay tier, usa ese %; si no, el 10% base.
     * Empaque nunca entra (isDiscountEligibleProduct).
     */
    BigDecimal calculateTieredPercentDiscount(List<PreparedLine> lines, List<KioskPromotionEntity> promotions) {
        return calculateTieredPercentDiscountResult(lines, promotions).discountAmount();
    }

    private TieredDiscountResult calculateTieredPercentDiscountResult(
            List<PreparedLine> lines,
            List<KioskPromotionEntity> promotions
    ) {
        if (lines == null || lines.isEmpty() || promotions == null || promotions.isEmpty()) {
            return new TieredDiscountResult(BigDecimal.ZERO, false);
        }
        BigDecimal discount = BigDecimal.ZERO;
        boolean anyTierMatch = false;
        for (PreparedLine line : lines) {
            if (!isDiscountEligibleProduct(line.product())) {
                continue;
            }
            BigDecimal promoPct = resolveBestTierPercentForLine(line, promotions);
            if (promoPct.compareTo(BigDecimal.ZERO) > 0) {
                anyTierMatch = true;
            }
            BigDecimal pct = promoPct.compareTo(BigDecimal.ZERO) > 0
                    ? promoPct
                    : DEFAULT_POS_DISCOUNT_PERCENT;
            discount = discount.add(line.lineTotal()
                    .multiply(pct)
                    .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP));
        }
        BigDecimal maxDiscount = lines.stream()
                .map(PreparedLine::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new TieredDiscountResult(
                discount.max(BigDecimal.ZERO).min(maxDiscount).setScale(2, RoundingMode.HALF_UP),
                anyTierMatch
        );
    }

    private DiscountResolution resolveSaleDiscount(
            BigDecimal subtotal,
            List<PreparedLine> lines,
            boolean exchangeSale,
            KioskPosSaleRequest request,
            KioskPromotionEntity selectedPromotion,
            Long kioskId,
            LocalDate saleDate
    ) {
        if (exchangeSale) {
            BigDecimal discount = request.getExchangeCreditAmount().min(subtotal).setScale(2, RoundingMode.HALF_UP);
            return new DiscountResolution(discount, null, buildExchangePromotionName(request.getExchangeSlipNumber()), false);
        }
        if (Boolean.TRUE.equals(request.getChargeWithoutDiscount())) {
            return new DiscountResolution(BigDecimal.ZERO, null, null, false);
        }
        if (request.getManualDiscountPercent() != null
                && request.getManualDiscountPercent().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal pct = request.getManualDiscountPercent().max(BigDecimal.ZERO).min(new BigDecimal("100"));
            BigDecimal eligibleSubtotal = eligibleDiscountSubtotal(lines);
            BigDecimal discount = eligibleSubtotal.multiply(pct)
                    .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
            return applyDefaultPosDiscountFloor(
                    new DiscountResolution(
                            discount,
                            null,
                            resolvePromotionDisplayName(null, pct),
                            false
                    ),
                    lines
            );
        }
        if (selectedPromotion != null) {
            DiscountResolution resolved = new DiscountResolution(
                    calculatePromotionDiscount(subtotal, selectedPromotion, lines),
                    selectedPromotion.getId(),
                    resolvePromotionDisplayName(selectedPromotion, null),
                    false
            );
            return applyDefaultPosDiscountFloor(resolved, lines);
        }
        return applyDefaultPosDiscountFloor(
                resolveAutoPromotionDiscount(subtotal, lines, kioskId, saleDate),
                lines
        );
    }

    private BigDecimal calculateDefaultPosDiscount(List<PreparedLine> lines) {
        return eligibleDiscountSubtotal(lines)
                .multiply(DEFAULT_POS_DISCOUNT_PERCENT)
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
    }

    /**
     * Piso cart-level solo si el descuento resuelto queda por debajo del 10% base
     * (p. ej. combo que no aplica). El descuento TIERED/PERCENT ya aplica 10% por línea
     * en productos sin match de promo.
     */
    private DiscountResolution applyDefaultPosDiscountFloor(DiscountResolution resolved, List<PreparedLine> lines) {
        BigDecimal defaultDiscount = calculateDefaultPosDiscount(lines);
        if (defaultDiscount.compareTo(BigDecimal.ZERO) <= 0) {
            return resolved;
        }
        if (resolved.discountAmount().compareTo(defaultDiscount) >= 0) {
            return resolved;
        }
        return new DiscountResolution(
                defaultDiscount,
                null,
                DEFAULT_POS_DISCOUNT_NAME,
                true
        );
    }

    private DiscountResolution resolveAutoPromotionDiscount(
            BigDecimal subtotal,
            List<PreparedLine> lines,
            Long kioskId,
            LocalDate saleDate
    ) {
        List<KioskPromotionEntity> activePromotions = loadActivePromotions(kioskId, saleDate);
        if (activePromotions.isEmpty()) {
            return new DiscountResolution(BigDecimal.ZERO, null, null, false);
        }

        BigDecimal bestDiscount = BigDecimal.ZERO;
        Long bestPromotionId = null;
        String bestPromotionName = null;

        List<KioskPromotionEntity> tieredPromotions = activePromotions.stream()
                .filter(promotion -> "TIERED_PERCENT".equals(normalizeDiscountType(promotion.getDiscountType())))
                .collect(Collectors.toList());
        TieredDiscountResult tieredResult = calculateTieredPercentDiscountResult(lines, tieredPromotions);
        if (tieredResult.anyTierMatch() && tieredResult.discountAmount().compareTo(bestDiscount) > 0) {
            bestDiscount = tieredResult.discountAmount();
            bestPromotionName = "Promoción automática";
        }

        for (KioskPromotionEntity promotion : activePromotions) {
            if ("TIERED_PERCENT".equals(normalizeDiscountType(promotion.getDiscountType()))) {
                continue;
            }
            BigDecimal discount = calculatePromotionDiscount(subtotal, promotion, lines);
            if (discount.compareTo(bestDiscount) > 0) {
                bestDiscount = discount;
                bestPromotionId = promotion.getId();
                bestPromotionName = resolvePromotionDisplayName(promotion, null);
            }
        }

        if (bestDiscount.compareTo(BigDecimal.ZERO) <= 0) {
            return new DiscountResolution(BigDecimal.ZERO, null, null, false);
        }
        return new DiscountResolution(
                bestDiscount.setScale(2, RoundingMode.HALF_UP),
                bestPromotionId,
                bestPromotionName,
                true
        );
    }

    private BigDecimal resolveBestTierPercentForLine(PreparedLine line, List<KioskPromotionEntity> promotions) {
        BigDecimal best = BigDecimal.ZERO;
        for (KioskPromotionEntity promotion : promotions) {
            if (!"TIERED_PERCENT".equals(normalizeDiscountType(promotion.getDiscountType()))) {
                continue;
            }
            for (KioskPromotionTierEntity tier : loadTierEntities(promotion)) {
                if (!tierMatchesLine(line, tier)) {
                    continue;
                }
                BigDecimal value = tier.getDiscountValue() != null ? tier.getDiscountValue() : BigDecimal.ZERO;
                if (value.compareTo(best) > 0) {
                    best = value;
                }
            }
        }
        return best;
    }

    private boolean tierMatchesLine(PreparedLine line, KioskPromotionTierEntity tier) {
        ProductEntity product = line.product();
        if (product == null || tier == null || tier.getCategoryId() == null) {
            return false;
        }
        if (!isDiscountEligibleProduct(product)) {
            return false;
        }
        if (!Objects.equals(product.getCategoryId(), tier.getCategoryId())) {
            return false;
        }
        return ProductAudienceCategory.productMatchesPromotion(
                product.getAudienceCategory(),
                tier.getAudienceCategory()
        );
    }

    private List<KioskPromotionEntity> loadActivePromotions(Long kioskId, LocalDate saleDate) {
        return kioskPromotionRepository.findByActiveTrueOrderByNameAsc().stream()
                .filter(promotion -> isPromotionActiveOnDate(promotion, saleDate))
                .filter(promotion -> promotion.getKioskLocationId() == null
                        || Objects.equals(promotion.getKioskLocationId(), kioskId))
                .collect(Collectors.toList());
    }

    private List<KioskPromotionTierEntity> loadTierEntities(KioskPromotionEntity promotion) {
        List<KioskPromotionTierEntity> tiers = promotion.getTiers();
        if ((tiers == null || tiers.isEmpty()) && promotion.getId() != null) {
            tiers = kioskPromotionTierRepository.findByPromotionIdOrderByAudienceCategoryAsc(promotion.getId());
        }
        return tiers != null ? tiers : List.of();
    }

    private BigDecimal eligibleDiscountSubtotal(List<PreparedLine> lines) {
        return filterDiscountEligibleLines(lines).stream()
                .map(PreparedLine::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private List<PreparedLine> filterDiscountEligibleLines(List<PreparedLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        return lines.stream()
                .filter(line -> line.product() != null && isDiscountEligibleProduct(line.product()))
                .toList();
    }

    private boolean isDiscountEligibleProduct(ProductEntity product) {
        return product != null && !isPackagingProduct(product);
    }

    private List<PreparedLine> buildPreparedLinesForEstimate(
            Long kioskId,
            List<KioskPosPromotionEstimateRequest.ItemRequest> items
    ) throws BusinessException, ResourceNotFoundException {
        List<PreparedLine> preparedLines = new ArrayList<>();
        for (KioskPosPromotionEstimateRequest.ItemRequest itemRequest : items) {
            if (itemRequest == null || itemRequest.getProductId() == null) {
                throw new BusinessException("Todos los renglones deben tener producto.");
            }
            BigDecimal quantity = itemRequest.getQuantity();
            if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException("La cantidad debe ser mayor a cero para todos los productos.");
            }
            ProductEntity product = productRepository.findById(itemRequest.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product", itemRequest.getProductId()));
            ColorEntity color = null;
            if (itemRequest.getColorId() != null) {
                color = colorRepository.findById(itemRequest.getColorId())
                        .orElseThrow(() -> new ResourceNotFoundException("Color", itemRequest.getColorId()));
            }
            String sizeLabel = ProductInventorySizesJson.normalizeKey(itemRequest.getSize());
            BigDecimal unitPrice = resolvePosUnitPrice(product);
            BigDecimal lineTotal = unitPrice.multiply(quantity).setScale(2, RoundingMode.HALF_UP);
            preparedLines.add(new PreparedLine(
                    product,
                    color,
                    sizeLabel.isEmpty() ? null : sizeLabel,
                    quantity,
                    unitPrice,
                    lineTotal
            ));
        }
        return preparedLines;
    }

    private List<PreparedLine> filterLinesByPromotionAudience(List<PreparedLine> lines, String promotionAudience) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        return lines.stream()
                .filter(line -> line.product() != null && isDiscountEligibleProduct(line.product()))
                .filter(line -> ProductAudienceCategory.productMatchesPromotion(
                        line.product().getAudienceCategory(), promotionAudience))
                .toList();
    }

    BigDecimal calculateComboDiscount(KioskPromotionEntity promotion, List<PreparedLine> lines) {
        int buyQty = promotion.getComboBuyQty() != null ? promotion.getComboBuyQty() : 0;
        int payQty = promotion.getComboPayQty() != null ? promotion.getComboPayQty() : 0;
        if (buyQty <= 0 || payQty <= 0 || payQty >= buyQty) {
            return BigDecimal.ZERO;
        }
        int freePerGroup = buyQty - payQty;
        List<BigDecimal> units = new ArrayList<>();
        for (PreparedLine line : lines) {
            int wholeUnits = line.quantity().setScale(0, RoundingMode.DOWN).intValue();
            for (int i = 0; i < wholeUnits; i++) {
                units.add(line.unitPrice());
            }
        }
        if (units.isEmpty()) {
            return BigDecimal.ZERO;
        }
        units.sort(Comparator.naturalOrder());
        int freeUnits = (units.size() / buyQty) * freePerGroup;
        BigDecimal discount = BigDecimal.ZERO;
        for (int i = 0; i < freeUnits && i < units.size(); i++) {
            discount = discount.add(units.get(i));
        }
        return discount.setScale(2, RoundingMode.HALF_UP);
    }

    PaymentSnapshot resolvePaymentSnapshot(
            String paymentMethod,
            BigDecimal total,
            BigDecimal amountReceived,
            BigDecimal cashAmount,
            BigDecimal cardAmount
    ) throws BusinessException {
        BigDecimal safeTotal = total != null ? total : BigDecimal.ZERO;
        if ("EFECTIVO".equals(paymentMethod)) {
            BigDecimal received = amountReceived != null ? amountReceived : BigDecimal.ZERO;
            if (received.compareTo(safeTotal) < 0) {
                throw new BusinessException("El monto recibido debe ser mayor o igual al total.");
            }
            BigDecimal change = received.subtract(safeTotal).setScale(2, RoundingMode.HALF_UP);
            return new PaymentSnapshot(received, change, received, null);
        }
        if ("MIXTO".equals(paymentMethod)) {
            BigDecimal cash = cashAmount != null ? cashAmount : BigDecimal.ZERO;
            BigDecimal card = cardAmount != null ? cardAmount : BigDecimal.ZERO;
            BigDecimal sum = cash.add(card).setScale(2, RoundingMode.HALF_UP);
            if (sum.compareTo(safeTotal) != 0) {
                throw new BusinessException("En pago mixto, efectivo + tarjeta debe igualar el total.");
            }
            if (cash.compareTo(BigDecimal.ZERO) > 0 && amountReceived != null
                    && amountReceived.compareTo(cash) < 0) {
                throw new BusinessException("El efectivo recibido debe cubrir la parte en efectivo.");
            }
            BigDecimal received = cash.compareTo(BigDecimal.ZERO) > 0
                    ? (amountReceived != null ? amountReceived : cash)
                    : null;
            BigDecimal change = received != null
                    ? received.subtract(cash).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            return new PaymentSnapshot(received, change, cash, card);
        }
        return new PaymentSnapshot(null, null, null, null);
    }

    private String generateSaleNumber(LocalDate saleDate) {
        KioskSaleSequenceEntity sequence = kioskSaleSequenceRepository.findWithLockBySaleDate(saleDate)
                .orElseGet(() -> KioskSaleSequenceEntity.builder()
                        .saleDate(saleDate)
                        .lastNumber(0)
                        .build());
        int next = (sequence.getLastNumber() != null ? sequence.getLastNumber() : 0) + 1;
        sequence.setLastNumber(next);
        kioskSaleSequenceRepository.save(sequence);
        return String.format("POS-%s-%04d", saleDate.format(SALE_NUMBER_DATE), next);
    }

    private void validateCardFields(
            String paymentMethod,
            BigDecimal cardAmount,
            String cardAuthNumber,
            String cardLast4,
            String cardBrand,
            BigDecimal cardVoucherAmount,
            boolean requireVoucherAmount
    ) throws BusinessException {
        if (!requiresCardData(paymentMethod, cardAmount)) {
            return;
        }
        if (safeTrim(cardAuthNumber).isBlank()) {
            throw new BusinessException("Debes indicar el número de voucher de la tarjeta.");
        }
        if (!CARD_LAST4_PATTERN.matcher(safeTrim(cardLast4)).matches()) {
            throw new BusinessException("Los últimos 4 dígitos de la tarjeta deben ser 4 números.");
        }
        if (safeTrim(cardBrand).isBlank()) {
            throw new BusinessException("Debes indicar la marca de la tarjeta (VISA, MC o AMEX).");
        }
        normalizeCardBrand(cardBrand);
        if (requireVoucherAmount
                && cardVoucherAmount != null
                && cardVoucherAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("El monto del voucher de la tarjeta debe ser mayor a cero.");
        }
    }

    private void validateSplitCardPayment(
            String paymentMethod,
            BigDecimal totalAmount,
            BigDecimal cardAmount,
            BigDecimal card2Amount,
            String card2AuthNumber,
            String card2Last4,
            String card2Brand,
            BigDecimal card2VoucherAmount,
            boolean requireVoucherAmount
    ) throws BusinessException {
        if (!"TARJETA".equals(paymentMethod) || !isSplitCardPayment(card2Amount)) {
            return;
        }
        BigDecimal safeTotal = totalAmount != null ? totalAmount : BigDecimal.ZERO;
        BigDecimal firstCard = cardAmount != null ? cardAmount : BigDecimal.ZERO;
        BigDecimal secondCard = card2Amount != null ? card2Amount : BigDecimal.ZERO;
        if (firstCard.compareTo(BigDecimal.ZERO) <= 0 || secondCard.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("En pago con dos tarjetas, ambos montos deben ser mayores a cero.");
        }
        BigDecimal sum = firstCard.add(secondCard).setScale(2, RoundingMode.HALF_UP);
        if (sum.compareTo(safeTotal) != 0) {
            throw new BusinessException("La suma de las dos tarjetas debe igualar el total de la venta.");
        }
        if (safeTrim(card2AuthNumber).isBlank()) {
            throw new BusinessException("Debes indicar el número de voucher de la segunda tarjeta.");
        }
        if (!CARD_LAST4_PATTERN.matcher(safeTrim(card2Last4)).matches()) {
            throw new BusinessException("Los últimos 4 dígitos de la segunda tarjeta deben ser 4 números.");
        }
        if (safeTrim(card2Brand).isBlank()) {
            throw new BusinessException("Debes indicar la marca de la segunda tarjeta (VISA, MC o AMEX).");
        }
        normalizeCardBrand(card2Brand);
        if (requireVoucherAmount
                && card2VoucherAmount != null
                && card2VoucherAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("El monto del voucher de la segunda tarjeta debe ser mayor a cero.");
        }
    }

    static boolean isSplitCardPayment(BigDecimal card2Amount) {
        return card2Amount != null && card2Amount.compareTo(BigDecimal.ZERO) > 0;
    }

    static boolean hasSplitCardPayment(KioskSaleEntity sale) {
        return sale != null && isSplitCardPayment(sale.getCard2Amount());
    }

    private BigDecimal resolveStoredCardAmount(
            String paymentMethod,
            BigDecimal paymentCardAmount,
            BigDecimal requestCardAmount,
            BigDecimal card2Amount
    ) {
        if (isSplitCardPayment(card2Amount)) {
            return requestCardAmount != null
                    ? requestCardAmount.setScale(2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
        }
        return paymentCardAmount;
    }

    /**
     * Persiste el monto del voucher físico. No se usa para total_amount ni FEL.
     * Si no viene, se usa el monto de tarjeta de la factura (sin diferencia).
     */
    private BigDecimal resolveStoredCardVoucherAmount(
            String paymentMethod,
            BigDecimal storedCardAmount,
            BigDecimal cardVoucherAmount
    ) {
        if (!requiresCardData(paymentMethod, storedCardAmount)) {
            return null;
        }
        if (cardVoucherAmount != null && cardVoucherAmount.compareTo(BigDecimal.ZERO) > 0) {
            return cardVoucherAmount.setScale(2, RoundingMode.HALF_UP);
        }
        return storedCardAmount != null
                ? storedCardAmount.setScale(2, RoundingMode.HALF_UP)
                : null;
    }

    private BigDecimal voucherDifference(BigDecimal voucherAmount, BigDecimal expectedCardAmount) {
        if (voucherAmount == null || expectedCardAmount == null) {
            return null;
        }
        return voucherAmount.subtract(expectedCardAmount).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal resolveVoucherAmountForReport(KioskSaleEntity sale, int cardSlot) {
        if (cardSlot == 2) {
            if (sale.getCard2VoucherAmount() != null) {
                return sale.getCard2VoucherAmount();
            }
            return sale.getCard2Amount() != null ? sale.getCard2Amount() : BigDecimal.ZERO;
        }
        if (cardSlot == 1) {
            if (sale.getCardVoucherAmount() != null) {
                return sale.getCardVoucherAmount();
            }
            return sale.getCardAmount() != null ? sale.getCardAmount() : BigDecimal.ZERO;
        }
        if (sale.getCardVoucherAmount() != null) {
            BigDecimal second = sale.getCard2VoucherAmount() != null
                    ? sale.getCard2VoucherAmount()
                    : (sale.getCard2Amount() != null ? sale.getCard2Amount() : BigDecimal.ZERO);
            return sale.getCardVoucherAmount().add(second);
        }
        return resolveCardAmountForReport(sale);
    }

    private KioskVoucherReportRowResponse buildVoucherReportRow(
            KioskSaleEntity sale,
            LocationEntity kiosk,
            Map<Long, TaxInvoiceEntity> invoicesById,
            String defaultCardBrand,
            int cardSlot
    ) {
        String voucherNumber;
        String cardLast4;
        String cardBrand;
        BigDecimal amount;
        BigDecimal invoiceCardAmount;
        if (cardSlot == 2) {
            voucherNumber = safeTrim(sale.getCard2AuthNumber());
            cardLast4 = safeTrim(sale.getCard2Last4());
            cardBrand = resolveCardBrandForReport(sale.getCard2Brand(), defaultCardBrand);
            amount = resolveVoucherAmountForReport(sale, 2);
            invoiceCardAmount = sale.getCard2Amount() != null ? sale.getCard2Amount() : BigDecimal.ZERO;
        } else if (cardSlot == 1) {
            voucherNumber = safeTrim(sale.getCardAuthNumber());
            cardLast4 = safeTrim(sale.getCardLast4());
            cardBrand = resolveCardBrandForReport(sale.getCardBrand(), defaultCardBrand);
            amount = resolveVoucherAmountForReport(sale, 1);
            invoiceCardAmount = sale.getCardAmount() != null ? sale.getCardAmount() : BigDecimal.ZERO;
        } else {
            voucherNumber = safeTrim(sale.getCardAuthNumber());
            cardLast4 = safeTrim(sale.getCardLast4());
            cardBrand = resolveCardBrandForReport(sale, defaultCardBrand);
            amount = resolveVoucherAmountForReport(sale, 0);
            invoiceCardAmount = resolveCardAmountForReport(sale);
        }
        BigDecimal scaledAmount = amount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal scaledInvoice = invoiceCardAmount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal difference = scaledAmount.subtract(scaledInvoice).setScale(2, RoundingMode.HALF_UP);
        return KioskVoucherReportRowResponse.builder()
                .id(sale.getId())
                .saleId(sale.getId())
                .invoiceNumber(resolveSaleInvoiceLabel(sale, invoicesById))
                .cardBrand(cardBrand)
                .amount(scaledAmount)
                .invoiceCardAmount(scaledInvoice)
                .difference(difference)
                .voucherNumber(voucherNumber)
                .cardLast4(cardLast4)
                .description(buildVoucherDescription(voucherNumber, cardLast4, difference))
                .soldAt(sale.getSoldAt())
                .kioskLocationId(sale.getKioskLocationId())
                .kioskCode(kiosk != null ? kiosk.getCode() : "")
                .kioskName(kiosk != null ? kiosk.getName() : "Kiosko")
                .build();
    }

    private boolean requiresCardData(String paymentMethod, BigDecimal cardAmount) {
        return "TARJETA".equals(paymentMethod)
                || ("MIXTO".equals(paymentMethod) && cardAmount != null && cardAmount.compareTo(BigDecimal.ZERO) > 0);
    }

    private String resolveStoredCardBrand(String paymentMethod, BigDecimal cardAmount, String cardBrand)
            throws BusinessException {
        if (!requiresCardData(paymentMethod, cardAmount)) {
            return "";
        }
        return normalizeCardBrand(cardBrand);
    }

    private String normalizeCardBrand(String cardBrand) throws BusinessException {
        String normalized = safeTrim(cardBrand).toUpperCase(Locale.ROOT);
        if ("MASTERCARD".equals(normalized) || "MASTER".equals(normalized)) {
            normalized = "MC";
        }
        if (!ALLOWED_CARD_BRANDS.contains(normalized)) {
            throw new BusinessException("Marca de tarjeta no válida. Use VISA, MC o AMEX.");
        }
        return normalized;
    }

    private String resolveKioskEncargadaName(LocationEntity kiosk) {
        if (kiosk == null) {
            return "";
        }
        if (kiosk.getEncargado() != null) {
            String name = buildUserFullName(kiosk.getEncargado());
            if (!name.isBlank()) {
                return name.toUpperCase(Locale.ROOT);
            }
        }
        if (kiosk.getEncargadoId() != null) {
            UserEntity encargado = userRepository.findById(kiosk.getEncargadoId()).orElse(null);
            String name = buildUserFullName(encargado);
            if (!name.isBlank()) {
                return name.toUpperCase(Locale.ROOT);
            }
        }
        return "";
    }

    private record InternalInvoiceRange(String from, String to) {}

    private InternalInvoiceRange resolveInternalInvoiceRange(List<String> labels) {
        if (labels == null || labels.isEmpty()) {
            return new InternalInvoiceRange("", "");
        }
        List<ParsedInternalInvoice> parsed = labels.stream()
                .map(ParsedInternalInvoice::parse)
                .sorted()
                .toList();
        return new InternalInvoiceRange(parsed.get(0).label(), parsed.get(parsed.size() - 1).label());
    }

    private record ParsedInternalInvoice(String series, int number, String label)
            implements Comparable<ParsedInternalInvoice> {
        static ParsedInternalInvoice parse(String raw) {
            String label = safeTrimStatic(raw);
            if (label.isBlank()) {
                return new ParsedInternalInvoice("", 0, "");
            }
            String normalized = label.toUpperCase(Locale.ROOT);
            int dash = normalized.lastIndexOf('-');
            if (dash <= 0 || dash >= normalized.length() - 1) {
                return new ParsedInternalInvoice(normalized, 0, normalized);
            }
            String series = normalized.substring(0, dash);
            try {
                int num = Integer.parseInt(normalized.substring(dash + 1));
                return new ParsedInternalInvoice(series, num, normalized);
            } catch (NumberFormatException ex) {
                return new ParsedInternalInvoice(normalized, 0, normalized);
            }
        }

        @Override
        public int compareTo(ParsedInternalInvoice other) {
            if (other == null) {
                return 1;
            }
            int bySeries = series.compareTo(other.series);
            if (bySeries != 0) {
                return bySeries;
            }
            return Integer.compare(number, other.number);
        }
    }

    static String resolveCardBrandForReport(KioskSaleEntity sale, String defaultBrand) {
        if (sale == null) {
            return resolveCardBrandForReport((String) null, defaultBrand);
        }
        return resolveCardBrandForReport(sale.getCardBrand(), defaultBrand);
    }

    static String resolveCardBrandForReport(String storedBrand, String defaultBrand) {
        String stored = safeTrimStatic(storedBrand).toUpperCase(Locale.ROOT);
        if ("MASTERCARD".equals(stored) || "MASTER".equals(stored)) {
            stored = "MC";
        }
        if (ALLOWED_CARD_BRANDS.contains(stored)) {
            return stored;
        }
        String fallback = safeTrimStatic(defaultBrand).toUpperCase(Locale.ROOT);
        return fallback.isBlank() ? "VISA" : fallback;
    }

    private String inventoryKey(Long productId, Long colorId) {
        return productId + ":" + (colorId != null ? colorId : "null");
    }

    private static final String PACKAGING_PRODUCT_CODE_PREFIX = "SUM";
    /** Descuento base POS sobre precio de catálogo (no acumula con promos; la promo mayor reemplaza). */
    private static final BigDecimal DEFAULT_POS_DISCOUNT_PERCENT = new BigDecimal("10");
    private static final String DEFAULT_POS_DISCOUNT_NAME = "Descuento 10%";

    private BigDecimal resolvePosUnitPrice(ProductEntity product) {
        if (product == null) {
            return BigDecimal.ZERO;
        }
        if (isPackagingProduct(product)) {
            if (product.getSalePrice() != null && product.getSalePrice().compareTo(BigDecimal.ZERO) > 0) {
                return product.getSalePrice().setScale(2, RoundingMode.HALF_UP);
            }
            if (product.getSellerPrice() != null && product.getSellerPrice().compareTo(BigDecimal.ZERO) > 0) {
                return product.getSellerPrice().setScale(2, RoundingMode.HALF_UP);
            }
            return BigDecimal.ZERO;
        }
        if (product.getSalePrice() != null && product.getSalePrice().compareTo(BigDecimal.ZERO) > 0) {
            return product.getSalePrice().setScale(2, RoundingMode.HALF_UP);
        }
        if (product.getDiscountedPrice() != null && product.getDiscountedPrice().compareTo(BigDecimal.ZERO) > 0) {
            return product.getDiscountedPrice().setScale(2, RoundingMode.HALF_UP);
        }
        if (product.getSellerPrice() != null && product.getSellerPrice().compareTo(BigDecimal.ZERO) > 0) {
            return product.getSellerPrice().setScale(2, RoundingMode.HALF_UP);
        }
        return BigDecimal.ZERO;
    }

    private boolean isPackagingProduct(ProductEntity product) {
        if (product == null || product.getCode() == null) {
            return false;
        }
        return product.getCode().trim().toUpperCase(Locale.ROOT).startsWith(PACKAGING_PRODUCT_CODE_PREFIX);
    }

    private boolean isInactiveProduct(ProductEntity product) {
        String status = safeTrim(product.getStatus());
        return status != null && "INACTIVE".equalsIgnoreCase(status);
    }

    private void appendMissingPackagingCatalogItems(
            List<KioskPosContextResponse.InventoryItem> rawInventory,
            Map<Long, ProductEntity> productsById,
            Map<Long, ProductCategoryEntity> categoriesById) {
        Set<String> existingKeys = rawInventory.stream()
                .map(item -> inventoryKey(item.getProductId(), item.getColorId()))
                .collect(Collectors.toSet());

        for (ProductEntity product : productRepository.findByCodeStartingWithIgnoreCaseOrderByCodeAsc("SUM")) {
            if (!isPackagingProduct(product) || isInactiveProduct(product)) {
                continue;
            }
            String key = inventoryKey(product.getId(), null);
            if (existingKeys.contains(key)) {
                continue;
            }
            productsById.putIfAbsent(product.getId(), product);
            ProductCategoryEntity category = null;
            if (product.getCategoryId() != null) {
                category = categoriesById.computeIfAbsent(
                        product.getCategoryId(),
                        id -> productCategoryRepository.findById(id).orElse(null));
            }
            rawInventory.add(KioskPosContextResponse.InventoryItem.builder()
                    .productId(product.getId())
                    .productCode(product.getCode())
                    .productName(product.getName())
                    .productImageUrl(safeTrim(product.getImageUrl()))
                    .colorId(null)
                    .colorName("")
                    .categoryId(category != null ? category.getId() : null)
                    .categoryName(category != null ? category.getName() : "")
                    .audienceCategory(ProductAudienceCategory.normalizeProductAudience(product.getAudienceCategory()))
                    .quantity(BigDecimal.ZERO)
                    .suggestedUnitPrice(resolvePosUnitPrice(product))
                    .sizes(null)
                    .build());
            existingKeys.add(key);
        }
    }

    private String inventoryKey(Long productId, Long colorId, String hardwareCondition, String size) {
        String hardware = ProductHardwareCondition.normalize(hardwareCondition);
        if (hardware == null) {
            hardware = ProductHardwareCondition.NUEVO;
        }
        String base = productId + ":" + (colorId != null ? colorId : "null") + ":" + hardware;
        String normalized = ProductInventorySizesJson.normalizeKey(size);
        if (!normalized.isEmpty()) {
            return base + ":" + normalized;
        }
        return base;
    }

    private String resolveItemHardwareCondition(String hardwareCondition) {
        String hardware = ProductHardwareCondition.normalize(hardwareCondition);
        return hardware != null ? hardware : ProductHardwareCondition.NUEVO;
    }

    private String inventoryKey(Long productId, Long colorId, String size) {
        return inventoryKey(productId, colorId, ProductHardwareCondition.NUEVO, size);
    }

    private Map<String, BigDecimal> positiveSizesMap(String sizesDataJson) {
        Map<String, BigDecimal> parsed = ProductInventorySizesJson.parse(sizesDataJson);
        parsed.entrySet().removeIf(e -> e.getValue() == null || e.getValue().compareTo(BigDecimal.ZERO) <= 0);
        return parsed.isEmpty() ? null : parsed;
    }

    private record ParsedInventoryKey(Long productId, Long colorId, String size, String hardwareCondition) {}

    private ParsedInventoryKey parseInventoryKey(String key) {
        String[] parts = key.split(":", -1);
        if (parts.length < 2) {
            throw new IllegalArgumentException("Clave de inventario inválida: " + key);
        }
        Long productId = Long.parseLong(parts[0]);
        String colorPart = parts[1];
        Long colorId = "null".equals(colorPart) ? null : Long.parseLong(colorPart);
        String hardware = ProductHardwareCondition.NUEVO;
        String size = null;
        if (parts.length >= 3 && !parts[2].isBlank()) {
            String third = parts[2];
            String normalizedHardware = ProductHardwareCondition.normalize(third);
            if (normalizedHardware != null) {
                hardware = normalizedHardware;
                if (parts.length >= 4 && !parts[3].isBlank()) {
                    size = parts[3];
                }
            } else {
                size = third;
            }
        }
        if (size != null && size.isBlank()) {
            size = null;
        }
        return new ParsedInventoryKey(productId, colorId, size, hardware);
    }

    private boolean isPromotionActiveOnDate(KioskPromotionEntity promotion, LocalDate date) {
        if (promotion == null || !Boolean.TRUE.equals(promotion.getActive())) {
            return false;
        }
        if (promotion.getStartDate() != null && date.isBefore(promotion.getStartDate())) {
            return false;
        }
        return promotion.getEndDate() == null || !date.isAfter(promotion.getEndDate());
    }

    private KioskPromotionEntity toPromotionEntity(KioskPromotionRequest request, Long userId) {
        String discountType = normalizeDiscountType(request.getDiscountType());
        BigDecimal discountValue = request.getDiscountValue();
        if ("COMBO".equals(discountType) && (discountValue == null || discountValue.compareTo(BigDecimal.ZERO) <= 0)) {
            discountValue = BigDecimal.ZERO;
        }
        if ("TIERED_PERCENT".equals(discountType)) {
            discountValue = BigDecimal.ZERO;
        }
        return KioskPromotionEntity.builder()
                .name(safeTrim(request.getName()))
                .description(safeTrim(request.getDescription()))
                .discountType(discountType)
                .discountValue(discountValue)
                .comboBuyQty(request.getComboBuyQty())
                .comboPayQty(request.getComboPayQty())
                .kioskLocationId(request.getKioskLocationId())
                .audienceCategory("TIERED_PERCENT".equals(discountType)
                        ? null
                        : ProductAudienceCategory.normalizePromotionAudience(request.getAudienceCategory()))
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .active(request.getActive() == null || request.getActive())
                .createdBy(userId)
                .updatedBy(userId)
                .build();
    }

    private void applyPromotionRequest(KioskPromotionEntity entity, KioskPromotionRequest request, Long userId) {
        String discountType = normalizeDiscountType(request.getDiscountType());
        entity.setName(safeTrim(request.getName()));
        entity.setDescription(safeTrim(request.getDescription()));
        entity.setDiscountType(discountType);
        entity.setDiscountValue("TIERED_PERCENT".equals(discountType)
                ? BigDecimal.ZERO
                : request.getDiscountValue());
        entity.setComboBuyQty(request.getComboBuyQty());
        entity.setComboPayQty(request.getComboPayQty());
        entity.setKioskLocationId(request.getKioskLocationId());
        entity.setAudienceCategory("TIERED_PERCENT".equals(discountType)
                ? null
                : ProductAudienceCategory.normalizePromotionAudience(request.getAudienceCategory()));
        entity.setStartDate(request.getStartDate());
        entity.setEndDate(request.getEndDate());
        entity.setActive(request.getActive() == null || request.getActive());
        entity.setUpdatedBy(userId);
    }

    private void syncPromotionTiers(KioskPromotionEntity entity, KioskPromotionRequest request) {
        if (!"TIERED_PERCENT".equals(normalizeDiscountType(entity.getDiscountType()))) {
            if (entity.getTiers() != null) {
                entity.getTiers().clear();
            }
            return;
        }
        entity.setDiscountValue(BigDecimal.ZERO);
        entity.setAudienceCategory(null);
        if (entity.getTiers() == null) {
            entity.setTiers(new ArrayList<>());
        }
        entity.getTiers().clear();
        entity.getTiers().addAll(buildTierEntities(entity, request.getTiers()));
    }

    private List<KioskPromotionTierEntity> buildTierEntities(
            KioskPromotionEntity promotion,
            List<KioskPromotionTierRequest> tiers
    ) {
        if (tiers == null || tiers.isEmpty()) {
            return List.of();
        }
        return tiers.stream()
                .filter(Objects::nonNull)
                .map(tier -> KioskPromotionTierEntity.builder()
                        .promotion(promotion)
                        .audienceCategory(ProductAudienceCategory.normalizeTierAudience(tier.getAudienceCategory()))
                        .categoryId(tier.getCategoryId())
                        .discountValue(tier.getDiscountValue() != null ? tier.getDiscountValue() : BigDecimal.ZERO)
                        .build())
                .collect(Collectors.toList());
    }

    private UserEntity getCurrentUserOrThrow() throws BusinessException {
        Long currentUserId = securityUtil.getCurrentUserId();
        if (currentUserId == null) {
            throw new BusinessException("No se pudo identificar el usuario autenticado.");
        }
        return userRepository.findById(currentUserId)
                .orElseThrow(() -> new BusinessException("No se encontró el usuario autenticado."));
    }

    private List<LocationEntity> resolveAvailableKiosks(UserEntity user, boolean admin) throws BusinessException {
        List<LocationEntity> kiosks;
        if (admin) {
            kiosks = locationRepository.findAll().stream()
                    .filter(this::isKioskLocation)
                    .sorted(Comparator.comparing(item -> safeTrim(item.getName()), String.CASE_INSENSITIVE_ORDER))
                    .collect(Collectors.toList());
            if (kiosks.isEmpty()) {
                kiosks = locationRepository.findAll().stream()
                        .sorted(Comparator.comparing(item -> safeTrim(item.getName()), String.CASE_INSENSITIVE_ORDER))
                        .collect(Collectors.toList());
            }
        } else {
            kiosks = locationRepository.findByEncargadoIdOrderByNameAsc(user.getId()).stream()
                    .filter(this::isKioskLocation)
                    .collect(Collectors.toList());
            if (kiosks.isEmpty()) {
                kiosks = locationRepository.findByEncargadoIdOrderByNameAsc(user.getId());
            }
        }
        if (kiosks == null || kiosks.isEmpty()) {
            throw new BusinessException("Tu usuario no tiene kiosko asignado. Configura el encargado del kiosko.");
        }
        return kiosks;
    }

    private LocationEntity resolveTargetKiosk(List<LocationEntity> availableKiosks, Long kioskLocationId) throws BusinessException {
        if (availableKiosks == null || availableKiosks.isEmpty()) {
            throw new BusinessException("No hay kioskos disponibles para este usuario.");
        }
        if (kioskLocationId == null) {
            return availableKiosks.get(0);
        }
        return availableKiosks.stream()
                .filter(item -> Objects.equals(item.getId(), kioskLocationId))
                .findFirst()
                .orElseThrow(() -> new BusinessException("No tienes acceso al kiosko seleccionado."));
    }

    private boolean isKioskLocation(LocationEntity location) {
        String categoria = normalizeText(location != null ? location.getCategoria() : null);
        String name = normalizeText(location != null ? location.getName() : null);
        String code = normalizeText(location != null ? location.getCode() : null);
        return categoria.contains("KIOS")
                || name.contains("KIOS")
                || code.startsWith("K");
    }

    private String normalizeText(String value) {
        return safeTrim(value)
                .toUpperCase(Locale.ROOT)
                .replace("Á", "A")
                .replace("É", "E")
                .replace("Í", "I")
                .replace("Ó", "O")
                .replace("Ú", "U");
    }

    static boolean countsForProductionMetrics(KioskSaleEntity sale) {
        return sale != null && !Boolean.TRUE.equals(sale.getTestSale()) && !isVoidSale(sale);
    }

    static boolean countsForManagerDashboard(KioskSaleEntity sale) {
        if (sale == null || isVoidSale(sale)) {
            return false;
        }
        String status = safeTrimStatic(sale.getStatus());
        return status.isEmpty() || "COMPLETED".equalsIgnoreCase(status);
    }

    private KioskPosManagerDashboardResponse.Metric buildDashboardMetric(
            List<KioskSaleEntity> sales,
            LocalDate startDate,
            LocalDate endDate
    ) {
        BigDecimal amount = BigDecimal.ZERO;
        int count = 0;
        for (KioskSaleEntity sale : sales) {
            LocalDate saleDate = sale.getSaleDate();
            if (saleDate == null || saleDate.isBefore(startDate) || saleDate.isAfter(endDate)) {
                continue;
            }
            count += 1;
            amount = amount.add(sale.getTotalAmount() != null ? sale.getTotalAmount() : BigDecimal.ZERO);
        }
        return KioskPosManagerDashboardResponse.Metric.builder()
                .amount(amount.setScale(2, RoundingMode.HALF_UP))
                .count(count)
                .build();
    }

    private BigDecimal growthPercent(BigDecimal current, BigDecimal previous) {
        BigDecimal safeCurrent = safeAmount(current);
        BigDecimal safePrevious = safeAmount(previous);
        if (safePrevious.compareTo(BigDecimal.ZERO) <= 0) {
            return safeCurrent.compareTo(BigDecimal.ZERO) > 0 ? BigDecimal.valueOf(100) : BigDecimal.ZERO;
        }
        return safeCurrent.subtract(safePrevious)
                .multiply(BigDecimal.valueOf(100))
                .divide(safePrevious, 1, RoundingMode.HALF_UP);
    }

    private BigDecimal resolvePosOpeningCashAmount(LocationEntity kiosk) {
        if (kiosk == null || kiosk.getPosOpeningCashAmount() == null) {
            return CASH_OPENING_AMOUNT;
        }
        BigDecimal amount = kiosk.getPosOpeningCashAmount().setScale(2, RoundingMode.HALF_UP);
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return CASH_OPENING_AMOUNT;
        }
        return amount;
    }

    private boolean isPosTestSale(LocationEntity kiosk) {
        if (kiosk != null && kiosk.getPosTestMode() != null) {
            return Boolean.TRUE.equals(kiosk.getPosTestMode());
        }
        return felEmissionProperties.isTestMode();
    }

    private List<KioskSaleEntity> findSalesByDateRangeForKiosk(Long kioskId, LocalDate startDate, LocalDate endDate) {
        LocalDate[] range = normalizeSaleDateRange(startDate, endDate);
        if (range[0] != null && range[1] != null) {
            return kioskSaleRepository.findByKioskLocationIdAndSaleDateBetweenWithItemsOrderBySoldAtDesc(
                    kioskId, range[0], range[1]);
        }
        return kioskSaleRepository.findByKioskLocationIdWithItemsOrderBySoldAtDesc(kioskId);
    }

    private List<KioskSaleEntity> findSalesByDateRange(LocalDate startDate, LocalDate endDate) {
        LocalDate[] range = normalizeSaleDateRange(startDate, endDate);
        if (range[0] != null && range[1] != null) {
            return kioskSaleRepository.findBySaleDateBetweenOrderBySoldAtDesc(range[0], range[1]);
        }
        return kioskSaleRepository.findAll().stream()
                .sorted(Comparator.comparing(KioskSaleEntity::getSoldAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());
    }

    /**
     * Acepta un solo día (solo inicio o solo fin), o rango completo. Intercambia si vienen invertidas.
     */
    private LocalDate[] normalizeSaleDateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate == null && endDate == null) {
            return new LocalDate[] { null, null };
        }
        LocalDate from = startDate != null ? startDate : endDate;
        LocalDate to = endDate != null ? endDate : startDate;
        if (to.isBefore(from)) {
            return new LocalDate[] { to, from };
        }
        return new LocalDate[] { from, to };
    }

    private List<KioskSaleEntity> findSalesForReportBySaleDate(
            LocalDate from,
            LocalDate to,
            Long kioskLocationId
    ) {
        if (kioskLocationId != null) {
            return kioskSaleRepository.findByKioskLocationIdAndSaleDateBetweenOrderBySoldAtDesc(
                    kioskLocationId, from, to);
        }
        return kioskSaleRepository.findBySaleDateBetweenOrderBySoldAtDesc(from, to);
    }

    static BigDecimal resolveReportSaleAmount(KioskSaleEntity sale, String paymentKind) {
        if ("CARD".equals(paymentKind)) {
            return resolveCardAmountForReport(sale);
        }
        return sale != null && sale.getTotalAmount() != null ? sale.getTotalAmount() : BigDecimal.ZERO;
    }

    private KioskPosReportsResponse buildReportResponse(
            List<KioskSaleEntity> sales,
            LocalDate startDate,
            LocalDate endDate,
            String paymentKind
    ) {
        Set<Long> kioskIds = sales.stream()
                .map(KioskSaleEntity::getKioskLocationId)
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toSet());
        Map<Long, LocationEntity> kioskMap = locationRepository.findAllById(kioskIds).stream()
                .collect(Collectors.toMap(LocationEntity::getId, row -> row));

        Map<Long, KioskPosReportsResponse.KioskSummary> grouped = new LinkedHashMap<>();
        BigDecimal totalItems = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;

        for (KioskSaleEntity sale : sales) {
            Long kioskId = sale.getKioskLocationId();
            if (kioskId == null) {
                continue;
            }
            LocationEntity kiosk = kioskMap.get(kioskId);
            KioskPosReportsResponse.KioskSummary current = grouped.get(kioskId);
            if (current == null) {
                current = KioskPosReportsResponse.KioskSummary.builder()
                        .kioskId(kioskId)
                        .kioskCode(kiosk != null ? kiosk.getCode() : "")
                        .kioskName(kiosk != null ? kiosk.getName() : "Kiosko")
                        .salesCount(0)
                        .totalItems(BigDecimal.ZERO)
                        .totalAmount(BigDecimal.ZERO)
                        .build();
            }
            BigDecimal saleItems = sale.getTotalItems() != null ? sale.getTotalItems() : BigDecimal.ZERO;
            BigDecimal saleAmount = resolveReportSaleAmount(sale, paymentKind);
            current.setSalesCount(current.getSalesCount() + 1);
            current.setTotalItems(current.getTotalItems().add(saleItems));
            current.setTotalAmount(current.getTotalAmount().add(saleAmount));
            grouped.put(kioskId, current);

            totalItems = totalItems.add(saleItems);
            totalAmount = totalAmount.add(saleAmount);
        }

        List<KioskPosReportsResponse.KioskSummary> kioskSummaries = grouped.values().stream()
                .sorted(Comparator.comparing(KioskPosReportsResponse.KioskSummary::getKioskName, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());

        BigDecimal averageTicket = BigDecimal.ZERO;
        if (!sales.isEmpty()) {
            averageTicket = totalAmount.divide(BigDecimal.valueOf(sales.size()), 2, RoundingMode.HALF_UP);
        }

        return KioskPosReportsResponse.builder()
                .startDate(startDate)
                .endDate(endDate)
                .salesCount(sales.size())
                .totalItems(totalItems)
                .totalAmount(totalAmount)
                .averageTicket(averageTicket)
                .kiosks(kioskSummaries)
                .build();
    }

    private KioskPosSaleResponse toSaleResponse(KioskSaleEntity sale, LocationEntity kiosk, UserEntity user) {
        Map<Long, String> categoryNameByProductId = resolveSaleItemCategoryNames(sale);
        List<KioskPosSaleResponse.Item> items = sale.getItems() == null
                ? List.of()
                : sale.getItems().stream().map(row -> KioskPosSaleResponse.Item.builder()
                        .id(row.getId())
                        .productId(row.getProductId())
                        .productCode(row.getProductCode())
                        .productName(row.getProductName())
                        .colorId(row.getColorId())
                        .colorName(row.getColorName())
                        .categoryName(categoryNameByProductId.get(row.getProductId()))
                        .quantity(row.getQuantity())
                        .unitPrice(row.getUnitPrice())
                        .lineTotal(row.getLineTotal())
                        .build())
                .collect(Collectors.toList());

        UserEntity depositRecordedBy = sale.getDepositRecordedBy() != null
                ? userRepository.findById(sale.getDepositRecordedBy()).orElse(null)
                : null;

        UserEntity soldByUser = resolveSaleSoldByUser(sale, user);

        KioskPosSaleResponse.InvoiceInfo invoiceInfo = buildInvoiceInfo(sale);

        BigDecimal disbursementsTotal = safeAmount(
                sale.getId() != null ? kioskCashExpenseRepository.sumAmountByKioskSaleId(sale.getId()) : BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal cashAmountForDeposit = resolveCashAmountForDeposit(sale).setScale(2, RoundingMode.HALF_UP);
        BigDecimal netDepositAmount = resolveNetDepositAmount(sale, disbursementsTotal);
        List<KioskPosSaleResponse.SaleDisbursement> disbursements = buildSaleDisbursementResponses(sale.getId());

        return KioskPosSaleResponse.builder()
                .id(sale.getId())
                .saleNumber(sale.getSaleNumber())
                .saleDate(sale.getSaleDate())
                .soldAt(sale.getSoldAt())
                .kioskId(kiosk.getId())
                .kioskCode(kiosk.getCode())
                .kioskName(kiosk.getName())
                .soldByUserId(soldByUser.getId())
                .soldByUsername(soldByUser.getUsername())
                .soldByName(buildUserFullName(soldByUser))
                .customerTaxId(sale.getCustomerTaxId())
                .customerName(sale.getCustomerName())
                .address(sale.getAddress())
                .phone(sale.getPhone())
                .email(sale.getEmail())
                .paymentMethod(sale.getPaymentMethod())
                .status(sale.getStatus())
                .cashSessionId(sale.getCashSessionId())
                .testSale(Boolean.TRUE.equals(sale.getTestSale()))
                .totalItems(sale.getTotalItems())
                .discountAmount(sale.getDiscountAmount())
                .subtotal(sale.getSubtotal())
                .totalAmount(sale.getTotalAmount())
                .amountReceived(sale.getAmountReceived())
                .changeAmount(sale.getChangeAmount())
                .cashAmount(sale.getCashAmount())
                .cardAmount(sale.getCardAmount())
                .cardAuthNumber(sale.getCardAuthNumber())
                .cardLast4(sale.getCardLast4())
                .cardBrand(sale.getCardBrand())
                .cardVoucherAmount(sale.getCardVoucherAmount())
                .card2Amount(sale.getCard2Amount())
                .card2AuthNumber(sale.getCard2AuthNumber())
                .card2Last4(sale.getCard2Last4())
                .card2Brand(sale.getCard2Brand())
                .card2VoucherAmount(sale.getCard2VoucherAmount())
                .cardVoucherDifference(voucherDifference(sale.getCardVoucherAmount(), sale.getCardAmount()))
                .card2VoucherDifference(voucherDifference(sale.getCard2VoucherAmount(), sale.getCard2Amount()))
                .notes(sale.getNotes())
                .comments(sale.getComments())
                .promotionId(sale.getPromotionId())
                .promotionName(sale.getPromotionName())
                .felStatus(sale.getFelStatus())
                .felUuid(sale.getFelUuid())
                .felSerie(sale.getFelSerie())
                .felNumero(sale.getFelNumero())
                .felError(sale.getFelError())
                .felCertifiedAt(sale.getFelCertifiedAt())
                .internalNumber(invoiceInfo != null ? invoiceInfo.getInternalNumber() : null)
                .invoice(invoiceInfo)
                .depositSlipNumber(sale.getDepositSlipNumber())
                .depositRecordedAt(sale.getDepositRecordedAt())
                .depositRecordedByUserId(sale.getDepositRecordedBy())
                .depositRecordedByName(depositRecordedBy != null ? buildUserFullName(depositRecordedBy) : null)
                .pendingDeposit(isPendingDeposit(sale, disbursementsTotal))
                .cashAmountForDeposit(cashAmountForDeposit)
                .disbursementsTotal(disbursementsTotal)
                .netDepositAmount(netDepositAmount)
                .disbursements(disbursements)
                .items(items)
                .build();
    }

    private List<KioskPosSaleResponse.SaleDisbursement> buildSaleDisbursementResponses(Long saleId) {
        if (saleId == null) {
            return List.of();
        }
        return kioskCashExpenseRepository.findByKioskSaleIdOrderByCreatedAtAscIdAsc(saleId).stream()
                .map(expense -> {
                    UserEntity createdBy = expense.getCreatedByUserId() != null
                            ? userRepository.findById(expense.getCreatedByUserId()).orElse(null)
                            : null;
                    return KioskPosSaleResponse.SaleDisbursement.builder()
                            .id(expense.getId())
                            .amount(expense.getAmount())
                            .description(expense.getDescription())
                            .createdAt(expense.getCreatedAt())
                            .createdByName(createdBy != null ? buildUserFullName(createdBy) : null)
                            .build();
                })
                .collect(Collectors.toList());
    }

    private KioskSaleEntity validateLinkedSaleForExpense(KioskCashSessionEntity session, Long kioskSaleId)
            throws ResourceNotFoundException, BusinessException {
        KioskSaleEntity sale = kioskSaleRepository.findById(kioskSaleId)
                .orElseThrow(() -> new ResourceNotFoundException("KioskSale", kioskSaleId));
        if (!Objects.equals(sale.getCashSessionId(), session.getId())) {
            throw new BusinessException("La venta no pertenece a la sesión de caja abierta.");
        }
        if (isVoidSale(sale)) {
            throw new BusinessException("No puedes desembolsar de una venta anulada.");
        }
        if (!"COMPLETED".equalsIgnoreCase(safeTrim(sale.getStatus()))) {
            throw new BusinessException("Solo puedes desembolsar de ventas completadas.");
        }
        if (resolveCashAmountForDeposit(sale).compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("La venta no tiene efectivo sujeto a depósito.");
        }
        return sale;
    }

    private Map<Long, BigDecimal> loadDisbursementTotalsBySaleIds(java.util.Collection<Long> saleIds) {
        if (saleIds == null || saleIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, BigDecimal> totals = new HashMap<>();
        for (Object[] row : kioskCashExpenseRepository.sumAmountByKioskSaleIds(saleIds)) {
            if (row == null || row.length < 2 || row[0] == null) {
                continue;
            }
            totals.put((Long) row[0], safeAmount((BigDecimal) row[1]).setScale(2, RoundingMode.HALF_UP));
        }
        return totals;
    }

    private Map<Long, String> resolveSaleItemCategoryNames(KioskSaleEntity sale) {
        if (sale.getItems() == null || sale.getItems().isEmpty()) {
            return Map.of();
        }
        Set<Long> productIds = sale.getItems().stream()
                .map(KioskSaleItemEntity::getProductId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (productIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, ProductEntity> productsById = productRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(ProductEntity::getId, item -> item, (a, b) -> a));
        Set<Long> categoryIds = productsById.values().stream()
                .map(ProductEntity::getCategoryId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> categoryNames = categoryIds.isEmpty()
                ? Map.of()
                : productCategoryRepository.findAllById(categoryIds).stream()
                        .collect(Collectors.toMap(ProductCategoryEntity::getId, ProductCategoryEntity::getName, (a, b) -> a));
        Map<Long, String> result = new LinkedHashMap<>();
        for (Long productId : productIds) {
            ProductEntity product = productsById.get(productId);
            if (product == null || product.getCategoryId() == null) {
                continue;
            }
            String name = categoryNames.get(product.getCategoryId());
            if (name != null && !name.isBlank()) {
                result.put(productId, name);
            }
        }
        return result;
    }

    private KioskPosSaleResponse.InvoiceInfo buildInvoiceInfo(KioskSaleEntity sale) {
        if (sale.getInvoiceId() == null && (sale.getFelStatus() == null || sale.getFelStatus().isBlank())) {
            return null;
        }
        return KioskPosSaleResponse.InvoiceInfo.builder()
                .id(sale.getInvoiceId())
                .status(sale.getFelStatus())
                .internalNumber(taxInvoiceService.getInternalNumber(sale.getInvoiceId()))
                .felUuid(sale.getFelUuid())
                .felSerie(sale.getFelSerie())
                .felNumero(sale.getFelNumero())
                .felError(sale.getFelError())
                .felCertifiedAt(sale.getFelCertifiedAt())
                .hasCertifiedXml(taxInvoiceService.hasStoredCertifiedXml(sale.getInvoiceId()))
                .build();
    }

    private KioskPromotionResponse toPromotionResponse(KioskPromotionEntity entity) {
        return KioskPromotionResponse.builder()
                .id(entity.getId())
                .name(entity.getName())
                .description(entity.getDescription())
                .discountType(entity.getDiscountType())
                .discountValue(entity.getDiscountValue())
                .comboBuyQty(entity.getComboBuyQty())
                .comboPayQty(entity.getComboPayQty())
                .kioskLocationId(entity.getKioskLocationId())
                .audienceCategory(ProductAudienceCategory.normalizePromotionAudience(entity.getAudienceCategory()))
                .startDate(entity.getStartDate())
                .endDate(entity.getEndDate())
                .active(entity.getActive())
                .tiers(toPromotionTierResponses(entity))
                .build();
    }

    private List<KioskPromotionTierResponse> toPromotionTierResponses(KioskPromotionEntity entity) {
        List<KioskPromotionTierEntity> tiers = entity.getTiers();
        if ((tiers == null || tiers.isEmpty()) && entity.getId() != null) {
            tiers = kioskPromotionTierRepository.findByPromotionIdOrderByAudienceCategoryAsc(entity.getId());
        }
        if (tiers == null || tiers.isEmpty()) {
            return List.of();
        }
        Map<Long, String> categoryNames = productCategoryRepository.findAll().stream()
                .collect(Collectors.toMap(ProductCategoryEntity::getId, ProductCategoryEntity::getName, (a, b) -> a));
        return tiers.stream()
                .map(tier -> KioskPromotionTierResponse.builder()
                        .audienceCategory(ProductAudienceCategory.normalizeTierAudience(tier.getAudienceCategory()))
                        .categoryId(tier.getCategoryId())
                        .categoryName(categoryNames.get(tier.getCategoryId()))
                        .discountValue(tier.getDiscountValue())
                        .build())
                .collect(Collectors.toList());
    }

    private KioskPromotionEntity resolvePromotionIfAny(Long promotionId, LocalDate saleDate, Long kioskId)
            throws BusinessException {
        if (promotionId == null) {
            return null;
        }
        KioskPromotionEntity promotion = kioskPromotionRepository.findByIdAndActiveTrue(promotionId)
                .orElseThrow(() -> new BusinessException("La promoción seleccionada no existe o está inactiva."));
        if (promotion.getKioskLocationId() != null && !Objects.equals(promotion.getKioskLocationId(), kioskId)) {
            throw new BusinessException("La promoción no aplica a este kiosko.");
        }
        LocalDate startDate = promotion.getStartDate();
        LocalDate endDate = promotion.getEndDate();
        if (startDate != null && saleDate.isBefore(startDate)) {
            throw new BusinessException("La promoción todavía no está vigente.");
        }
        if (endDate != null && saleDate.isAfter(endDate)) {
            throw new BusinessException("La promoción ya expiró.");
        }
        return promotion;
    }

    private void validatePromotionRequest(KioskPromotionRequest request) throws BusinessException {
        if (request == null) {
            throw new BusinessException("Debes enviar la promoción.");
        }
        if (safeTrim(request.getName()).isBlank()) {
            throw new BusinessException("El nombre de la promoción es obligatorio.");
        }
        String discountType = normalizeDiscountType(request.getDiscountType());
        if ("COMBO".equals(discountType)) {
            Integer buy = request.getComboBuyQty();
            Integer pay = request.getComboPayQty();
            if (buy == null || pay == null || buy <= 0 || pay <= 0 || pay >= buy) {
                throw new BusinessException("Promoción COMBO: indique cantidades válidas (ej. lleva 2 paga 1).");
            }
            validatePromotionDates(request);
            return;
        }
        if ("TIERED_PERCENT".equals(discountType)) {
            validateTieredPromotion(request);
            validatePromotionDates(request);
            return;
        }
        if (request.getDiscountValue() == null || request.getDiscountValue().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("El valor del descuento debe ser mayor a cero.");
        }
        if ("PERCENT".equals(discountType) && request.getDiscountValue().compareTo(new BigDecimal("100")) > 0) {
            throw new BusinessException("El descuento porcentual no puede ser mayor a 100.");
        }
        validatePromotionDates(request);
        String audience = ProductAudienceCategory.normalizePromotionAudience(request.getAudienceCategory());
        if (request.getAudienceCategory() != null && !request.getAudienceCategory().isBlank() && audience == null) {
            throw new BusinessException("Línea de promoción inválida. Use DAMA o CABALLERO.");
        }
    }

    private void validatePromotionDates(KioskPromotionRequest request) throws BusinessException {
        if (request.getStartDate() != null && request.getEndDate() != null
                && request.getEndDate().isBefore(request.getStartDate())) {
            throw new BusinessException("La fecha fin no puede ser menor a la fecha inicio.");
        }
    }

    private void validateTieredPromotion(KioskPromotionRequest request) throws BusinessException {
        List<KioskPromotionTierRequest> tiers = request.getTiers();
        if (tiers == null || tiers.isEmpty()) {
            throw new BusinessException("Debe indicar al menos un porcentaje por línea y categoría.");
        }
        Set<String> tierKeys = new HashSet<>();
        boolean hasPositive = false;
        for (KioskPromotionTierRequest tier : tiers) {
            if (tier == null) {
                continue;
            }
            String audience = ProductAudienceCategory.normalizeTierAudience(tier.getAudienceCategory());
            if (audience == null) {
                throw new BusinessException("Línea de promoción inválida. Use DAMA, CABALLERO o UNISEX.");
            }
            if (tier.getCategoryId() == null) {
                throw new BusinessException("Debe indicar la categoría de producto para cada tier.");
            }
            if (!productCategoryRepository.existsById(tier.getCategoryId())) {
                throw new BusinessException("La categoría seleccionada no existe.");
            }
            String tierKey = audience + ":" + tier.getCategoryId();
            if (!tierKeys.add(tierKey)) {
                throw new BusinessException("No puede repetir la misma audiencia y categoría en una promoción.");
            }
            BigDecimal value = tier.getDiscountValue() != null ? tier.getDiscountValue() : BigDecimal.ZERO;
            if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(new BigDecimal("100")) > 0) {
                throw new BusinessException("Cada porcentaje por línea debe estar entre 0 y 100.");
            }
            if (value.compareTo(BigDecimal.ZERO) > 0) {
                hasPositive = true;
            }
        }
        if (!hasPositive) {
            throw new BusinessException("Debe indicar al menos un porcentaje mayor a cero.");
        }
    }

    private String normalizeDiscountType(String value) {
        String normalized = normalizeText(value);
        if (normalized.contains("TIERED")) {
            return "TIERED_PERCENT";
        }
        if (normalized.contains("COMBO")) {
            return "COMBO";
        }
        if (normalized.contains("PERCENT")) {
            return "PERCENT";
        }
        return "FIXED";
    }

    private String normalizePaymentMethod(String value) throws BusinessException {
        String normalized = normalizeText(value);
        if (normalized.isBlank() || normalized.contains("EFECTIVO") || "CASH".equals(normalized)) {
            return "EFECTIVO";
        }
        if (normalized.contains("TARJETA") || normalized.contains("CARD")) {
            return "TARJETA";
        }
        if (normalized.contains("TRANSFER")) {
            throw new BusinessException("Transferencia ya no está disponible en el POS. Use EFECTIVO, TARJETA o MIXTO.");
        }
        if (normalized.contains("MIXTO") || normalized.contains("MIXED")) {
            return "MIXTO";
        }
        throw new BusinessException("Forma de pago no válida. Use EFECTIVO, TARJETA o MIXTO.");
    }

    private String normalizeTaxId(String value) {
        String raw = safeTrim(value).toUpperCase(Locale.ROOT);
        if (raw.isBlank()) {
            return "CF";
        }
        if ("CF".equals(raw) || "C/F".equals(raw)) {
            return "CF";
        }
        return raw.replace(" ", "").replace("-", "");
    }

    private boolean isValidGuatemalaNit(String nit) {
        if (nit == null) {
            return false;
        }
        String normalized = nit.toUpperCase(Locale.ROOT).replace("-", "").trim();
        if (normalized.length() < 2) {
            return false;
        }
        String body = normalized.substring(0, normalized.length() - 1);
        char verifier = normalized.charAt(normalized.length() - 1);
        if (!body.chars().allMatch(Character::isDigit)) {
            return false;
        }
        int factor = body.length() + 1;
        int total = 0;
        for (char c : body.toCharArray()) {
            total += Character.getNumericValue(c) * factor;
            factor--;
        }
        int modulus = (11 - (total % 11)) % 11;
        char expected = (modulus == 10) ? 'K' : Character.forDigit(modulus, 10);
        return verifier == expected;
    }

    @Transactional(readOnly = true)
    public KioskCashSessionResponse getCurrentCashSession(Long kioskLocationId) throws BusinessException {
        UserEntity user = getCurrentUserOrThrow();
        boolean admin = KioskAccessHelper.hasAllKiosksAccess(user);
        List<LocationEntity> availableKiosks = resolveAvailableKiosks(user, admin);
        LocationEntity kiosk = resolveTargetKiosk(availableKiosks, kioskLocationId);
        return kioskCashSessionRepository
                .findFirstByKioskLocationIdAndStatusOrderByOpenedAtDesc(kiosk.getId(), CASH_SESSION_OPEN)
                .map(session -> toCashSessionResponse(session, kiosk))
                .orElse(null);
    }

    public KioskCashSessionResponse openCashSession(KioskCashSessionOpenRequest request) throws BusinessException {
        UserEntity user = getCurrentUserOrThrow();
        boolean admin = KioskAccessHelper.hasAllKiosksAccess(user);
        List<LocationEntity> availableKiosks = resolveAvailableKiosks(user, admin);
        LocationEntity kiosk = resolveTargetKiosk(availableKiosks, request != null ? request.getKioskLocationId() : null);

        if (kioskCashSessionRepository
                .findFirstByKioskLocationIdAndStatusOrderByOpenedAtDesc(kiosk.getId(), CASH_SESSION_OPEN)
                .isPresent()) {
            throw new BusinessException("Ya hay una caja abierta en este kiosko.");
        }

        KioskCashSessionEntity session = KioskCashSessionEntity.builder()
                .kioskLocationId(kiosk.getId())
                .openedByUserId(user.getId())
                .openedAt(GuatemalaDateTime.now())
                .openingAmount(resolvePosOpeningCashAmount(kiosk))
                .status(CASH_SESSION_OPEN)
                .build();
        KioskCashSessionEntity saved = kioskCashSessionRepository.save(session);
        return toCashSessionResponse(saved, kiosk);
    }

    public KioskCashSessionResponse closeCashSession(Long sessionId, KioskCashSessionCloseRequest request)
            throws BusinessException, ResourceNotFoundException {
        UserEntity user = getCurrentUserOrThrow();
        KioskCashSessionEntity session = kioskCashSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("KioskCashSession", sessionId));

        if (!CASH_SESSION_OPEN.equalsIgnoreCase(safeTrim(session.getStatus()))) {
            throw new BusinessException("La caja ya está cerrada.");
        }

        boolean admin = KioskAccessHelper.hasAllKiosksAccess(user);
        List<LocationEntity> availableKiosks = resolveAvailableKiosks(user, admin);
        resolveTargetKiosk(availableKiosks, session.getKioskLocationId());

        if (request == null || request.getCountedCash() == null) {
            throw new BusinessException("Debes ingresar el efectivo contado en caja.");
        }

        List<KioskSaleEntity> sessionSales = kioskSaleRepository.findByCashSessionIdOrderBySoldAtAsc(sessionId);
        BigDecimal expectedCash = calculateExpectedCash(session, sessionSales);
        BigDecimal countedCash = request.getCountedCash().setScale(2, RoundingMode.HALF_UP);
        BigDecimal variance = countedCash.subtract(expectedCash).setScale(2, RoundingMode.HALF_UP);

        session.setClosedAt(GuatemalaDateTime.now());
        session.setClosedByUserId(user.getId());
        session.setCountedCash(countedCash);
        session.setExpectedCash(expectedCash);
        session.setVariance(variance);
        session.setCloseNotes(safeTrim(request.getNotes()));
        session.setStatus(CASH_SESSION_CLOSED);
        KioskCashSessionEntity saved = kioskCashSessionRepository.save(session);

        LocationEntity kiosk = locationRepository.findById(session.getKioskLocationId()).orElse(null);
        return toCashSessionResponse(saved, kiosk);
    }

    private KioskCashSessionEntity requireOpenCashSession(Long kioskLocationId) throws BusinessException {
        return kioskCashSessionRepository
                .findFirstByKioskLocationIdAndStatusOrderByOpenedAtDesc(kioskLocationId, CASH_SESSION_OPEN)
                .orElseThrow(() -> new BusinessException("Debes abrir caja antes de vender."));
    }

    private BigDecimal calculateExpectedCash(KioskCashSessionEntity session, List<KioskSaleEntity> sales) {
        BigDecimal expected = session.getOpeningAmount() != null
                ? session.getOpeningAmount()
                : CASH_OPENING_AMOUNT;
        for (KioskSaleEntity sale : sales) {
            if (sale == null || isVoidSale(sale)) {
                continue;
            }
            String method = safeTrim(sale.getPaymentMethod()).toUpperCase(Locale.ROOT);
            if ("EFECTIVO".equals(method)) {
                expected = expected.add(safeAmount(sale.getTotalAmount()));
            } else if ("MIXTO".equals(method)) {
                expected = expected.add(safeAmount(sale.getCashAmount()));
            }
        }
        BigDecimal expenses = kioskCashExpenseRepository.sumAmountByCashSessionId(session.getId());
        expected = expected.subtract(safeAmount(expenses));
        return expected.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal sumCashExpenses(Long sessionId) {
        return safeAmount(kioskCashExpenseRepository.sumAmountByCashSessionId(sessionId))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private KioskCashSessionResponse toCashSessionResponse(KioskCashSessionEntity session, LocationEntity kiosk) {
        List<KioskSaleEntity> sales = kioskSaleRepository.findByCashSessionIdOrderBySoldAtAsc(session.getId());
        int salesCount = 0;
        BigDecimal cashTotal = BigDecimal.ZERO;
        BigDecimal cardTotal = BigDecimal.ZERO;
        for (KioskSaleEntity sale : sales) {
            if (isVoidSale(sale)) {
                continue;
            }
            salesCount++;
            String method = safeTrim(sale.getPaymentMethod()).toUpperCase(Locale.ROOT);
            if ("EFECTIVO".equals(method)) {
                cashTotal = cashTotal.add(safeAmount(sale.getTotalAmount()));
            } else if ("TARJETA".equals(method)) {
                cardTotal = cardTotal.add(safeAmount(sale.getTotalAmount()));
            } else if ("MIXTO".equals(method)) {
                cashTotal = cashTotal.add(safeAmount(sale.getCashAmount()));
                cardTotal = cardTotal.add(safeAmount(sale.getCardAmount()));
            } else if ("TRANSFERENCIA".equals(method)) {
                cardTotal = cardTotal.add(safeAmount(sale.getTotalAmount()));
            }
        }

        UserEntity openedBy = session.getOpenedByUserId() != null
                ? userRepository.findById(session.getOpenedByUserId()).orElse(null)
                : null;
        UserEntity closedBy = session.getClosedByUserId() != null
                ? userRepository.findById(session.getClosedByUserId()).orElse(null)
                : null;

        BigDecimal expectedCash = CASH_SESSION_OPEN.equalsIgnoreCase(safeTrim(session.getStatus()))
                ? calculateExpectedCash(session, sales)
                : session.getExpectedCash();
        BigDecimal cashExpensesTotal = sumCashExpenses(session.getId());
        List<KioskCashExpenseResponse> expenseRows = kioskCashExpenseRepository
                .findByCashSessionIdOrderByCreatedAtAscIdAsc(session.getId())
                .stream()
                .map(this::toCashExpenseResponse)
                .collect(Collectors.toList());

        return KioskCashSessionResponse.builder()
                .id(session.getId())
                .kioskLocationId(session.getKioskLocationId())
                .kioskCode(kiosk != null ? kiosk.getCode() : null)
                .kioskName(kiosk != null ? kiosk.getName() : null)
                .openedByUserId(session.getOpenedByUserId())
                .openedByName(openedBy != null ? buildUserFullName(openedBy) : null)
                .openedAt(session.getOpenedAt())
                .openingAmount(session.getOpeningAmount())
                .closedAt(session.getClosedAt())
                .closedByUserId(session.getClosedByUserId())
                .closedByName(closedBy != null ? buildUserFullName(closedBy) : null)
                .countedCash(session.getCountedCash())
                .expectedCash(expectedCash)
                .variance(session.getVariance())
                .closeNotes(session.getCloseNotes())
                .status(session.getStatus())
                .salesCount(salesCount)
                .cashSalesTotal(cashTotal.setScale(2, RoundingMode.HALF_UP))
                .cardSalesTotal(cardTotal.setScale(2, RoundingMode.HALF_UP))
                .cashExpensesTotal(cashExpensesTotal)
                .expenses(expenseRows)
                .build();
    }

    public KioskCashExpenseResponse addCashExpense(Long sessionId, KioskCashExpenseRequest request)
            throws BusinessException, ResourceNotFoundException {
        if (request == null || request.getAmount() == null) {
            throw new BusinessException("Debes indicar el monto del gasto.");
        }
        String description = safeTrim(request.getDescription());
        if (description.isBlank()) {
            throw new BusinessException("Debes indicar la descripción del gasto.");
        }
        UserEntity user = getCurrentUserOrThrow();
        KioskCashSessionEntity session = kioskCashSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("KioskCashSession", sessionId));
        if (!CASH_SESSION_OPEN.equalsIgnoreCase(safeTrim(session.getStatus()))) {
            throw new BusinessException("Solo puedes registrar gastos mientras la caja esté abierta.");
        }
        boolean admin = KioskAccessHelper.hasAllKiosksAccess(user);
        resolveTargetKiosk(resolveAvailableKiosks(user, admin), session.getKioskLocationId());

        BigDecimal amount = request.getAmount().setScale(2, RoundingMode.HALF_UP);
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("El monto del gasto debe ser mayor a cero.");
        }

        Long kioskSaleId = request.getKioskSaleId();
        if (kioskSaleId != null) {
            KioskSaleEntity linkedSale = validateLinkedSaleForExpense(session, kioskSaleId);
            BigDecimal existing = safeAmount(kioskCashExpenseRepository.sumAmountByKioskSaleId(kioskSaleId));
            BigDecimal gross = resolveCashAmountForDeposit(linkedSale);
            if (existing.add(amount).compareTo(gross) > 0) {
                throw new BusinessException("El desembolso supera el efectivo disponible de la venta.");
            }
        }

        KioskCashExpenseEntity saved = kioskCashExpenseRepository.save(
                KioskCashExpenseEntity.builder()
                        .cashSessionId(session.getId())
                        .kioskSaleId(kioskSaleId)
                        .amount(amount)
                        .description(description)
                        .createdByUserId(user.getId())
                        .build()
        );
        return toCashExpenseResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<KioskCashExpenseResponse> listCashExpenses(Long sessionId) throws BusinessException, ResourceNotFoundException {
        UserEntity user = getCurrentUserOrThrow();
        KioskCashSessionEntity session = kioskCashSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("KioskCashSession", sessionId));
        boolean admin = KioskAccessHelper.hasAllKiosksAccess(user);
        resolveTargetKiosk(resolveAvailableKiosks(user, admin), session.getKioskLocationId());
        return kioskCashExpenseRepository.findByCashSessionIdOrderByCreatedAtAscIdAsc(session.getId())
                .stream()
                .map(this::toCashExpenseResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<KioskCashSessionDailySummaryResponse> getCashSessionDailySummaries(
            Long kioskLocationId,
            LocalDate startDate,
            LocalDate endDate
    ) throws BusinessException {
        UserEntity user = getCurrentUserOrThrow();
        boolean admin = KioskAccessHelper.hasAllKiosksAccess(user);
        LocationEntity kiosk = resolveTargetKiosk(resolveAvailableKiosks(user, admin), kioskLocationId);
        LocalDate start = startDate != null ? startDate : GuatemalaDateTime.today();
        LocalDate end = endDate != null ? endDate : start;
        if (end.isBefore(start)) {
            throw new BusinessException("La fecha final no puede ser anterior a la inicial.");
        }
        LocalDateTime startAt = start.atStartOfDay();
        LocalDateTime endAt = end.plusDays(1).atStartOfDay();
        List<KioskCashSessionEntity> sessions = kioskCashSessionRepository
                .findByKioskLocationIdAndOpenedAtBetween(kiosk.getId(), startAt, endAt);
        return sessions.stream()
                .map(session -> {
                    List<KioskSaleEntity> sales = kioskSaleRepository.findByCashSessionIdOrderBySoldAtAsc(session.getId());
                    BigDecimal cashSales = sumCashSales(sales);
                    BigDecimal expenses = sumCashExpenses(session.getId());
                    BigDecimal expected = CASH_SESSION_OPEN.equalsIgnoreCase(safeTrim(session.getStatus()))
                            ? calculateExpectedCash(session, sales)
                            : session.getExpectedCash();
                    return KioskCashSessionDailySummaryResponse.builder()
                            .workDate(session.getOpenedAt() != null
                                    ? session.getOpenedAt().toLocalDate()
                                    : start)
                            .sessionId(session.getId())
                            .sessionStatus(session.getStatus())
                            .openingAmount(session.getOpeningAmount())
                            .cashSalesTotal(cashSales)
                            .cashExpensesTotal(expenses)
                            .expectedCash(expected)
                            .countedCash(session.getCountedCash())
                            .variance(session.getVariance())
                            .build();
                })
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public KioskCashCloseReportResponse getCashCloseReport(Long sessionId)
            throws BusinessException, ResourceNotFoundException {
        UserEntity user = getCurrentUserOrThrow();
        KioskCashSessionEntity session = kioskCashSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("KioskCashSession", sessionId));
        if (!CASH_SESSION_CLOSED.equalsIgnoreCase(safeTrim(session.getStatus()))) {
            throw new BusinessException("El reporte de cierre solo está disponible para cajas cerradas.");
        }
        boolean globalReports = KioskAccessHelper.hasKioskReportsAccess(user);
        LocationEntity kiosk = resolveTargetKiosk(resolveAvailableKiosks(user, globalReports), session.getKioskLocationId());
        return buildCashCloseReport(session, kiosk, user);
    }

    @Transactional(readOnly = true)
    public List<KioskCashSessionHistoryItemResponse> getCashSessionHistory(
            Long kioskLocationId,
            LocalDate startDate,
            LocalDate endDate
    ) throws BusinessException {
        UserEntity user = getCurrentUserOrThrow();
        boolean globalReports = KioskAccessHelper.hasKioskReportsAccess(user);
        List<LocationEntity> available = resolveAvailableKiosks(user, globalReports);
        List<Long> kioskIds;
        if (kioskLocationId != null) {
            LocationEntity target = resolveTargetKiosk(available, kioskLocationId);
            kioskIds = List.of(target.getId());
        } else {
            kioskIds = available.stream().map(LocationEntity::getId).filter(Objects::nonNull).toList();
        }
        if (kioskIds.isEmpty()) {
            return List.of();
        }
        LocalDate start = startDate != null ? startDate : GuatemalaDateTime.today().withDayOfMonth(1);
        LocalDate end = endDate != null ? endDate : GuatemalaDateTime.today();
        if (end.isBefore(start)) {
            throw new BusinessException("La fecha final no puede ser anterior a la inicial.");
        }
        LocalDateTime startAt = start.atStartOfDay();
        LocalDateTime endAt = end.plusDays(1).atStartOfDay();
        List<KioskCashSessionEntity> sessions = kioskCashSessionRepository.findClosedSessionsForHistory(
                CASH_SESSION_CLOSED, startAt, endAt, kioskIds);
        Map<Long, LocationEntity> kiosksById = available.stream()
                .filter(k -> k.getId() != null)
                .collect(Collectors.toMap(LocationEntity::getId, k -> k, (a, b) -> a));
        return sessions.stream()
                .map(session -> toCashSessionHistoryItem(session, kiosksById.get(session.getKioskLocationId())))
                .collect(Collectors.toList());
    }

    private KioskCashCloseReportResponse buildCashCloseReport(
            KioskCashSessionEntity session,
            LocationEntity kiosk,
            UserEntity generatedBy
    ) {
        List<KioskSaleEntity> sales = kioskSaleRepository.findByCashSessionIdOrderBySoldAtAsc(session.getId());
        List<KioskCashExpenseEntity> expenses = kioskCashExpenseRepository
                .findByCashSessionIdOrderByCreatedAtAscIdAsc(session.getId());

        BigDecimal cashTotal = BigDecimal.ZERO;
        BigDecimal cardTotal = BigDecimal.ZERO;
        BigDecimal salesSubtotal = BigDecimal.ZERO;
        BigDecimal cardVoucherTotal = BigDecimal.ZERO;
        BigDecimal cardInvoiceCardTotal = BigDecimal.ZERO;
        BigDecimal cardVoucherDifferencesTotal = BigDecimal.ZERO;
        List<KioskCashCloseReportResponse.SaleLine> saleLines = new ArrayList<>();
        List<String> depositSlips = new ArrayList<>();

        for (KioskSaleEntity sale : sales) {
            if (isVoidSale(sale)) {
                continue;
            }
            BigDecimal amount = safeAmount(sale.getTotalAmount()).setScale(2, RoundingMode.HALF_UP);
            salesSubtotal = salesSubtotal.add(amount);
            String method = safeTrim(sale.getPaymentMethod()).toUpperCase(Locale.ROOT);
            if ("EFECTIVO".equals(method)) {
                cashTotal = cashTotal.add(amount);
            } else if ("TARJETA".equals(method) || "TRANSFERENCIA".equals(method)) {
                cardTotal = cardTotal.add(amount);
            } else if ("MIXTO".equals(method)) {
                cashTotal = cashTotal.add(safeAmount(sale.getCashAmount()));
                cardTotal = cardTotal.add(safeAmount(sale.getCardAmount()));
            } else {
                cashTotal = cashTotal.add(amount);
            }
            if (sale.getDepositSlipNumber() != null && !sale.getDepositSlipNumber().isBlank()) {
                depositSlips.add(sale.getDepositSlipNumber().trim());
            }

            BigDecimal cardInvoice = resolveCardInvoiceAmountForClose(sale, method);
            BigDecimal cardVoucher = resolveCardVoucherAmountForClose(sale, method);
            BigDecimal cardDiff = voucherDifference(cardVoucher, cardInvoice);
            BigDecimal card2Invoice = isSplitCardPayment(sale.getCard2Amount())
                    ? safeAmount(sale.getCard2Amount()).setScale(2, RoundingMode.HALF_UP)
                    : null;
            BigDecimal card2Voucher = isSplitCardPayment(sale.getCard2Amount())
                    ? (sale.getCard2VoucherAmount() != null
                            ? sale.getCard2VoucherAmount().setScale(2, RoundingMode.HALF_UP)
                            : card2Invoice)
                    : null;
            BigDecimal card2Diff = voucherDifference(card2Voucher, card2Invoice);
            if (cardInvoice != null) {
                cardInvoiceCardTotal = cardInvoiceCardTotal.add(cardInvoice);
            }
            if (card2Invoice != null) {
                cardInvoiceCardTotal = cardInvoiceCardTotal.add(card2Invoice);
            }
            if (cardVoucher != null) {
                cardVoucherTotal = cardVoucherTotal.add(cardVoucher);
            }
            if (card2Voucher != null) {
                cardVoucherTotal = cardVoucherTotal.add(card2Voucher);
            }
            if (cardDiff != null) {
                cardVoucherDifferencesTotal = cardVoucherDifferencesTotal.add(cardDiff);
            }
            if (card2Diff != null) {
                cardVoucherDifferencesTotal = cardVoucherDifferencesTotal.add(card2Diff);
            }

            saleLines.add(KioskCashCloseReportResponse.SaleLine.builder()
                    .saleId(sale.getId())
                    .saleNumber(sale.getSaleNumber())
                    .invoiceNumber(resolveSaleInvoiceNumber(sale))
                    .paymentMethod(method)
                    .paymentLabel(buildPaymentLabel(sale, method))
                    .paymentKind(resolvePaymentKind(method))
                    .amount(amount)
                    .soldAt(sale.getSoldAt())
                    .cardInvoiceAmount(cardInvoice)
                    .cardVoucherAmount(cardVoucher)
                    .cardVoucherDifference(cardDiff)
                    .card2InvoiceAmount(card2Invoice)
                    .card2VoucherAmount(card2Voucher)
                    .card2VoucherDifference(card2Diff)
                    .voucherDifferenceNote(buildVoucherDifferenceNote(sale, cardDiff, card2Diff))
                    .build());
        }

        BigDecimal disbursementsTotal = BigDecimal.ZERO;
        List<KioskCashCloseReportResponse.DisbursementLine> disbursementLines = new ArrayList<>();
        for (KioskCashExpenseEntity expense : expenses) {
            BigDecimal amount = safeAmount(expense.getAmount()).setScale(2, RoundingMode.HALF_UP);
            disbursementsTotal = disbursementsTotal.add(amount);
            disbursementLines.add(KioskCashCloseReportResponse.DisbursementLine.builder()
                    .id(expense.getId())
                    .description(expense.getDescription())
                    .amount(amount)
                    .createdAt(expense.getCreatedAt())
                    .build());
        }
        disbursementsTotal = disbursementsTotal.setScale(2, RoundingMode.HALF_UP);
        cashTotal = cashTotal.setScale(2, RoundingMode.HALF_UP);
        cardTotal = cardTotal.setScale(2, RoundingMode.HALF_UP);
        salesSubtotal = salesSubtotal.setScale(2, RoundingMode.HALF_UP);

        BigDecimal opening = session.getOpeningAmount() != null
                ? session.getOpeningAmount().setScale(2, RoundingMode.HALF_UP)
                : CASH_OPENING_AMOUNT;
        BigDecimal depositAmount = cashTotal.subtract(disbursementsTotal).setScale(2, RoundingMode.HALF_UP);
        if (depositAmount.compareTo(BigDecimal.ZERO) < 0) {
            depositAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal totalCash = disbursementsTotal.add(depositAmount).setScale(2, RoundingMode.HALF_UP);
        BigDecimal closeAmount = opening.add(cardTotal).add(totalCash).setScale(2, RoundingMode.HALF_UP);
        BigDecimal salesDayTotal = closeAmount.subtract(opening).setScale(2, RoundingMode.HALF_UP);
        BigDecimal salesMinusDisbursements = salesSubtotal.subtract(disbursementsTotal).setScale(2, RoundingMode.HALF_UP);

        String depositDetail;
        if (depositSlips.isEmpty()) {
            depositDetail = "Sin boleta registrada";
        } else {
            depositDetail = "No. documento: " + String.join(", ", depositSlips);
        }

        UserEntity openedBy = session.getOpenedByUserId() != null
                ? userRepository.findById(session.getOpenedByUserId()).orElse(null)
                : null;
        UserEntity closedBy = session.getClosedByUserId() != null
                ? userRepository.findById(session.getClosedByUserId()).orElse(null)
                : null;

        return KioskCashCloseReportResponse.builder()
                .sessionId(session.getId())
                .kioskLocationId(session.getKioskLocationId())
                .kioskCode(kiosk != null ? kiosk.getCode() : null)
                .kioskName(kiosk != null ? kiosk.getName() : null)
                .openedByName(openedBy != null ? buildUserFullName(openedBy) : null)
                .closedByName(closedBy != null ? buildUserFullName(closedBy) : null)
                .generatedByName(generatedBy != null ? buildUserFullName(generatedBy) : null)
                .openedAt(session.getOpenedAt())
                .closedAt(session.getClosedAt())
                .generatedAt(GuatemalaDateTime.now())
                .openingAmount(opening)
                .cashSalesTotal(cashTotal)
                .cardSalesTotal(cardTotal)
                .salesSubtotal(salesSubtotal)
                .disbursementsTotal(disbursementsTotal)
                .salesMinusDisbursements(salesMinusDisbursements)
                .depositAmount(depositAmount)
                .depositDetail(depositDetail)
                .totalCash(totalCash)
                .closeAmount(closeAmount)
                .salesDayTotal(salesDayTotal)
                .countedCash(session.getCountedCash())
                .expectedCash(session.getExpectedCash())
                .variance(session.getVariance())
                .cardVoucherTotal(cardVoucherTotal.setScale(2, RoundingMode.HALF_UP))
                .cardInvoiceCardTotal(cardInvoiceCardTotal.setScale(2, RoundingMode.HALF_UP))
                .cardVoucherDifferencesTotal(cardVoucherDifferencesTotal.setScale(2, RoundingMode.HALF_UP))
                .sales(saleLines)
                .disbursements(disbursementLines)
                .build();
    }

    private KioskCashSessionHistoryItemResponse toCashSessionHistoryItem(
            KioskCashSessionEntity session,
            LocationEntity kiosk
    ) {
        List<KioskSaleEntity> sales = kioskSaleRepository.findByCashSessionIdOrderBySoldAtAsc(session.getId());
        int salesCount = 0;
        BigDecimal salesTotal = BigDecimal.ZERO;
        BigDecimal cashTotal = BigDecimal.ZERO;
        BigDecimal cardTotal = BigDecimal.ZERO;
        for (KioskSaleEntity sale : sales) {
            if (isVoidSale(sale)) {
                continue;
            }
            salesCount++;
            BigDecimal amount = safeAmount(sale.getTotalAmount());
            salesTotal = salesTotal.add(amount);
            String method = safeTrim(sale.getPaymentMethod()).toUpperCase(Locale.ROOT);
            if ("EFECTIVO".equals(method)) {
                cashTotal = cashTotal.add(amount);
            } else if ("TARJETA".equals(method) || "TRANSFERENCIA".equals(method)) {
                cardTotal = cardTotal.add(amount);
            } else if ("MIXTO".equals(method)) {
                cashTotal = cashTotal.add(safeAmount(sale.getCashAmount()));
                cardTotal = cardTotal.add(safeAmount(sale.getCardAmount()));
            }
        }
        UserEntity openedBy = session.getOpenedByUserId() != null
                ? userRepository.findById(session.getOpenedByUserId()).orElse(null)
                : null;
        UserEntity closedBy = session.getClosedByUserId() != null
                ? userRepository.findById(session.getClosedByUserId()).orElse(null)
                : null;
        return KioskCashSessionHistoryItemResponse.builder()
                .sessionId(session.getId())
                .kioskLocationId(session.getKioskLocationId())
                .kioskCode(kiosk != null ? kiosk.getCode() : null)
                .kioskName(kiosk != null ? kiosk.getName() : null)
                .openedByName(openedBy != null ? buildUserFullName(openedBy) : null)
                .closedByName(closedBy != null ? buildUserFullName(closedBy) : null)
                .openedAt(session.getOpenedAt())
                .closedAt(session.getClosedAt())
                .salesCount(salesCount)
                .salesTotal(salesTotal.setScale(2, RoundingMode.HALF_UP))
                .cashSalesTotal(cashTotal.setScale(2, RoundingMode.HALF_UP))
                .cardSalesTotal(cardTotal.setScale(2, RoundingMode.HALF_UP))
                .disbursementsTotal(sumCashExpenses(session.getId()))
                .openingAmount(session.getOpeningAmount())
                .countedCash(session.getCountedCash())
                .expectedCash(session.getExpectedCash())
                .variance(session.getVariance())
                .cardVoucherDifferencesTotal(sumCardVoucherDifferences(sales))
                .build();
    }

    /** Suma neta de diferencias voucher − factura del turno (informativo). */
    private BigDecimal sumCardVoucherDifferences(List<KioskSaleEntity> sales) {
        BigDecimal total = BigDecimal.ZERO;
        if (sales == null) {
            return total.setScale(2, RoundingMode.HALF_UP);
        }
        for (KioskSaleEntity sale : sales) {
            if (isVoidSale(sale)) {
                continue;
            }
            String method = safeTrim(sale.getPaymentMethod()).toUpperCase(Locale.ROOT);
            BigDecimal cardInvoice = resolveCardInvoiceAmountForClose(sale, method);
            BigDecimal cardVoucher = resolveCardVoucherAmountForClose(sale, method);
            BigDecimal cardDiff = voucherDifference(cardVoucher, cardInvoice);
            if (cardDiff != null) {
                total = total.add(cardDiff);
            }
            if (isSplitCardPayment(sale.getCard2Amount())) {
                BigDecimal card2Invoice = safeAmount(sale.getCard2Amount()).setScale(2, RoundingMode.HALF_UP);
                BigDecimal card2Voucher = sale.getCard2VoucherAmount() != null
                        ? sale.getCard2VoucherAmount().setScale(2, RoundingMode.HALF_UP)
                        : card2Invoice;
                BigDecimal card2Diff = voucherDifference(card2Voucher, card2Invoice);
                if (card2Diff != null) {
                    total = total.add(card2Diff);
                }
            }
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    private String resolveSaleInvoiceNumber(KioskSaleEntity sale) {
        String internal = taxInvoiceService.getInternalNumber(sale.getInvoiceId());
        if (internal != null && !internal.isBlank()) {
            return internal.trim();
        }
        String serie = safeTrim(sale.getFelSerie());
        String numero = safeTrim(sale.getFelNumero());
        if (!serie.isBlank() && !numero.isBlank()) {
            return serie + "-" + numero;
        }
        if (!serie.isBlank()) {
            return serie;
        }
        if (!numero.isBlank()) {
            return numero;
        }
        return "—";
    }

    private static String resolvePaymentKind(String method) {
        if ("EFECTIVO".equals(method)) {
            return "CASH";
        }
        if ("TARJETA".equals(method) || "TRANSFERENCIA".equals(method)) {
            return "CARD";
        }
        if ("MIXTO".equals(method)) {
            return "MIXED";
        }
        return "OTHER";
    }

    private String buildPaymentLabel(KioskSaleEntity sale, String method) {
        if ("EFECTIVO".equals(method)) {
            return "EFECTIVO";
        }
        if ("MIXTO".equals(method)) {
            String cardPart = buildCardPaymentLabel(sale);
            return "MIXTO · EFECTIVO " + formatMoneyPlain(sale.getCashAmount())
                    + " / " + cardPart;
        }
        if ("TARJETA".equals(method) || "TRANSFERENCIA".equals(method)) {
            return buildCardPaymentLabel(sale);
        }
        return method.isBlank() ? "—" : method;
    }

    private String buildCardPaymentLabel(KioskSaleEntity sale) {
        String auth = safeTrim(sale.getCardAuthNumber());
        String last4 = safeTrim(sale.getCardLast4());
        String base;
        if (!auth.isBlank() && !last4.isBlank()) {
            base = "No. Voucher: " + auth + ", No. Tarjeta: " + last4;
        } else if (!auth.isBlank()) {
            base = "No. Voucher: " + auth;
        } else if (!last4.isBlank()) {
            base = "No. Tarjeta: " + last4;
        } else {
            base = "TARJETA";
        }
        BigDecimal diff1 = voucherDifference(sale.getCardVoucherAmount(), sale.getCardAmount());
        BigDecimal diff2 = voucherDifference(sale.getCard2VoucherAmount(), sale.getCard2Amount());
        String note = buildVoucherDifferenceNote(sale, diff1, diff2);
        if (note != null && !note.isBlank()) {
            return base + " · " + note;
        }
        return base;
    }

    private BigDecimal resolveCardInvoiceAmountForClose(KioskSaleEntity sale, String method) {
        if ("TARJETA".equals(method) || "TRANSFERENCIA".equals(method) || "MIXTO".equals(method)) {
            if (sale.getCardAmount() != null) {
                return sale.getCardAmount().setScale(2, RoundingMode.HALF_UP);
            }
            if ("TARJETA".equals(method) || "TRANSFERENCIA".equals(method)) {
                return safeAmount(sale.getTotalAmount()).setScale(2, RoundingMode.HALF_UP);
            }
        }
        return null;
    }

    private BigDecimal resolveCardVoucherAmountForClose(KioskSaleEntity sale, String method) {
        BigDecimal invoice = resolveCardInvoiceAmountForClose(sale, method);
        if (invoice == null) {
            return null;
        }
        if (sale.getCardVoucherAmount() != null) {
            return sale.getCardVoucherAmount().setScale(2, RoundingMode.HALF_UP);
        }
        return invoice;
    }

    private String buildVoucherDifferenceNote(
            KioskSaleEntity sale,
            BigDecimal cardDiff,
            BigDecimal card2Diff
    ) {
        List<String> parts = new ArrayList<>();
        if (cardDiff != null && cardDiff.compareTo(BigDecimal.ZERO) != 0) {
            parts.add(formatVoucherDiffPhrase(
                    cardDiff,
                    hasSplitCardPayment(sale) ? "Tarjeta 1" : null,
                    sale.getCardBrand(),
                    sale.getCardAuthNumber()
            ));
        }
        if (card2Diff != null && card2Diff.compareTo(BigDecimal.ZERO) != 0) {
            parts.add(formatVoucherDiffPhrase(
                    card2Diff,
                    "Tarjeta 2",
                    sale.getCard2Brand(),
                    sale.getCard2AuthNumber()
            ));
        }
        if (parts.isEmpty()) {
            return null;
        }
        return String.join(" · ", parts);
    }

    private String formatVoucherDiffPhrase(
            BigDecimal diff,
            String prefix,
            String cardBrand,
            String voucherNumber
    ) {
        if (diff == null || diff.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        String side = diff.compareTo(BigDecimal.ZERO) > 0 ? "de más" : "de menos";
        String amount = formatMoneyPlain(diff.abs());
        List<String> bits = new ArrayList<>();
        if (prefix != null && !prefix.isBlank()) {
            bits.add(prefix);
        }
        String brand = safeTrim(cardBrand).toUpperCase(Locale.ROOT);
        if (!brand.isBlank()) {
            bits.add(brand);
        }
        String voucher = safeTrim(voucherNumber);
        if (!voucher.isBlank()) {
            bits.add("No. voucher " + voucher);
        }
        bits.add("Dif. " + amount + " " + side);
        return String.join(" · ", bits);
    }

    private String formatMoneyPlain(BigDecimal value) {
        return "Q" + safeAmount(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private BigDecimal sumCashSales(List<KioskSaleEntity> sales) {
        BigDecimal cashTotal = BigDecimal.ZERO;
        for (KioskSaleEntity sale : sales) {
            if (isVoidSale(sale)) {
                continue;
            }
            String method = safeTrim(sale.getPaymentMethod()).toUpperCase(Locale.ROOT);
            if ("EFECTIVO".equals(method)) {
                cashTotal = cashTotal.add(safeAmount(sale.getTotalAmount()));
            } else if ("MIXTO".equals(method)) {
                cashTotal = cashTotal.add(safeAmount(sale.getCashAmount()));
            }
        }
        return cashTotal.setScale(2, RoundingMode.HALF_UP);
    }

    private KioskCashExpenseResponse toCashExpenseResponse(KioskCashExpenseEntity entity) {
        UserEntity createdBy = entity.getCreatedByUserId() != null
                ? userRepository.findById(entity.getCreatedByUserId()).orElse(null)
                : null;
        String saleNumber = null;
        String internalNumber = null;
        if (entity.getKioskSaleId() != null) {
            KioskSaleEntity linkedSale = kioskSaleRepository.findById(entity.getKioskSaleId()).orElse(null);
            if (linkedSale != null) {
                saleNumber = linkedSale.getSaleNumber();
                if (linkedSale.getInvoiceId() != null) {
                    internalNumber = taxInvoiceService.getInternalNumber(linkedSale.getInvoiceId());
                }
            }
        }
        return KioskCashExpenseResponse.builder()
                .id(entity.getId())
                .cashSessionId(entity.getCashSessionId())
                .kioskSaleId(entity.getKioskSaleId())
                .saleNumber(saleNumber)
                .internalNumber(internalNumber)
                .amount(entity.getAmount())
                .description(entity.getDescription())
                .createdAt(entity.getCreatedAt())
                .createdByUserId(entity.getCreatedByUserId())
                .createdByName(createdBy != null ? buildUserFullName(createdBy) : null)
                .build();
    }

    static boolean isVoidSale(KioskSaleEntity sale) {
        return sale != null && SALE_STATUS_VOID.equalsIgnoreCase(safeTrimStatic(sale.getStatus()));
    }

    static boolean isPendingDeposit(KioskSaleEntity sale) {
        return isPendingDeposit(sale, BigDecimal.ZERO);
    }

    static boolean isPendingDeposit(KioskSaleEntity sale, BigDecimal saleDisbursementsTotal) {
        if (sale == null || isVoidSale(sale)) {
            return false;
        }
        if (!"COMPLETED".equalsIgnoreCase(safeTrimStatic(sale.getStatus()))) {
            return false;
        }
        if (!safeTrimStatic(sale.getDepositSlipNumber()).isBlank()) {
            return false;
        }
        return resolveNetDepositAmount(sale, saleDisbursementsTotal).compareTo(BigDecimal.ZERO) > 0;
    }

    static BigDecimal pendingDepositCashAmount(KioskSaleEntity sale) {
        return pendingDepositCashAmount(sale, BigDecimal.ZERO);
    }

    static BigDecimal pendingDepositCashAmount(KioskSaleEntity sale, BigDecimal saleDisbursementsTotal) {
        return resolveNetDepositAmount(sale, saleDisbursementsTotal);
    }

    static BigDecimal resolveNetDepositAmount(KioskSaleEntity sale, BigDecimal saleDisbursementsTotal) {
        if (sale == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal gross = resolveCashAmountForDeposit(sale);
        BigDecimal disbursements = saleDisbursementsTotal != null ? saleDisbursementsTotal : BigDecimal.ZERO;
        BigDecimal net = gross.subtract(disbursements).max(BigDecimal.ZERO);
        return net.setScale(2, RoundingMode.HALF_UP);
    }

    static BigDecimal resolveCashAmountForDeposit(KioskSaleEntity sale) {
        if (sale == null) {
            return BigDecimal.ZERO;
        }
        String payment = normalizePaymentMethodStatic(sale.getPaymentMethod());
        BigDecimal total = sale.getTotalAmount() != null ? sale.getTotalAmount() : BigDecimal.ZERO;
        if ("EFECTIVO".equals(payment)) {
            // El depósito corresponde al total facturado, no al efectivo recibido (puede incluir vuelto).
            return total;
        }
        if ("MIXTO".equals(payment)) {
            if (sale.getCashAmount() != null && sale.getCashAmount().compareTo(BigDecimal.ZERO) > 0) {
                return sale.getCashAmount().min(total);
            }
            BigDecimal card = sale.getCardAmount() != null ? sale.getCardAmount() : BigDecimal.ZERO;
            return total.subtract(card).max(BigDecimal.ZERO);
        }
        return BigDecimal.ZERO;
    }

    static String normalizePaymentMethodStatic(String value) {
        String normalized = safeTrimStatic(value)
                .toUpperCase(Locale.ROOT)
                .replace("Á", "A")
                .replace("É", "E")
                .replace("Í", "I")
                .replace("Ó", "O")
                .replace("Ú", "U");
        if (normalized.isBlank() || normalized.contains("EFECTIVO") || "CASH".equals(normalized)) {
            return "EFECTIVO";
        }
        if (normalized.contains("TARJETA") || normalized.contains("CARD")) {
            return "TARJETA";
        }
        if (normalized.contains("MIXTO") || normalized.contains("MIXED")) {
            return "MIXTO";
        }
        return normalized;
    }

    private static String safeTrimStatic(String value) {
        return value == null ? "" : value.trim();
    }

    private BigDecimal safeAmount(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private UserEntity resolveSaleSoldByUser(KioskSaleEntity sale, UserEntity fallbackUser) {
        Long soldByUserId = sale.getSoldByUserId();
        if (soldByUserId == null) {
            return fallbackUser;
        }
        return userRepository.findById(soldByUserId).orElse(fallbackUser);
    }

    private String normalizeReportPaymentKind(String paymentKind) throws BusinessException {
        String normalized = normalizeText(paymentKind);
        if (normalized.isBlank() || "ALL".equals(normalized) || "GENERAL".equals(normalized)) {
            return null;
        }
        if ("CASH".equals(normalized) || "EFECTIVO".equals(normalized)) {
            return "CASH";
        }
        if ("CARD".equals(normalized) || "TARJETA".equals(normalized)) {
            return "CARD";
        }
        throw new BusinessException("Filtro de pago no válido. Use ALL, CASH o CARD.");
    }

    private boolean matchesReportPaymentKind(KioskSaleEntity sale, String paymentKind) {
        if (paymentKind == null || paymentKind.isBlank()) {
            return true;
        }
        String method = safeTrim(sale.getPaymentMethod()).toUpperCase(Locale.ROOT);
        if ("CASH".equals(paymentKind)) {
            return "EFECTIVO".equals(method) || "CASH".equals(method);
        }
        if ("CARD".equals(paymentKind)) {
            return qualifiesForVoucherReport(sale);
        }
        return true;
    }

    private String buildVoucherDescription(String voucherNumber, String cardLast4) {
        return buildVoucherDescription(voucherNumber, cardLast4, null);
    }

    private String buildVoucherDescription(String voucherNumber, String cardLast4, BigDecimal difference) {
        String voucher = safeTrim(voucherNumber);
        String last4 = safeTrim(cardLast4);
        List<String> parts = new ArrayList<>();
        if (!voucher.isBlank()) {
            parts.add("No. Voucher: " + voucher);
        }
        if (!last4.isBlank()) {
            parts.add("No. Tarjeta: " + last4);
        }
        if (difference != null && difference.compareTo(BigDecimal.ZERO) != 0) {
            String side = difference.compareTo(BigDecimal.ZERO) > 0 ? "de más" : "de menos";
            parts.add("Dif. " + formatMoneyPlain(difference.abs()) + " " + side + " (factura sin cambio)");
        }
        return parts.isEmpty() ? "—" : String.join(", ", parts);
    }

    private String resolveSaleInvoiceLabel(KioskSaleEntity sale, Map<Long, TaxInvoiceEntity> invoicesById) {
        if (sale == null) {
            return "";
        }
        if (sale.getInvoiceId() != null && invoicesById.containsKey(sale.getInvoiceId())) {
            String internal = safeTrim(invoicesById.get(sale.getInvoiceId()).getInternalNumber());
            if (!internal.isBlank()) {
                return internal;
            }
        }
        String serie = safeTrim(sale.getFelSerie());
        String numero = safeTrim(sale.getFelNumero());
        if (!serie.isBlank() && !numero.isBlank()) {
            return serie + "-" + numero;
        }
        return (serie + " " + numero).trim();
    }

    static BigDecimal resolveCardAmountForReport(KioskSaleEntity sale) {
        if (sale == null) {
            return BigDecimal.ZERO;
        }
        String payment = normalizePaymentMethodStatic(sale.getPaymentMethod());
        BigDecimal total = sale.getTotalAmount() != null ? sale.getTotalAmount() : BigDecimal.ZERO;
        if ("TARJETA".equals(payment) || "CARD".equals(payment) || "TRANSFERENCIA".equals(payment)) {
            if (hasSplitCardPayment(sale)) {
                BigDecimal firstCard = sale.getCardAmount() != null ? sale.getCardAmount() : BigDecimal.ZERO;
                BigDecimal secondCard = sale.getCard2Amount() != null ? sale.getCard2Amount() : BigDecimal.ZERO;
                return firstCard.add(secondCard).min(total);
            }
            if (sale.getCardAmount() != null && sale.getCardAmount().compareTo(BigDecimal.ZERO) > 0) {
                return sale.getCardAmount().min(total);
            }
            return total;
        }
        if ("MIXTO".equals(payment)) {
            BigDecimal cashPart = sale.getCashAmount() != null && sale.getCashAmount().compareTo(BigDecimal.ZERO) > 0
                    ? sale.getCashAmount().min(total)
                    : BigDecimal.ZERO;
            if (sale.getCardAmount() != null && sale.getCardAmount().compareTo(BigDecimal.ZERO) > 0) {
                return sale.getCardAmount().min(total.subtract(cashPart).max(BigDecimal.ZERO));
            }
            if (cashPart.compareTo(BigDecimal.ZERO) > 0) {
                return total.subtract(cashPart).max(BigDecimal.ZERO);
            }
        }
        return BigDecimal.ZERO;
    }

    static boolean qualifiesForVoucherReport(KioskSaleEntity sale) {
        return resolveCardAmountForReport(sale).compareTo(BigDecimal.ZERO) > 0;
    }

    static boolean qualifiesForBankDepositReport(KioskSaleEntity sale) {
        if (sale == null || safeTrimStatic(sale.getDepositSlipNumber()).isBlank()) {
            return false;
        }
        return resolveCashAmountForDeposit(sale).compareTo(BigDecimal.ZERO) > 0;
    }

    private String buildBankDepositDescription(KioskSaleEntity sale, LocationEntity kiosk) {
        LocalDate saleDay = sale != null && sale.getSaleDate() != null
                ? sale.getSaleDate()
                : (sale != null && sale.getSoldAt() != null ? sale.getSoldAt().toLocalDate() : GuatemalaDateTime.today());
        String dayLabel = saleDay.format(DateTimeFormatter.ofPattern("dd-MM-yyyy"));
        String kioskLabel = kiosk != null ? safeTrim(kiosk.getName()) : "";
        if (!kioskLabel.isBlank()) {
            String shortName = kioskLabel
                    .replaceAll("(?i)^Kiosco\\s+", "")
                    .replaceAll("(?i)^CUEROGLAM\\s+", "")
                    .trim();
            if (!shortName.isBlank()) {
                return "deposito del dia " + dayLabel + " " + shortName.toUpperCase(Locale.ROOT);
            }
        }
        return "deposito del dia " + dayLabel;
    }

    private String buildUserFullName(UserEntity user) {
        if (user == null) {
            return "";
        }
        String firstName = safeTrim(user.getFirstName());
        String lastName = safeTrim(user.getLastName());
        String fullName = (firstName + " " + lastName).trim();
        return fullName.isBlank() ? user.getUsername() : fullName;
    }

    private String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    record DiscountResolution(
            BigDecimal discountAmount,
            Long promotionId,
            String promotionName,
            boolean autoApplied
    ) {}

    record TieredDiscountResult(
            BigDecimal discountAmount,
            boolean anyTierMatch
    ) {}

    record PreparedLine(
            ProductEntity product,
            ColorEntity color,
            String size,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal
    ) {}

    record PaymentSnapshot(
            BigDecimal amountReceived,
            BigDecimal changeAmount,
            BigDecimal cashAmount,
            BigDecimal cardAmount
    ) {}
}
