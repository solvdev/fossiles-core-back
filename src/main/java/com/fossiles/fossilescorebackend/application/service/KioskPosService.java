package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.KioskPosSaleRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskPromotionRequest;
import com.fossiles.fossilescorebackend.application.dto.response.KioskCustomerProfileResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskPosContextResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskPromotionResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskPosReportsResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskPosSaleResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskProductAvailabilityResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskCustomerProfileEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskPromotionEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSaleItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductInventoryLocation;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskCustomerProfileRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskPromotionRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSaleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductInventoryLocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
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

    private final SecurityUtil securityUtil;
    private final UserRepository userRepository;
    private final LocationRepository locationRepository;
    private final ProductRepository productRepository;
    private final ColorRepository colorRepository;
    private final ProductInventoryLocationRepository productInventoryLocationRepository;
    private final ProductInventoryService productInventoryService;
    private final KioskSaleRepository kioskSaleRepository;
    private final KioskPromotionRepository kioskPromotionRepository;
    private final KioskCustomerProfileRepository kioskCustomerProfileRepository;

    @Transactional(readOnly = true)
    public KioskPosContextResponse getCurrentContext(Long kioskLocationId) throws BusinessException {
        UserEntity user = getCurrentUserOrThrow();
        boolean admin = isAdminUser(user);
        List<LocationEntity> availableKiosks = resolveAvailableKiosks(user, admin);
        LocationEntity kiosk = resolveTargetKiosk(availableKiosks, kioskLocationId);

        List<ProductInventoryLocation> inventoryRows = productInventoryLocationRepository.findByLocationId(kiosk.getId());
        List<ProductInventoryLocation> positiveInventory = inventoryRows.stream()
                .filter(row -> row.getQuantity() != null && row.getQuantity().compareTo(BigDecimal.ZERO) > 0)
                .collect(Collectors.toList());

        Set<Long> productIds = positiveInventory.stream()
                .map(ProductInventoryLocation::getProductId)
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toSet());

        Map<Long, ProductEntity> productsById = productRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(ProductEntity::getId, item -> item));

        List<KioskPosContextResponse.InventoryItem> inventory = positiveInventory.stream()
                .map(row -> {
                    ProductEntity product = productsById.get(row.getProductId());
                    return KioskPosContextResponse.InventoryItem.builder()
                            .productId(row.getProductId())
                            .productCode(product != null ? product.getCode() : "")
                            .productName(product != null ? product.getName() : "Producto")
                            .productImageUrl(product != null ? safeTrim(product.getImageUrl()) : "")
                            .colorId(row.getColorId())
                            .colorName(row.getColor() != null ? row.getColor().getName() : "")
                            .quantity(row.getQuantity())
                            .suggestedUnitPrice(product != null && product.getSalePrice() != null
                                    ? product.getSalePrice()
                                    : BigDecimal.ZERO)
                            .build();
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
        String normalizedPaymentMethod = normalizePaymentMethod(request.getPaymentMethod());

        UserEntity user = getCurrentUserOrThrow();
        boolean admin = isAdminUser(user);
        List<LocationEntity> availableKiosks = resolveAvailableKiosks(user, admin);
        LocationEntity kiosk = resolveTargetKiosk(availableKiosks, request.getKioskLocationId());
        LocalDate saleDate = request.getSaleDate() != null ? request.getSaleDate() : LocalDate.now();
        String saleNumber = generateSaleNumber(saleDate);
        String normalizedTaxId = normalizeTaxId(request.getCustomerTaxId());
        if (normalizedTaxId != null && !"CF".equals(normalizedTaxId) && !isValidGuatemalaNit(normalizedTaxId)) {
            throw new BusinessException("El NIT ingresado no es válido para Guatemala.");
        }
        KioskPromotionEntity promotion = resolvePromotionIfAny(request.getPromotionId(), saleDate);

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
                .subtotal(BigDecimal.ZERO)
                .totalAmount(BigDecimal.ZERO)
                .discountAmount(BigDecimal.ZERO)
                .promotionId(promotion != null ? promotion.getId() : null)
                .promotionName(promotion != null ? promotion.getName() : null)
                .totalItems(BigDecimal.ZERO)
                .createdBy(user.getId())
                .items(new ArrayList<>())
                .build();

        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal totalItems = BigDecimal.ZERO;

        for (KioskPosSaleRequest.ItemRequest itemRequest : request.getItems()) {
            if (itemRequest == null || itemRequest.getProductId() == null) {
                throw new BusinessException("Todos los renglones deben tener producto.");
            }
            BigDecimal quantity = itemRequest.getQuantity() != null ? itemRequest.getQuantity() : BigDecimal.ZERO;
            if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException("La cantidad debe ser mayor a cero para todos los productos.");
            }

            ProductEntity product = productRepository.findById(itemRequest.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product", itemRequest.getProductId()));
            ColorEntity color = null;
            if (itemRequest.getColorId() != null) {
                color = colorRepository.findById(itemRequest.getColorId())
                        .orElseThrow(() -> new ResourceNotFoundException("Color", itemRequest.getColorId()));
            }

            BigDecimal unitPrice = itemRequest.getUnitPrice() != null
                    ? itemRequest.getUnitPrice()
                    : (product.getSalePrice() != null ? product.getSalePrice() : BigDecimal.ZERO);
            BigDecimal lineTotal = unitPrice.multiply(quantity);

            KioskSaleItemEntity saleItem = KioskSaleItemEntity.builder()
                    .kioskSale(sale)
                    .productId(product.getId())
                    .productCode(product.getCode())
                    .productName(product.getName())
                    .colorId(color != null ? color.getId() : null)
                    .colorName(color != null ? color.getName() : "")
                    .quantity(quantity)
                    .unitPrice(unitPrice)
                    .lineTotal(lineTotal)
                    .build();
            sale.getItems().add(saleItem);

            subtotal = subtotal.add(lineTotal);
            totalItems = totalItems.add(quantity);
        }

        sale.setSubtotal(subtotal);
        BigDecimal discountAmount = calculatePromotionDiscount(subtotal, promotion);
        sale.setDiscountAmount(discountAmount);
        sale.setTotalAmount(subtotal.subtract(discountAmount).max(BigDecimal.ZERO));
        sale.setTotalItems(totalItems);
        KioskSaleEntity saved = kioskSaleRepository.save(sale);

        for (KioskSaleItemEntity item : saved.getItems()) {
            productInventoryService.decrementInventory(
                    item.getProductId(),
                    kiosk.getId(),
                    item.getColorId(),
                    item.getQuantity(),
                    "KIOSK_SALE",
                    saved.getId(),
                    saved.getSaleNumber(),
                    "Venta POS en kiosko " + kiosk.getName()
            );
        }

        saveCustomerProfileIfNeeded(
                normalizedTaxId,
                safeTrim(request.getCustomerName()),
                safeTrim(request.getAddress()),
                safeTrim(request.getPhone()),
                safeTrim(request.getEmail())
        );

        return toSaleResponse(saved, kiosk, user);
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
        List<KioskSaleEntity> sales = findSalesByDateRange(startDate, endDate);
        return buildReportResponse(sales, startDate, endDate);
    }

    @Transactional(readOnly = true)
    public KioskCustomerProfileResponse getCustomerByTaxId(String taxId) throws BusinessException {
        String normalizedTaxId = normalizeTaxId(taxId);
        if (normalizedTaxId == null || "CF".equals(normalizedTaxId)) {
            return null;
        }
        if (!isValidGuatemalaNit(normalizedTaxId)) {
            throw new BusinessException("El NIT ingresado no es válido para Guatemala.");
        }
        return kioskCustomerProfileRepository.findByTaxId(normalizedTaxId)
                .map(this::toCustomerProfileResponse)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public List<KioskPromotionResponse> getPromotions(Boolean activeOnly) {
        boolean onlyActive = activeOnly == null || activeOnly;
        List<KioskPromotionEntity> promotions = onlyActive
                ? kioskPromotionRepository.findByActiveTrueOrderByNameAsc()
                : kioskPromotionRepository.findAll().stream()
                .sorted(Comparator.comparing(item -> safeTrim(item.getName()), String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());
        return promotions.stream().map(this::toPromotionResponse).collect(Collectors.toList());
    }

    public KioskPromotionResponse createPromotion(KioskPromotionRequest request) throws BusinessException {
        UserEntity user = getCurrentUserOrThrow();
        if (!isAdminUser(user)) {
            throw new BusinessException("Solo un administrador puede crear promociones.");
        }
        validatePromotionRequest(request);
        KioskPromotionEntity entity = KioskPromotionEntity.builder()
                .name(safeTrim(request.getName()))
                .description(safeTrim(request.getDescription()))
                .discountType(normalizeDiscountType(request.getDiscountType()))
                .discountValue(request.getDiscountValue())
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .active(request.getActive() == null || request.getActive())
                .createdBy(user.getId())
                .updatedBy(user.getId())
                .build();
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
        entity.setName(safeTrim(request.getName()));
        entity.setDescription(safeTrim(request.getDescription()));
        entity.setDiscountType(normalizeDiscountType(request.getDiscountType()));
        entity.setDiscountValue(request.getDiscountValue());
        entity.setStartDate(request.getStartDate());
        entity.setEndDate(request.getEndDate());
        entity.setActive(request.getActive() == null || request.getActive());
        entity.setUpdatedBy(user.getId());
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

        return rows.stream()
                .filter(row -> row.getQuantity() != null && row.getQuantity().compareTo(BigDecimal.ZERO) > 0)
                .filter(row -> includeCurrentKiosk || !row.getLocationId().equals(currentKiosk.getId()))
                .map(row -> {
                    LocationEntity location = locations.get(row.getLocationId());
                    return KioskProductAvailabilityResponse.builder()
                            .kioskId(row.getLocationId())
                            .kioskCode(location != null ? location.getCode() : "")
                            .kioskName(location != null ? location.getName() : "Kiosko")
                            .available(true)
                            .build();
                })
                .sorted(Comparator.comparing(KioskProductAvailabilityResponse::getKioskName, String.CASE_INSENSITIVE_ORDER))
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

    private String generateSaleNumber(LocalDate saleDate) {
        long sequence = kioskSaleRepository.countBySaleDate(saleDate) + 1;
        return String.format("POS-%s-%04d", saleDate.format(SALE_NUMBER_DATE), sequence);
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

        return KioskPosReportsResponse.builder()
                .startDate(startDate)
                .endDate(endDate)
                .salesCount(sales.size())
                .totalItems(totalItems)
                .totalAmount(totalAmount)
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
                .totalItems(sale.getTotalItems())
                .discountAmount(sale.getDiscountAmount())
                .subtotal(sale.getSubtotal())
                .totalAmount(sale.getTotalAmount())
                .notes(sale.getNotes())
                .comments(sale.getComments())
                .promotionId(sale.getPromotionId())
                .promotionName(sale.getPromotionName())
                .items(items)
                .build();
    }

    private void saveCustomerProfileIfNeeded(
            String taxId,
            String customerName,
            String address,
            String phone,
            String email
    ) {
        if (taxId == null || taxId.isBlank() || "CF".equals(taxId)) {
            return;
        }
        KioskCustomerProfileEntity profile = kioskCustomerProfileRepository.findByTaxId(taxId)
                .orElse(KioskCustomerProfileEntity.builder().taxId(taxId).build());
        profile.setCustomerName(customerName);
        profile.setAddress(address);
        profile.setPhone(phone);
        profile.setEmail(email);
        kioskCustomerProfileRepository.save(profile);
    }

    private KioskCustomerProfileResponse toCustomerProfileResponse(KioskCustomerProfileEntity entity) {
        return KioskCustomerProfileResponse.builder()
                .id(entity.getId())
                .taxId(entity.getTaxId())
                .customerName(entity.getCustomerName())
                .address(entity.getAddress())
                .phone(entity.getPhone())
                .email(entity.getEmail())
                .build();
    }

    private KioskPromotionResponse toPromotionResponse(KioskPromotionEntity entity) {
        return KioskPromotionResponse.builder()
                .id(entity.getId())
                .name(entity.getName())
                .description(entity.getDescription())
                .discountType(entity.getDiscountType())
                .discountValue(entity.getDiscountValue())
                .startDate(entity.getStartDate())
                .endDate(entity.getEndDate())
                .active(entity.getActive())
                .build();
    }

    private KioskPromotionEntity resolvePromotionIfAny(Long promotionId, LocalDate saleDate) throws BusinessException {
        if (promotionId == null) {
            return null;
        }
        KioskPromotionEntity promotion = kioskPromotionRepository.findByIdAndActiveTrue(promotionId)
                .orElseThrow(() -> new BusinessException("La promoción seleccionada no existe o está inactiva."));
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

    private BigDecimal calculatePromotionDiscount(BigDecimal subtotal, KioskPromotionEntity promotion) {
        if (promotion == null || subtotal == null || subtotal.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        String type = normalizeDiscountType(promotion.getDiscountType());
        BigDecimal value = promotion.getDiscountValue() != null ? promotion.getDiscountValue() : BigDecimal.ZERO;
        if (value.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        if ("PERCENT".equals(type)) {
            return subtotal.multiply(value).divide(new BigDecimal("100")).max(BigDecimal.ZERO).min(subtotal);
        }
        return value.max(BigDecimal.ZERO).min(subtotal);
    }

    private void validatePromotionRequest(KioskPromotionRequest request) throws BusinessException {
        if (request == null) {
            throw new BusinessException("Debes enviar la promoción.");
        }
        if (safeTrim(request.getName()).isBlank()) {
            throw new BusinessException("El nombre de la promoción es obligatorio.");
        }
        if (request.getDiscountValue() == null || request.getDiscountValue().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("El valor del descuento debe ser mayor a cero.");
        }
        String discountType = normalizeDiscountType(request.getDiscountType());
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
        throw new BusinessException("Solo se permiten pagos en efectivo o tarjeta para POS de kiosko.");
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

    private String buildUserFullName(UserEntity user) {
        String firstName = safeTrim(user.getFirstName());
        String lastName = safeTrim(user.getLastName());
        String fullName = (firstName + " " + lastName).trim();
        return fullName.isBlank() ? user.getUsername() : fullName;
    }

    private String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }
}
