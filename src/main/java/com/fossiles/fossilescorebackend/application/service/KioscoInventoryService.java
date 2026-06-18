package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.KioscoConsolidatedReportResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoInventoryInitializeResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoMovementResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoStockResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoMovementEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoMovementType;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoStockEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoMovementRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoStockRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class KioscoInventoryService {

    private static final String REASON_NON_RESELLABLE = "producto no apto para reventa";
    private static final String REFERENCE_KIOSCO_INVENTORY = "KIOSCO_INVENTORY";

    private final KioscoStockRepository kioscoStockRepository;
    private final KioscoMovementRepository kioscoMovementRepository;
    private final LocationRepository locationRepository;
    private final ProductRepository productRepository;
    private final ColorRepository colorRepository;
    private final UserRepository userRepository;
    private final SecurityUtil securityUtil;
    private final ProductInventoryService productInventoryService;
    private final KioskInventoryGuard kioskInventoryGuard;

    public KioscoStockResponse registrarEntrada(
            Long locationId,
            Long productId,
            Long colorId,
            Integer quantity,
            Long referenceId,
            Long userId
    ) throws BusinessException, ResourceNotFoundException {
        return registrarEntradaInternal(locationId, productId, colorId, quantity, referenceId, userId, true);
    }

    public KioscoStockResponse registrarVenta(
            Long locationId,
            Long productId,
            Long colorId,
            Integer quantity,
            Long invoiceId,
            Long userId
    ) throws BusinessException, ResourceNotFoundException {
        return registrarVentaInternal(locationId, productId, colorId, quantity, invoiceId, userId, true);
    }

    public KioscoStockResponse registrarDevolucionDeposito(
            Long locationId,
            Long productId,
            Long colorId,
            Integer quantity,
            Long referenceId,
            Long userId
    ) throws BusinessException, ResourceNotFoundException {
        return applyStockMovement(
                locationId,
                productId,
                colorId,
                quantity,
                referenceId,
                null,
                null,
                resolveUserIdRequired(userId),
                KioscoMovementType.DEVOLUCION_DEPOSITO,
                -quantity,
                true,
                null,
                true
        );
    }

    public KioscoStockResponse registrarDevolucionCliente(
            Long locationId,
            Long productId,
            Long colorId,
            Integer quantity,
            Long originalInvoiceId,
            Boolean apto,
            Long userId
    ) throws BusinessException, ResourceNotFoundException {
        if (apto == null) {
            throw new BusinessException("Debes indicar si el producto es apto para reventa.");
        }
        Long resolvedUserId = resolveUserIdRequired(userId);
        if (Boolean.TRUE.equals(apto)) {
            return applyStockMovement(
                    locationId,
                    productId,
                    colorId,
                    quantity,
                    originalInvoiceId,
                    null,
                    null,
                    resolvedUserId,
                    KioscoMovementType.DEVOLUCION_CLIENTE,
                    quantity,
                    true,
                    null,
                    true
            );
        }

        KioscoStockEntity stock = getOrCreateLockedStock(locationId, productId, colorId, resolvedUserId);
        int before = safeInt(stock.getCurrentStock());
        int after = before;
        saveMovement(stock, KioscoMovementType.DEVOLUCION_CLIENTE, quantity, before, after,
                originalInvoiceId, null, false, resolvedUserId, null, null);
        saveMovement(stock, KioscoMovementType.MERMA, quantity, before, after,
                originalInvoiceId, REASON_NON_RESELLABLE, false, resolvedUserId, null, null);
        return toStockResponse(stock);
    }

    public TrasladoResult registrarTraslado(
            Long locationOriginId,
            Long locationDestinationId,
            Long productId,
            Long colorId,
            Integer quantity,
            Long userId
    ) throws BusinessException, ResourceNotFoundException {
        return registrarTrasladoInternal(locationOriginId, locationDestinationId, productId, colorId, quantity, userId, true);
    }

    public TrasladoResult registrarTrasladoDesdeIntegracion(
            Long locationOriginId,
            Long locationDestinationId,
            Long productId,
            Long colorId,
            BigDecimal quantity,
            Long userId
    ) throws BusinessException, ResourceNotFoundException {
        int qty = normalizePositiveIntegerQuantity(quantity);
        return registrarTrasladoInternal(locationOriginId, locationDestinationId, productId, colorId, qty, userId, false);
    }

    private TrasladoResult registrarTrasladoInternal(
            Long locationOriginId,
            Long locationDestinationId,
            Long productId,
            Long colorId,
            Integer quantity,
            Long userId,
            boolean syncLegacy
    ) throws BusinessException, ResourceNotFoundException {
        validateQuantity(quantity);
        if (Objects.equals(locationOriginId, locationDestinationId)) {
            throw new BusinessException("El origen y el destino deben ser distintos.");
        }
        Long resolvedUserId = resolveUserIdRequired(userId);
        validateLocationIsKiosk(locationOriginId);
        validateLocationIsKiosk(locationDestinationId);
        validateProduct(productId);
        validateColor(colorId);

        Long transferReferenceId = generateTransferReferenceId();

        KioscoStockResponse origin = applyStockMovement(
                locationOriginId,
                productId,
                colorId,
                quantity,
                transferReferenceId,
                locationOriginId,
                locationDestinationId,
                resolvedUserId,
                KioscoMovementType.TRASLADO_SALIDA,
                -quantity,
                true,
                null,
                syncLegacy
        );

        KioscoStockResponse destination = applyStockMovement(
                locationDestinationId,
                productId,
                colorId,
                quantity,
                transferReferenceId,
                locationOriginId,
                locationDestinationId,
                resolvedUserId,
                KioscoMovementType.TRASLADO_ENTRADA,
                quantity,
                true,
                null,
                syncLegacy
        );

        return TrasladoResult.builder()
                .referenceId(transferReferenceId)
                .originStock(origin)
                .destinationStock(destination)
                .build();
    }

    public KioscoStockResponse registrarMerma(
            Long locationId,
            Long productId,
            Long colorId,
            Integer quantity,
            String reason,
            Long userId
    ) throws BusinessException, ResourceNotFoundException {
        if (safeTrim(reason).isEmpty()) {
            throw new BusinessException("El motivo de merma es obligatorio.");
        }
        return applyStockMovement(
                locationId,
                productId,
                colorId,
                quantity,
                null,
                null,
                null,
                resolveUserIdRequired(userId),
                KioscoMovementType.MERMA,
                -quantity,
                true,
                reason.trim(),
                true
        );
    }

    public KioscoStockResponse registrarAjuste(
            Long locationId,
            Long productId,
            Long colorId,
            Integer realQuantity,
            String reason,
            Long userId
    ) throws BusinessException, ResourceNotFoundException {
        if (realQuantity == null || realQuantity < 0) {
            throw new BusinessException("La cantidad real no puede ser negativa.");
        }
        if (safeTrim(reason).isEmpty()) {
            throw new BusinessException("El motivo del ajuste es obligatorio.");
        }

        Long resolvedUserId = resolveUserIdRequired(userId);
        KioscoStockEntity stock = getOrCreateLockedStock(locationId, productId, colorId, resolvedUserId);
        int before = safeInt(stock.getCurrentStock());
        int delta = realQuantity - before;
        int after = before + delta;
        if (after < 0) {
            throw new BusinessException("El ajuste resultaría en stock negativo.");
        }

        stock.setCurrentStock(after);
        stock.setUpdatedBy(resolvedUserId);
        stock.setLastUpdatedAt(LocalDateTime.now());
        KioscoStockEntity savedStock = kioscoStockRepository.save(stock);

        saveMovement(savedStock, KioscoMovementType.AJUSTE, Math.abs(delta), before, after,
                null, reason.trim(), true, resolvedUserId, null, null);

        syncLegacyInventory(locationId, productId, colorId, delta);
        if (delta < 0) {
            verificarStockMinimo(locationId, productId, colorId);
        }

        return toStockResponse(savedStock);
    }

    public KioscoStockResponse anularFactura(
            Long invoiceId,
            Long locationId,
            Long productId,
            Long colorId,
            Integer quantity,
            String reason,
            Boolean productLeftKiosk,
            Long userId
    ) throws BusinessException, ResourceNotFoundException {
        if (invoiceId == null) {
            throw new BusinessException("La factura es obligatoria.");
        }
        if (safeTrim(reason).isEmpty()) {
            throw new BusinessException("El motivo de anulación es obligatorio.");
        }
        if (productLeftKiosk == null) {
            throw new BusinessException("Debes indicar si el producto salió del kiosko.");
        }
        int delta = Boolean.TRUE.equals(productLeftKiosk) ? 0 : quantity;
        return applyStockMovement(
                locationId,
                productId,
                colorId,
                quantity,
                invoiceId,
                null,
                null,
                resolveUserIdRequired(userId),
                KioscoMovementType.ANULACION,
                delta,
                !Boolean.TRUE.equals(productLeftKiosk),
                reason.trim(),
                true
        );
    }

    public KioscoStockResponse registrarEntradaDesdeIntegracion(
            Long locationId,
            Long productId,
            Long colorId,
            BigDecimal quantity,
            Long referenceId,
            Long userId
    ) throws BusinessException, ResourceNotFoundException {
        int qty = normalizePositiveIntegerQuantity(quantity);
        return registrarEntradaInternal(locationId, productId, colorId, qty, referenceId, userId, false);
    }

    public KioscoStockResponse registrarVentaDesdeIntegracion(
            Long locationId,
            Long productId,
            Long colorId,
            BigDecimal quantity,
            Long invoiceId,
            Long userId
    ) throws BusinessException, ResourceNotFoundException {
        int qty = normalizePositiveIntegerQuantity(quantity);
        return registrarVentaInternal(locationId, productId, colorId, qty, invoiceId, userId, false);
    }

    public KioscoStockResponse anularFacturaDesdeIntegracion(
            Long invoiceId,
            Long locationId,
            Long productId,
            Long colorId,
            BigDecimal quantity,
            String reason,
            Long userId
    ) throws BusinessException, ResourceNotFoundException {
        int qty = normalizePositiveIntegerQuantity(quantity);
        return anularFactura(invoiceId, locationId, productId, colorId, qty, reason, false, userId);
    }

    @Transactional(readOnly = true)
    public List<KioscoStockResponse> getStockByLocation(Long locationId) throws BusinessException, ResourceNotFoundException {
        validateLocationIsKiosk(locationId);
        return kioscoStockRepository.findByLocationIdOrderByProductIdAscColorIdAsc(locationId).stream()
                .map(this::toStockResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<KioscoMovementResponse> getMovements(Long locationId, Long productId, Long colorId)
            throws BusinessException, ResourceNotFoundException {
        validateLocationIsKiosk(locationId);
        return kioscoMovementRepository.findByLocationAndFilters(locationId, productId, colorId).stream()
                .map(this::toMovementResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<KioscoStockResponse> getLowStock(Long locationId) throws BusinessException, ResourceNotFoundException {
        validateLocationIsKiosk(locationId);
        return kioscoStockRepository.findLowStockByLocation(locationId).stream()
                .map(this::toStockResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public KioscoConsolidatedReportResponse getConsolidatedReport() {
        List<LocationEntity> kiosks = locationRepository.findAll().stream()
                .filter(kioskInventoryGuard::isKioskLocation)
                .sorted(Comparator.comparing(LocationEntity::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .collect(Collectors.toList());
        List<KioscoConsolidatedReportResponse.KioscoSummary> summaries = new ArrayList<>();
        int totalUnits = 0;
        int totalRows = 0;
        int totalLowStockRows = 0;

        for (LocationEntity kiosk : kiosks) {
            List<KioscoStockEntity> stocks = kioscoStockRepository.findByLocationIdOrderByProductIdAscColorIdAsc(kiosk.getId());
            int kioskUnits = stocks.stream().mapToInt(s -> safeInt(s.getCurrentStock())).sum();
            int kioskLowRows = (int) stocks.stream()
                    .filter(s -> safeInt(s.getCurrentStock()) <= safeInt(s.getMinimumStock()))
                    .count();
            totalUnits += kioskUnits;
            totalRows += stocks.size();
            totalLowStockRows += kioskLowRows;

            summaries.add(KioscoConsolidatedReportResponse.KioscoSummary.builder()
                    .locationId(kiosk.getId())
                    .locationCode(kiosk.getCode())
                    .locationName(kiosk.getName())
                    .stockRows(stocks.size())
                    .totalUnits(kioskUnits)
                    .lowStockRows(kioskLowRows)
                    .build());
        }

        summaries.sort(Comparator.comparing(KioscoConsolidatedReportResponse.KioscoSummary::getLocationName,
                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));

        return KioscoConsolidatedReportResponse.builder()
                .generatedAt(LocalDateTime.now())
                .totalKiosks(kiosks.size())
                .totalStockRows(totalRows)
                .totalUnits(totalUnits)
                .totalLowStockRows(totalLowStockRows)
                .kiosks(summaries)
                .build();
    }

    public KioscoInventoryInitializeResponse initializeMissingStock(Long locationId, Long userId)
            throws BusinessException, ResourceNotFoundException {
        Long resolvedUserId = resolveUserIdRequired(userId);
        List<LocationEntity> kiosks = resolveKioskLocations(locationId);

        List<ProductEntity> products = productRepository.findAll();
        int createdCount = 0;
        int existingCount = 0;

        for (LocationEntity kiosk : kiosks) {
            for (ProductEntity product : products) {
                if (product == null || product.getId() == null) {
                    continue;
                }
                boolean exists = kioscoStockRepository
                        .findByLocationIdAndProductIdAndColorId(kiosk.getId(), product.getId(), null)
                        .isPresent();
                if (exists) {
                    existingCount++;
                    continue;
                }
                kioscoStockRepository.save(KioscoStockEntity.builder()
                        .locationId(kiosk.getId())
                        .productId(product.getId())
                        .colorId(null)
                        .currentStock(0)
                        .minimumStock(0)
                        .createdBy(resolvedUserId)
                        .updatedBy(resolvedUserId)
                        .build());
                createdCount++;
            }
        }

        String scopeLabel = locationId != null ? "kiosko seleccionado" : "todos los kioskos";
        return KioscoInventoryInitializeResponse.builder()
                .message("Inventario kiosko inicializado para " + scopeLabel + ".")
                .kiosksProcessed(kiosks.size())
                .productsProcessed(products.size())
                .createdCount(createdCount)
                .existingCount(existingCount)
                .locationId(locationId)
                .build();
    }

    private List<LocationEntity> resolveKioskLocations(Long locationId) throws ResourceNotFoundException, BusinessException {
        if (locationId != null) {
            validateLocationIsKiosk(locationId);
            LocationEntity location = locationRepository.findById(locationId)
                    .orElseThrow(() -> new ResourceNotFoundException("Location", locationId));
            return List.of(location);
        }
        return locationRepository.findAll().stream()
                .filter(kioskInventoryGuard::isKioskLocation)
                .sorted(Comparator.comparing(LocationEntity::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public void verificarStockMinimo(Long locationId, Long productId, Long colorId)
            throws BusinessException, ResourceNotFoundException {
        validateLocationIsKiosk(locationId);
        validateProduct(productId);
        KioscoStockEntity stock = kioscoStockRepository
                .findByLocationIdAndProductIdAndColorId(locationId, productId, colorId)
                .orElse(null);
        if (stock == null) {
            return;
        }
        int current = safeInt(stock.getCurrentStock());
        int minimum = safeInt(stock.getMinimumStock());
        if (current <= minimum) {
            log.warn("KIOSCO_LOW_STOCK locationId={} productId={} colorId={} currentStock={} minimumStock={}",
                    locationId, productId, colorId, current, minimum);
        }
    }

    private KioscoStockResponse registrarEntradaInternal(
            Long locationId,
            Long productId,
            Long colorId,
            Integer quantity,
            Long referenceId,
            Long userId,
            boolean syncLegacy
    ) throws BusinessException, ResourceNotFoundException {
        return applyStockMovement(
                locationId,
                productId,
                colorId,
                quantity,
                referenceId,
                null,
                null,
                resolveUserIdRequired(userId),
                KioscoMovementType.ENTRADA,
                quantity,
                true,
                null,
                syncLegacy
        );
    }

    private KioscoStockResponse registrarVentaInternal(
            Long locationId,
            Long productId,
            Long colorId,
            Integer quantity,
            Long invoiceId,
            Long userId,
            boolean syncLegacy
    ) throws BusinessException, ResourceNotFoundException {
        KioscoStockResponse response = applyStockMovement(
                locationId,
                productId,
                colorId,
                quantity,
                invoiceId,
                null,
                null,
                resolveUserIdRequired(userId),
                KioscoMovementType.VENTA,
                -quantity,
                true,
                null,
                syncLegacy
        );
        verificarStockMinimo(locationId, productId, colorId);
        return response;
    }

    private KioscoStockResponse applyStockMovement(
            Long locationId,
            Long productId,
            Long colorId,
            Integer quantity,
            Long referenceId,
            Long originLocationId,
            Long destinationLocationId,
            Long userId,
            KioscoMovementType movementType,
            int delta,
            boolean affectsStock,
            String reason,
            boolean syncLegacy
    ) throws BusinessException, ResourceNotFoundException {
        validateQuantity(quantity);
        validateLocationIsKiosk(locationId);
        validateProduct(productId);
        validateColor(colorId);
        validateUser(userId);

        KioscoStockEntity stock = getOrCreateLockedStock(locationId, productId, colorId, userId);
        int before = safeInt(stock.getCurrentStock());
        int after = before + delta;

        if (after < 0) {
            throw new BusinessException("Stock insuficiente en kiosko. Disponible: " + before + ", solicitado: " + quantity);
        }

        if (affectsStock) {
            stock.setCurrentStock(after);
            stock.setUpdatedBy(userId);
            stock.setLastUpdatedAt(LocalDateTime.now());
            stock = kioscoStockRepository.save(stock);
        } else {
            after = before;
        }

        saveMovement(
                stock,
                movementType,
                quantity,
                before,
                after,
                referenceId,
                reason,
                affectsStock,
                userId,
                originLocationId,
                destinationLocationId
        );

        if (syncLegacy && affectsStock) {
            syncLegacyInventory(locationId, productId, colorId, delta);
        }

        return toStockResponse(stock);
    }

    private void saveMovement(
            KioscoStockEntity stock,
            KioscoMovementType movementType,
            Integer quantity,
            Integer stockBefore,
            Integer stockAfter,
            Long referenceId,
            String reason,
            boolean affectsStock,
            Long userId,
            Long originLocationId,
            Long destinationLocationId
    ) {
        KioscoMovementEntity movement = KioscoMovementEntity.builder()
                .kioscoStockId(stock.getId())
                .movementType(movementType)
                .quantity(quantity)
                .stockBefore(stockBefore)
                .stockAfter(stockAfter)
                .referenceId(referenceId)
                .reason(safeTrim(reason))
                .affectsStock(affectsStock)
                .userId(userId)
                .originLocationId(originLocationId)
                .destinationLocationId(destinationLocationId)
                .build();
        kioscoMovementRepository.save(movement);
    }

    private KioscoStockEntity getOrCreateLockedStock(Long locationId, Long productId, Long colorId, Long userId)
            throws BusinessException {
        try {
            return kioscoStockRepository.findForUpdate(locationId, productId, colorId)
                    .orElseGet(() -> kioscoStockRepository.save(KioscoStockEntity.builder()
                            .locationId(locationId)
                            .productId(productId)
                            .colorId(colorId)
                            .currentStock(0)
                            .minimumStock(0)
                            .createdBy(userId)
                            .updatedBy(userId)
                            .build()));
        } catch (DataIntegrityViolationException ex) {
            return kioscoStockRepository.findForUpdate(locationId, productId, colorId)
                    .orElseThrow(() -> new BusinessException("No se pudo preparar el stock de kiosko para la operación.", ex));
        }
    }

    private void syncLegacyInventory(Long locationId, Long productId, Long colorId, int delta)
            throws BusinessException, ResourceNotFoundException {
        if (delta == 0) {
            return;
        }
        BigDecimal qty = BigDecimal.valueOf(Math.abs(delta));
        if (delta > 0) {
            productInventoryService.incrementInventory(
                    productId,
                    locationId,
                    colorId,
                    qty,
                    null,
                    REFERENCE_KIOSCO_INVENTORY,
                    null,
                    null,
                    "Sincronización desde módulo kiosco"
            );
            return;
        }
        productInventoryService.decrementInventory(
                productId,
                locationId,
                colorId,
                qty,
                REFERENCE_KIOSCO_INVENTORY,
                null,
                null,
                "Sincronización desde módulo kiosco"
        );
    }

    private void validateLocationIsKiosk(Long locationId) throws ResourceNotFoundException, BusinessException {
        LocationEntity location = locationRepository.findById(locationId)
                .orElseThrow(() -> new ResourceNotFoundException("Location", locationId));
        if (!kioskInventoryGuard.isKioskLocation(location)) {
            throw new BusinessException("La ubicación no es de tipo kiosko.");
        }
    }

    private void validateProduct(Long productId) throws ResourceNotFoundException {
        if (productId == null || !productRepository.existsById(productId)) {
            throw new ResourceNotFoundException("Product", productId);
        }
    }

    private void validateColor(Long colorId) throws ResourceNotFoundException {
        if (colorId != null && !colorRepository.existsById(colorId)) {
            throw new ResourceNotFoundException("Color", colorId);
        }
    }

    private void validateUser(Long userId) throws ResourceNotFoundException {
        if (userId == null || !userRepository.existsById(userId)) {
            throw new ResourceNotFoundException("User", userId);
        }
    }

    private void validateQuantity(Integer quantity) throws BusinessException {
        if (quantity == null || quantity <= 0) {
            throw new BusinessException("La cantidad debe ser un entero mayor a cero.");
        }
    }

    private Long resolveUserIdRequired(Long userId) throws BusinessException {
        if (userId != null) {
            return userId;
        }
        Long currentUserId = securityUtil.getCurrentUserId();
        if (currentUserId == null) {
            throw new BusinessException("usuario_id es obligatorio para auditoría.");
        }
        return currentUserId;
    }

    private int normalizePositiveIntegerQuantity(BigDecimal quantity) throws BusinessException {
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("La cantidad debe ser mayor a cero.");
        }
        BigDecimal normalized = quantity.stripTrailingZeros();
        if (normalized.scale() > 0) {
            throw new BusinessException("La cantidad debe ser un entero para el módulo kiosco.");
        }
        return normalized.intValueExact();
    }

    private Long generateTransferReferenceId() {
        return ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE - 1);
    }

    private int safeInt(Integer value) {
        return value != null ? value : 0;
    }

    private String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private KioscoStockResponse toStockResponse(KioscoStockEntity entity) {
        LocationEntity location = entity.getLocation();
        ProductEntity product = entity.getProduct();
        ColorEntity color = entity.getColor();
        int current = safeInt(entity.getCurrentStock());
        int minimum = safeInt(entity.getMinimumStock());
        return KioscoStockResponse.builder()
                .id(entity.getId())
                .locationId(entity.getLocationId())
                .locationCode(location != null ? location.getCode() : null)
                .locationName(location != null ? location.getName() : null)
                .productId(entity.getProductId())
                .productCode(product != null ? product.getCode() : null)
                .productName(product != null ? product.getName() : null)
                .colorId(entity.getColorId())
                .colorName(color != null ? color.getName() : null)
                .currentStock(current)
                .minimumStock(minimum)
                .lowStock(current <= minimum)
                .lastUpdatedAt(entity.getLastUpdatedAt())
                .build();
    }

    private KioscoMovementResponse toMovementResponse(KioscoMovementEntity entity) {
        KioscoStockEntity stock = entity.getKioscoStock();
        LocationEntity location = stock != null ? stock.getLocation() : null;
        ProductEntity product = stock != null ? stock.getProduct() : null;
        ColorEntity color = stock != null ? stock.getColor() : null;
        UserEntity user = entity.getUser();
        LocationEntity originLocation = entity.getOriginLocationId() != null
                ? locationRepository.findById(entity.getOriginLocationId()).orElse(null)
                : null;
        LocationEntity destinationLocation = entity.getDestinationLocationId() != null
                ? locationRepository.findById(entity.getDestinationLocationId()).orElse(null)
                : null;

        return KioscoMovementResponse.builder()
                .id(entity.getId())
                .kioscoStockId(entity.getKioscoStockId())
                .locationId(stock != null ? stock.getLocationId() : null)
                .locationName(location != null ? location.getName() : null)
                .productId(stock != null ? stock.getProductId() : null)
                .productCode(product != null ? product.getCode() : null)
                .productName(product != null ? product.getName() : null)
                .colorId(stock != null ? stock.getColorId() : null)
                .colorName(color != null ? color.getName() : null)
                .movementType(entity.getMovementType())
                .quantity(entity.getQuantity())
                .stockBefore(entity.getStockBefore())
                .stockAfter(entity.getStockAfter())
                .referenceId(entity.getReferenceId())
                .reason(entity.getReason())
                .affectsStock(entity.getAffectsStock())
                .userId(entity.getUserId())
                .username(user != null ? user.getUsername() : null)
                .originLocationId(entity.getOriginLocationId())
                .originLocationName(originLocation != null ? originLocation.getName() : null)
                .destinationLocationId(entity.getDestinationLocationId())
                .destinationLocationName(destinationLocation != null ? destinationLocation.getName() : null)
                .createdAt(entity.getCreatedAt())
                .build();
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class TrasladoResult {
        private Long referenceId;
        private KioscoStockResponse originStock;
        private KioscoStockResponse destinationStock;
    }
}
