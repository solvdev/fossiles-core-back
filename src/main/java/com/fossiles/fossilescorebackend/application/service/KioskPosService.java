package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.KioskCashSessionCloseRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskCashSessionOpenRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskPosDepositSlipUpdateRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskPosSalePaymentUpdateRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskPosSaleRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskSaleVoidRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskPromotionRequest;
import com.fossiles.fossilescorebackend.application.dto.response.KioskCashSessionResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskPendingDepositSummaryResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskPosContextResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskCustomerProfileResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskPromotionResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskPosManagerDashboardResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskPosReportsResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskPosSaleResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskProductAvailabilityResponse;
import com.fossiles.fossilescorebackend.application.dto.response.TaxpayerLookupResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskCashSessionEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskPromotionEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSaleItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSaleSequenceEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoStockEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductCategoryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductInventoryLocation;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskCashSessionRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskPromotionRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSaleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSaleSequenceRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoStockRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductCategoryRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductInventoryLocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import com.fossiles.fossilescorebackend.infrastructure.config.FelEmissionProperties;
import com.fossiles.fossilescorebackend.infrastructure.util.CinchoProductUtils;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    private static final ZoneId GUATEMALA_ZONE = ZoneId.of("America/Guatemala");

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
    private final KioskSaleSequenceRepository kioskSaleSequenceRepository;
    private final KioskPromotionRepository kioskPromotionRepository;
    private final FelReceptorLookupService felReceptorLookupService;
    private final TaxInvoiceService taxInvoiceService;
    private final FelEmissionProperties felEmissionProperties;
    private final KioscoInventoryService kioscoInventoryService;

    @Transactional(readOnly = true)
    public KioskPosContextResponse getCurrentContext(
            Long kioskLocationId,
            String search,
            Long categoryId,
            String colorName
    ) throws BusinessException {
        UserEntity user = getCurrentUserOrThrow();
        boolean admin = isAdminUser(user);
        List<LocationEntity> availableKiosks = resolveAvailableKiosks(user, admin);
        LocationEntity kiosk = resolveTargetKiosk(availableKiosks, kioskLocationId);

        List<KioscoStockEntity> kioscoStockRows = kioscoStockRepository
                .findByLocationIdOrderByProductIdAscColorIdAsc(kiosk.getId());
        List<ProductInventoryLocation> legacyRows = kioscoStockRows.isEmpty()
                ? productInventoryLocationRepository.findByLocationId(kiosk.getId())
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
                    if (product != null && CinchoProductUtils.isFossCinchoProduct(product)) {
                        return null;
                    }
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
                            .quantity(row.getQuantity() != null ? row.getQuantity() : BigDecimal.ZERO)
                            .suggestedUnitPrice(product != null && product.getSalePrice() != null
                                    ? product.getSalePrice()
                                    : BigDecimal.ZERO)
                            .build();
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList())
                : kioscoStockRows.stream()
                .map(row -> {
                    ProductEntity product = productsById.get(row.getProductId());
                    if (product != null && CinchoProductUtils.isFossCinchoProduct(product)) {
                        return null;
                    }
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
                            .quantity(BigDecimal.valueOf(row.getCurrentStock() != null ? row.getCurrentStock() : 0))
                            .suggestedUnitPrice(product != null && product.getSalePrice() != null
                                    ? product.getSalePrice()
                                    : BigDecimal.ZERO)
                            .build();
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

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
                .kiosks(availableKiosks.stream()
                        .map(item -> KioskPosContextResponse.KioskOption.builder()
                                .kioskId(item.getId())
                                .kioskCode(item.getCode())
                                .kioskName(item.getName())
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
        boolean admin = isAdminUser(user);
        List<LocationEntity> availableKiosks = resolveAvailableKiosks(user, admin);
        LocationEntity kiosk = resolveTargetKiosk(availableKiosks, request.getKioskLocationId());
        KioskCashSessionEntity openSession = requireOpenCashSession(kiosk.getId());
        LocalDate saleDate = request.getSaleDate() != null ? request.getSaleDate() : LocalDate.now();
        String normalizedPaymentMethod = normalizePaymentMethod(request.getPaymentMethod());

        String normalizedTaxId = normalizeTaxId(request.getCustomerTaxId());
        if (normalizedTaxId != null && !"CF".equals(normalizedTaxId) && !isValidGuatemalaNit(normalizedTaxId)) {
            throw new BusinessException("El NIT ingresado no es válido para Guatemala.");
        }

        KioskPromotionEntity promotion = resolvePromotionIfAny(request.getPromotionId(), saleDate, kiosk.getId());

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
            if (CinchoProductUtils.isFossCinchoProduct(product)) {
                throw new BusinessException("Los cinchos FOSS no se venden por este POS.");
            }

            ColorEntity color = null;
            if (itemRequest.getColorId() != null) {
                color = colorRepository.findById(itemRequest.getColorId())
                        .orElseThrow(() -> new ResourceNotFoundException("Color", itemRequest.getColorId()));
            }

            BigDecimal unitPrice = product.getSalePrice() != null ? product.getSalePrice() : BigDecimal.ZERO;
            BigDecimal lineTotal = unitPrice.multiply(quantity).setScale(2, RoundingMode.HALF_UP);

            preparedLines.add(new PreparedLine(
                    product,
                    color,
                    quantity,
                    unitPrice,
                    lineTotal
            ));
            subtotal = subtotal.add(lineTotal);
            totalItems = totalItems.add(quantity);
        }

        BigDecimal discountAmount = calculatePromotionDiscount(subtotal, promotion, preparedLines);
        BigDecimal totalAmount = subtotal.subtract(discountAmount).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);

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
                .customerTaxId(normalizedTaxId)
                .customerName(safeTrim(request.getCustomerName()))
                .address(safeTrim(request.getAddress()))
                .phone(safeTrim(request.getPhone()))
                .email(safeTrim(request.getEmail()))
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
                .cardAmount(payment.cardAmount())
                .promotionId(promotion != null ? promotion.getId() : null)
                .promotionName(promotion != null ? promotion.getName() : null)
                .totalItems(totalItems)
                .testSale(isPosTestSale(kiosk))
                .cashSessionId(openSession.getId())
                .createdBy(user.getId())
                .items(new ArrayList<>())
                .build();

        for (PreparedLine line : preparedLines) {
            KioskSaleItemEntity saleItem = KioskSaleItemEntity.builder()
                    .kioskSale(sale)
                    .productId(line.product().getId())
                    .productCode(line.product().getCode())
                    .productName(line.product().getName())
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
            String[] parts = entry.getKey().split(":");
            Long productId = Long.parseLong(parts[0]);
            Long colorId = "null".equals(parts[1]) ? null : Long.parseLong(parts[1]);
            BigDecimal qty = entry.getValue();
            productInventoryService.decrementInventory(
                    productId,
                    kiosk.getId(),
                    colorId,
                    qty,
                    "KIOSK_SALE",
                    saved.getId(),
                    saved.getSaleNumber(),
                    "Venta POS en kiosko " + kiosk.getName()
            );
            kioscoInventoryService.registrarVentaDesdeIntegracion(
                    kiosk.getId(),
                    productId,
                    colorId,
                    qty,
                    saved.getId(),
                    user.getId()
            );
        }

        taxInvoiceService.issueFromKioskSale(saved, Boolean.TRUE.equals(request.getRequestInvoice()));
        saved = kioskSaleRepository.findById(saved.getId()).orElse(saved);

        return toSaleResponse(saved, kiosk, user);
    }

    @Transactional(readOnly = true)
    public KioskPosSaleResponse getSaleById(Long saleId, Long kioskLocationId)
            throws BusinessException, ResourceNotFoundException {
        UserEntity user = getCurrentUserOrThrow();
        boolean admin = isAdminUser(user);
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
        boolean admin = isAdminUser(user);
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
        BigDecimal totalAmount = sale.getTotalAmount() != null ? sale.getTotalAmount() : BigDecimal.ZERO;
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
        sale.setCardAmount(payment.cardAmount());
        KioskSaleEntity saved = kioskSaleRepository.save(sale);
        return toSaleResponse(saved, kiosk, user);
    }

    public KioskPosSaleResponse voidSale(Long saleId, Long kioskLocationId, KioskSaleVoidRequest request)
            throws BusinessException, ResourceNotFoundException {
        if (request == null || safeTrim(request.getReason()).isBlank()) {
            throw new BusinessException("Debes indicar el motivo de anulación.");
        }
        UserEntity user = getCurrentUserOrThrow();
        boolean admin = isAdminUser(user);
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
            KioskCashSessionEntity session = kioskCashSessionRepository.findById(sale.getCashSessionId())
                    .orElse(null);
            if (session != null && !CASH_SESSION_OPEN.equalsIgnoreCase(safeTrim(session.getStatus()))) {
                throw new BusinessException("Solo puedes anular ventas de la caja abierta actual.");
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
                productInventoryService.incrementInventory(
                        item.getProductId(),
                        kiosk.getId(),
                        item.getColorId(),
                        item.getQuantity(),
                        null,
                        "KIOSK_SALE_VOID",
                        sale.getId(),
                        sale.getSaleNumber(),
                        "Anulacion venta POS"
                );
                kioscoInventoryService.anularFacturaDesdeIntegracion(
                        sale.getId(),
                        kiosk.getId(),
                        item.getProductId(),
                        item.getColorId(),
                        item.getQuantity(),
                        request.getReason(),
                        user.getId()
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
        boolean admin = isAdminUser(user);
        List<LocationEntity> availableKiosks = resolveAvailableKiosks(user, admin);
        LocationEntity kiosk = resolveTargetKiosk(availableKiosks, kioskLocationId);

        KioskSaleEntity sale = kioskSaleRepository.findById(saleId)
                .orElseThrow(() -> new ResourceNotFoundException("KioskSale", saleId));
        if (!Objects.equals(sale.getKioskLocationId(), kiosk.getId())) {
            throw new BusinessException("No tienes acceso a esta venta.");
        }
        if (!isPendingDeposit(sale)) {
            throw new BusinessException("Esta venta no requiere boleta de depósito o ya fue registrada.");
        }

        sale.setDepositSlipNumber(safeTrim(request.getDepositSlipNumber()));
        sale.setDepositRecordedAt(LocalDateTime.now());
        sale.setDepositRecordedBy(user.getId());
        KioskSaleEntity saved = kioskSaleRepository.save(sale);
        return toSaleResponse(saved, kiosk, user);
    }

    @Transactional(readOnly = true)
    public KioskPendingDepositSummaryResponse getPendingDepositSummary(Long kioskLocationId) throws BusinessException {
        UserEntity user = getCurrentUserOrThrow();
        boolean admin = isAdminUser(user);
        List<LocationEntity> availableKiosks = resolveAvailableKiosks(user, admin);
        LocationEntity kiosk = resolveTargetKiosk(availableKiosks, kioskLocationId);

        List<KioskSaleEntity> pendingSales = kioskSaleRepository.findPendingDepositsByKioskLocationId(kiosk.getId());
        BigDecimal pendingAmount = pendingSales.stream()
                .map(KioskPosService::pendingDepositCashAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        return KioskPendingDepositSummaryResponse.builder()
                .kioskLocationId(kiosk.getId())
                .pendingCount(pendingSales.size())
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
        boolean admin = isAdminUser(user);
        List<LocationEntity> availableKiosks = resolveAvailableKiosks(user, admin);
        LocationEntity kiosk = resolveTargetKiosk(availableKiosks, kioskLocationId);
        List<KioskSaleEntity> sales = findSalesByDateRangeForKiosk(kiosk.getId(), startDate, endDate);
        return sales.stream().map(sale -> toSaleResponse(sale, kiosk, user)).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public KioskPosManagerDashboardResponse getManagerDashboard(Long kioskLocationId) throws BusinessException {
        UserEntity user = getCurrentUserOrThrow();
        boolean admin = isAdminUser(user);
        List<LocationEntity> availableKiosks = resolveAvailableKiosks(user, admin);
        LocationEntity kiosk = resolveTargetKiosk(availableKiosks, kioskLocationId);

        LocalDate today = LocalDate.now(GUATEMALA_ZONE);
        LocalDate todayLastYear = today.minusYears(1);
        LocalDate lastMonthStart = today.minusMonths(1).withDayOfMonth(1);
        LocalDate lastMonthEnd = today.withDayOfMonth(1).minusDays(1);
        LocalDate monthToDateStart = today.withDayOfMonth(1);
        LocalDate rangeStart = todayLastYear.isBefore(lastMonthStart)
                ? todayLastYear
                : lastMonthStart;
        LocalDate rangeEnd = today.isAfter(lastMonthEnd) ? today : lastMonthEnd;

        Object[] row = kioskSaleRepository.aggregateManagerDashboardMetrics(
                kiosk.getId(),
                today,
                todayLastYear,
                lastMonthStart,
                lastMonthEnd,
                monthToDateStart,
                rangeStart,
                rangeEnd
        );

        KioskPosManagerDashboardResponse.Metric todayMetric = toDashboardMetric(row, 0, 1);
        KioskPosManagerDashboardResponse.Metric todayLastYearMetric = toDashboardMetric(row, 2, 3);
        KioskPosManagerDashboardResponse.Metric lastMonthMetric = toDashboardMetric(row, 4, 5);
        KioskPosManagerDashboardResponse.Metric monthToDateMetric = toDashboardMetric(row, 6, 7);

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
        boolean admin = isAdminUser(user);
        List<LocationEntity> availableKiosks = resolveAvailableKiosks(user, admin);
        LocationEntity kiosk = resolveTargetKiosk(availableKiosks, kioskLocationId);
        List<KioskSaleEntity> sales = findSalesByDateRangeForKiosk(kiosk.getId(), startDate, endDate);
        return buildReportResponse(sales, startDate, endDate);
    }

    @Transactional(readOnly = true)
    public KioskPosReportsResponse getGeneralReport(LocalDate startDate, LocalDate endDate) throws BusinessException {
        UserEntity user = getCurrentUserOrThrow();
        if (!isAdminUser(user)) {
            throw new BusinessException("Solo administradores pueden ver el reporte general de kioskos.");
        }
        List<KioskSaleEntity> sales = findSalesByDateRange(startDate, endDate).stream()
                .filter(KioskPosService::countsForProductionMetrics)
                .collect(Collectors.toList());
        return buildReportResponse(sales, startDate, endDate);
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
        boolean admin = isAdminUser(user);
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
        LocalDate today = LocalDate.now();
        List<KioskPromotionEntity> promotions = onlyActive
                ? kioskPromotionRepository.findByActiveTrueOrderByNameAsc()
                : kioskPromotionRepository.findAll().stream()
                .sorted(Comparator.comparing(item -> safeTrim(item.getName()), String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());

        final Set<Long> scopedKioskIds = allowedKioskIds;
        return promotions.stream()
                .filter(p -> !onlyActive || isPromotionActiveOnDate(p, today))
                .filter(p -> p.getKioskLocationId() == null
                        || (kioskLocationId != null && Objects.equals(p.getKioskLocationId(), kioskLocationId)))
                .filter(p -> admin || scopedKioskIds == null
                        || p.getKioskLocationId() == null
                        || scopedKioskIds.contains(p.getKioskLocationId()))
                .map(this::toPromotionResponse)
                .collect(Collectors.toList());
    }

    public KioskPromotionResponse createPromotion(KioskPromotionRequest request) throws BusinessException {
        UserEntity user = getCurrentUserOrThrow();
        if (!isAdminUser(user)) {
            throw new BusinessException("Solo un administrador puede crear promociones.");
        }
        validatePromotionRequest(request);
        KioskPromotionEntity entity = toPromotionEntity(request, user.getId());
        return toPromotionResponse(kioskPromotionRepository.save(entity));
    }

    public KioskPromotionResponse updatePromotion(Long id, KioskPromotionRequest request)
            throws BusinessException, ResourceNotFoundException {
        UserEntity user = getCurrentUserOrThrow();
        if (!isAdminUser(user)) {
            throw new BusinessException("Solo un administrador puede editar promociones.");
        }
        validatePromotionRequest(request);
        KioskPromotionEntity entity = kioskPromotionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("KioskPromotion", id));
        applyPromotionRequest(entity, request, user.getId());
        return toPromotionResponse(kioskPromotionRepository.save(entity));
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
        boolean admin = isAdminUser(user);
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
            String key = inventoryKey(item.getProductId(), item.getColorId());
            BigDecimal qty = item.getQuantity() != null ? item.getQuantity() : BigDecimal.ZERO;
            aggregated.merge(key, qty, BigDecimal::add);
        }
        return aggregated;
    }

    Map<String, ProductInventoryLocation> lockAndValidateStock(Long kioskId, Map<String, BigDecimal> aggregatedQty)
            throws BusinessException {
        List<String> sortedKeys = aggregatedQty.keySet().stream().sorted().toList();
        Map<String, ProductInventoryLocation> locked = new LinkedHashMap<>();
        for (String key : sortedKeys) {
            String[] parts = key.split(":");
            Long productId = Long.parseLong(parts[0]);
            Long colorId = "null".equals(parts[1]) ? null : Long.parseLong(parts[1]);
            BigDecimal requested = aggregatedQty.get(key);

            ProductInventoryLocation row = productInventoryLocationRepository
                    .findWithLockByProductIdAndLocationIdAndColorId(productId, kioskId, colorId)
                    .orElseThrow(() -> new BusinessException(
                            "Stock insuficiente: no hay inventario para el producto solicitado en este kiosko."));

            BigDecimal available = row.getQuantity() != null ? row.getQuantity() : BigDecimal.ZERO;
            if (requested.compareTo(available) > 0) {
                ProductEntity product = productRepository.findById(productId).orElse(null);
                String label = product != null ? product.getName() : "Producto";
                throw new BusinessException(String.format(
                        "Stock insuficiente para %s. Disponible: %s, solicitado: %s.",
                        label,
                        available.stripTrailingZeros().toPlainString(),
                        requested.stripTrailingZeros().toPlainString()));
            }
            locked.put(key, row);
        }
        return locked;
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
        if ("COMBO".equals(type)) {
            return calculateComboDiscount(promotion, lines);
        }
        BigDecimal value = promotion.getDiscountValue() != null ? promotion.getDiscountValue() : BigDecimal.ZERO;
        if (value.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        if ("PERCENT".equals(type)) {
            return subtotal.multiply(value)
                    .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP)
                    .max(BigDecimal.ZERO)
                    .min(subtotal);
        }
        return value.max(BigDecimal.ZERO).min(subtotal).setScale(2, RoundingMode.HALF_UP);
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

    private String inventoryKey(Long productId, Long colorId) {
        return productId + ":" + (colorId != null ? colorId : "null");
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
        return KioskPromotionEntity.builder()
                .name(safeTrim(request.getName()))
                .description(safeTrim(request.getDescription()))
                .discountType(discountType)
                .discountValue(discountValue)
                .comboBuyQty(request.getComboBuyQty())
                .comboPayQty(request.getComboPayQty())
                .kioskLocationId(request.getKioskLocationId())
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .active(request.getActive() == null || request.getActive())
                .createdBy(userId)
                .updatedBy(userId)
                .build();
    }

    private void applyPromotionRequest(KioskPromotionEntity entity, KioskPromotionRequest request, Long userId) {
        entity.setName(safeTrim(request.getName()));
        entity.setDescription(safeTrim(request.getDescription()));
        entity.setDiscountType(normalizeDiscountType(request.getDiscountType()));
        entity.setDiscountValue(request.getDiscountValue());
        entity.setComboBuyQty(request.getComboBuyQty());
        entity.setComboPayQty(request.getComboPayQty());
        entity.setKioskLocationId(request.getKioskLocationId());
        entity.setStartDate(request.getStartDate());
        entity.setEndDate(request.getEndDate());
        entity.setActive(request.getActive() == null || request.getActive());
        entity.setUpdatedBy(userId);
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

    private boolean isAdminUser(UserEntity user) {
        if (user == null || user.getRoles() == null) {
            return false;
        }
        return user.getRoles().stream()
                .filter(Objects::nonNull)
                .map(role -> normalizeText(role.getName()))
                .anyMatch(roleName -> roleName.contains("ADMIN"));
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

    private KioskPosManagerDashboardResponse.Metric toDashboardMetric(Object[] row, int amountIndex, int countIndex) {
        BigDecimal amount = BigDecimal.ZERO;
        int count = 0;
        if (row != null && row.length > countIndex) {
            if (row[amountIndex] instanceof BigDecimal bd) {
                amount = bd;
            } else if (row[amountIndex] instanceof Number number) {
                amount = BigDecimal.valueOf(number.doubleValue());
            }
            if (row[countIndex] instanceof Number number) {
                count = number.intValue();
            }
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

    private boolean isPosTestSale(LocationEntity kiosk) {
        if (kiosk != null && kiosk.getPosTestMode() != null) {
            return Boolean.TRUE.equals(kiosk.getPosTestMode());
        }
        return felEmissionProperties.isTestMode();
    }

    private List<KioskSaleEntity> findSalesByDateRangeForKiosk(Long kioskId, LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null) {
            return kioskSaleRepository.findByKioskLocationIdAndSaleDateBetweenOrderBySoldAtDesc(kioskId, startDate, endDate);
        }
        return kioskSaleRepository.findByKioskLocationIdOrderBySoldAtDesc(kioskId);
    }

    private List<KioskSaleEntity> findSalesByDateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null) {
            return kioskSaleRepository.findBySaleDateBetweenOrderBySoldAtDesc(startDate, endDate);
        }
        return kioskSaleRepository.findAll().stream()
                .sorted(Comparator.comparing(KioskSaleEntity::getSoldAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());
    }

    private KioskPosReportsResponse buildReportResponse(List<KioskSaleEntity> sales, LocalDate startDate, LocalDate endDate) {
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
            BigDecimal saleAmount = sale.getTotalAmount() != null ? sale.getTotalAmount() : BigDecimal.ZERO;
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
        List<KioskPosSaleResponse.Item> items = sale.getItems() == null
                ? List.of()
                : sale.getItems().stream().map(row -> KioskPosSaleResponse.Item.builder()
                        .id(row.getId())
                        .productId(row.getProductId())
                        .productCode(row.getProductCode())
                        .productName(row.getProductName())
                        .colorId(row.getColorId())
                        .colorName(row.getColorName())
                        .quantity(row.getQuantity())
                        .unitPrice(row.getUnitPrice())
                        .lineTotal(row.getLineTotal())
                        .build())
                .collect(Collectors.toList());

        UserEntity depositRecordedBy = sale.getDepositRecordedBy() != null
                ? userRepository.findById(sale.getDepositRecordedBy()).orElse(null)
                : null;

        return KioskPosSaleResponse.builder()
                .id(sale.getId())
                .saleNumber(sale.getSaleNumber())
                .saleDate(sale.getSaleDate())
                .soldAt(sale.getSoldAt())
                .kioskId(kiosk.getId())
                .kioskCode(kiosk.getCode())
                .kioskName(kiosk.getName())
                .soldByUserId(user.getId())
                .soldByUsername(user.getUsername())
                .soldByName(buildUserFullName(user))
                .customerTaxId(sale.getCustomerTaxId())
                .customerName(sale.getCustomerName())
                .address(sale.getAddress())
                .phone(sale.getPhone())
                .email(sale.getEmail())
                .paymentMethod(sale.getPaymentMethod())
                .status(sale.getStatus())
                .testSale(Boolean.TRUE.equals(sale.getTestSale()))
                .totalItems(sale.getTotalItems())
                .discountAmount(sale.getDiscountAmount())
                .subtotal(sale.getSubtotal())
                .totalAmount(sale.getTotalAmount())
                .amountReceived(sale.getAmountReceived())
                .changeAmount(sale.getChangeAmount())
                .cashAmount(sale.getCashAmount())
                .cardAmount(sale.getCardAmount())
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
                .invoice(buildInvoiceInfo(sale))
                .depositSlipNumber(sale.getDepositSlipNumber())
                .depositRecordedAt(sale.getDepositRecordedAt())
                .depositRecordedByUserId(sale.getDepositRecordedBy())
                .depositRecordedByName(depositRecordedBy != null ? buildUserFullName(depositRecordedBy) : null)
                .pendingDeposit(isPendingDeposit(sale))
                .items(items)
                .build();
    }

    private KioskPosSaleResponse.InvoiceInfo buildInvoiceInfo(KioskSaleEntity sale) {
        if (sale.getInvoiceId() == null && (sale.getFelStatus() == null || sale.getFelStatus().isBlank())) {
            return null;
        }
        return KioskPosSaleResponse.InvoiceInfo.builder()
                .id(sale.getInvoiceId())
                .status(sale.getFelStatus())
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
                .startDate(entity.getStartDate())
                .endDate(entity.getEndDate())
                .active(entity.getActive())
                .build();
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
            return;
        }
        if (request.getDiscountValue() == null || request.getDiscountValue().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("El valor del descuento debe ser mayor a cero.");
        }
        if ("PERCENT".equals(discountType) && request.getDiscountValue().compareTo(new BigDecimal("100")) > 0) {
            throw new BusinessException("El descuento porcentual no puede ser mayor a 100.");
        }
        if (request.getStartDate() != null && request.getEndDate() != null
                && request.getEndDate().isBefore(request.getStartDate())) {
            throw new BusinessException("La fecha fin no puede ser menor a la fecha inicio.");
        }
    }

    private String normalizeDiscountType(String value) {
        String normalized = normalizeText(value);
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
        boolean admin = isAdminUser(user);
        List<LocationEntity> availableKiosks = resolveAvailableKiosks(user, admin);
        LocationEntity kiosk = resolveTargetKiosk(availableKiosks, kioskLocationId);
        return kioskCashSessionRepository
                .findFirstByKioskLocationIdAndStatusOrderByOpenedAtDesc(kiosk.getId(), CASH_SESSION_OPEN)
                .map(session -> toCashSessionResponse(session, kiosk))
                .orElse(null);
    }

    public KioskCashSessionResponse openCashSession(KioskCashSessionOpenRequest request) throws BusinessException {
        UserEntity user = getCurrentUserOrThrow();
        boolean admin = isAdminUser(user);
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
                .openingAmount(CASH_OPENING_AMOUNT)
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

        boolean admin = isAdminUser(user);
        List<LocationEntity> availableKiosks = resolveAvailableKiosks(user, admin);
        resolveTargetKiosk(availableKiosks, session.getKioskLocationId());

        if (request == null || request.getCountedCash() == null) {
            throw new BusinessException("Debes ingresar el efectivo contado en caja.");
        }

        List<KioskSaleEntity> sessionSales = kioskSaleRepository.findByCashSessionIdOrderBySoldAtAsc(sessionId);
        BigDecimal expectedCash = calculateExpectedCash(session, sessionSales);
        BigDecimal countedCash = request.getCountedCash().setScale(2, RoundingMode.HALF_UP);
        BigDecimal variance = countedCash.subtract(expectedCash).setScale(2, RoundingMode.HALF_UP);

        session.setClosedAt(LocalDateTime.now());
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
        return expected.setScale(2, RoundingMode.HALF_UP);
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
                .build();
    }

    static boolean isVoidSale(KioskSaleEntity sale) {
        return sale != null && SALE_STATUS_VOID.equalsIgnoreCase(safeTrimStatic(sale.getStatus()));
    }

    static boolean isPendingDeposit(KioskSaleEntity sale) {
        if (sale == null || isVoidSale(sale)) {
            return false;
        }
        if (!"COMPLETED".equalsIgnoreCase(safeTrimStatic(sale.getStatus()))) {
            return false;
        }
        if (!safeTrimStatic(sale.getDepositSlipNumber()).isBlank()) {
            return false;
        }
        return resolveCashAmountForDeposit(sale).compareTo(BigDecimal.ZERO) > 0;
    }

    static BigDecimal pendingDepositCashAmount(KioskSaleEntity sale) {
        return resolveCashAmountForDeposit(sale);
    }

    static BigDecimal resolveCashAmountForDeposit(KioskSaleEntity sale) {
        if (sale == null) {
            return BigDecimal.ZERO;
        }
        String payment = normalizePaymentMethodStatic(sale.getPaymentMethod());
        if ("EFECTIVO".equals(payment)) {
            if (sale.getCashAmount() != null && sale.getCashAmount().compareTo(BigDecimal.ZERO) > 0) {
                return sale.getCashAmount();
            }
            if (sale.getAmountReceived() != null && sale.getAmountReceived().compareTo(BigDecimal.ZERO) > 0) {
                return sale.getAmountReceived();
            }
            return sale.getTotalAmount() != null ? sale.getTotalAmount() : BigDecimal.ZERO;
        }
        if ("MIXTO".equals(payment)) {
            if (sale.getCashAmount() != null && sale.getCashAmount().compareTo(BigDecimal.ZERO) > 0) {
                return sale.getCashAmount();
            }
            BigDecimal total = sale.getTotalAmount() != null ? sale.getTotalAmount() : BigDecimal.ZERO;
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

    private String buildUserFullName(UserEntity user) {
        String firstName = safeTrim(user.getFirstName());
        String lastName = safeTrim(user.getLastName());
        String fullName = (firstName + " " + lastName).trim();
        return fullName.isBlank() ? user.getUsername() : fullName;
    }

    private String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    record PreparedLine(
            ProductEntity product,
            ColorEntity color,
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
