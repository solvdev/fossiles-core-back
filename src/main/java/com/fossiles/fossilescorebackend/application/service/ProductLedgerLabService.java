package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.ProductLedgerLabMovementUpsertRequest;
import com.fossiles.fossilescorebackend.application.dto.request.ProductLedgerLabStockUpdateRequest;
import com.fossiles.fossilescorebackend.application.dto.response.ProductLedgerLabLocationResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ProductLedgerLabMovementResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ProductLedgerLabReplayAllResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ProductLedgerLabStockResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.util.ProductLedgerLabGuard;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductInventoryKardex;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductInventoryLocation;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductInventoryKardexRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductInventoryLocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.CinchoProductUtils;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductInventorySizesJson;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductLedgerLabService {

    private final ProductLedgerLabGuard guard;
    private final SecurityUtil securityUtil;
    private final ProductInventoryService productInventoryService;
    private final ProductInventoryLocationRepository stockRepository;
    private final ProductInventoryKardexRepository kardexRepository;
    private final LocationRepository locationRepository;
    private final ProductRepository productRepository;
    private final ColorRepository colorRepository;
    private final UserRepository userRepository;
    private final ProductKardexHistoricoService kardexHistoricoService;

    @Transactional(readOnly = true)
    public List<ProductLedgerLabLocationResponse> listAllowedLocations() throws BusinessException {
        return productInventoryService.getDispatchSourceWarehouses().stream()
                .map(loc -> ProductLedgerLabLocationResponse.builder()
                        .id(loc.getId())
                        .code(loc.getCode())
                        .name(loc.getName())
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * Ubicaciones que la pantalla Inventario Productos puede consultar: las de despacho (Devoluciones y
     * Bodega PT) más la reserva de Luis Felipe cuando ya existe. La reserva es solo de consulta: las
     * operaciones que cambian movimientos o existencias siguen limitadas a las de despacho.
     */
    @Transactional(readOnly = true)
    public List<ProductLedgerLabLocationResponse> listReadableLocations() throws BusinessException {
        List<LocationEntity> locations = new ArrayList<>(productInventoryService.getDispatchSourceWarehouses());
        locationRepository.findByCode(ProductInventoryService.RESERVE_LOCATION_CODE)
                .filter(reserve -> locations.stream().noneMatch(loc -> Objects.equals(loc.getId(), reserve.getId())))
                .ifPresent(locations::add);
        return locations.stream()
                .map(loc -> ProductLedgerLabLocationResponse.builder()
                        .id(loc.getId())
                        .code(loc.getCode())
                        .name(loc.getName())
                        .build())
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<ProductLedgerLabStockResponse> listStocks(
            Long locationId,
            String productTerm,
            Long productId,
            Long colorId,
            Long stockId
    ) throws BusinessException, ResourceNotFoundException {
        if (locationId == null && stockId == null) {
            throw new BusinessException("Indica locationId o stockId.");
        }

        List<ProductInventoryLocation> stocks;
        if (stockId != null) {
            ProductInventoryLocation one = stockRepository.findById(stockId)
                    .orElseThrow(() -> new ResourceNotFoundException("ProductInventoryLocation", stockId));
            requireReadableLocation(one.getLocationId());
            stocks = List.of(one);
        } else {
            requireReadableLocation(locationId);
            stocks = stockRepository.findByLocationId(locationId);
        }

        String term = normalizeTerm(productTerm);
        List<ProductLedgerLabStockResponse> out = new ArrayList<>();
        for (ProductInventoryLocation stock : stocks) {
            if (productId != null && !Objects.equals(stock.getProductId(), productId)) {
                continue;
            }
            if (colorId != null && !Objects.equals(stock.getColorId(), colorId)) {
                continue;
            }
            ProductEntity product = resolveProduct(stock);
            ColorEntity color = resolveColor(stock);
            LocationEntity location = resolveLocation(stock);
            if (term != null) {
                String haystack = ((product != null ? nullToEmpty(product.getCode()) : "")
                        + " "
                        + (product != null ? nullToEmpty(product.getName()) : "")).toLowerCase(Locale.ROOT);
                if (!haystack.contains(term)) {
                    continue;
                }
            }
            out.add(toStockResponse(stock, product, color, location, -1));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<ProductLedgerLabMovementResponse> listMovements(
            Long locationId,
            Long stockId,
            Long productId,
            Long colorId,
            String type,
            LocalDate from,
            LocalDate to,
            Long referenceId,
            String referenceTerm,
            String descriptionContains,
            String sizeLabel,
            Long movementId
    ) throws BusinessException, ResourceNotFoundException {
        return listMovements(locationId, stockId, productId, colorId, type, from, to, referenceId,
                referenceTerm, descriptionContains, sizeLabel, movementId, false);
    }

    /**
     * @param includeArchived también los movimientos archivados por limpiezas de bodega (con su línea de
     *                        corte), solo para mostrar: no se pueden editar y no cambian el stock.
     */
    @Transactional(readOnly = true)
    public List<ProductLedgerLabMovementResponse> listMovements(
            Long locationId,
            Long stockId,
            Long productId,
            Long colorId,
            String type,
            LocalDate from,
            LocalDate to,
            Long referenceId,
            String referenceTerm,
            String descriptionContains,
            String sizeLabel,
            Long movementId,
            boolean includeArchived
    ) throws BusinessException, ResourceNotFoundException {
        if (movementId != null) {
            ProductInventoryKardex one = kardexRepository.findById(movementId)
                    .orElseThrow(() -> new ResourceNotFoundException("ProductInventoryKardex", movementId));
            requireReadableLocation(one.getLocationId());
            ProductLedgerLabMovementResponse dto = toLabMovement(one);
            applySizeLevelStock(List.of(dto), loadKardexAscForKeys(
                    one.getProductId(), one.getLocationId(), one.getColorId()));
            return List.of(dto);
        }
        if (stockId == null && locationId == null) {
            throw new BusinessException("Indica locationId o stockId.");
        }

        List<ProductInventoryKardex> raw;
        ProductInventoryLocation stockFilter = null;
        if (stockId != null) {
            stockFilter = stockRepository.findById(stockId)
                    .orElseThrow(() -> new ResourceNotFoundException("ProductInventoryLocation", stockId));
            requireReadableLocation(stockFilter.getLocationId());
            raw = kardexRepository.findByProductLocationColorDesc(
                    stockFilter.getProductId(), stockFilter.getLocationId(), stockFilter.getColorId());
        } else {
            requireReadableLocation(locationId);
            raw = kardexRepository.findByLocationIdOrderByMovementDateDescIdDesc(locationId);
        }

        LocalDateTime fromDt = from != null ? from.atStartOfDay() : null;
        LocalDateTime toDtExclusive = to != null ? to.plusDays(1).atStartOfDay() : null;
        String descTerm = normalizeTerm(descriptionContains);
        String refTerm = normalizeTerm(referenceTerm);
        String sizeNorm = sizeLabel != null ? ProductInventorySizesJson.normalizeKey(sizeLabel) : null;
        String typeNorm = type != null ? type.trim().toUpperCase(Locale.ROOT) : null;

        List<ProductInventoryKardex> archived = List.of();
        if (includeArchived) {
            archived = stockFilter != null
                    ? kardexHistoricoService.archivedAsEntities(stockFilter.getProductId(), stockFilter.getLocationId())
                    : kardexHistoricoService.archivedAsEntities(productId, locationId);
            if (stockFilter != null) {
                Long stockColor = stockFilter.getColorId();
                archived = archived.stream()
                        .filter(k -> Objects.equals(k.getColorId(), stockColor))
                        .collect(Collectors.toList());
            }
        }
        Set<ProductInventoryKardex> archivedSet = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        archivedSet.addAll(archived);
        ArchivedLookups lookups = archived.isEmpty() ? null : new ArchivedLookups(archived);
        List<ProductInventoryKardex> combined = archived.isEmpty() ? raw : new ArrayList<>(raw);
        if (!archived.isEmpty()) {
            combined.addAll(archived);
        }

        List<ProductLedgerLabMovementResponse> out = new ArrayList<>();
        for (ProductInventoryKardex m : combined) {
            if (productId != null && !Objects.equals(m.getProductId(), productId)) {
                continue;
            }
            if (colorId != null && !Objects.equals(m.getColorId(), colorId)) {
                continue;
            }
            if (typeNorm != null && !typeNorm.isEmpty()
                    && (m.getMovementType() == null
                    || !typeNorm.equalsIgnoreCase(m.getMovementType().trim()))) {
                continue;
            }
            if (referenceId != null && !Objects.equals(m.getReferenceId(), referenceId)) {
                continue;
            }
            if (sizeNorm != null && !sizeNorm.isEmpty()) {
                String mk = ProductInventorySizesJson.normalizeKey(m.getSizeLabel());
                if (!sizeNorm.equals(mk)) {
                    continue;
                }
            }
            if (fromDt != null && (m.getMovementDate() == null || m.getMovementDate().isBefore(fromDt))) {
                continue;
            }
            if (toDtExclusive != null
                    && (m.getMovementDate() == null || !m.getMovementDate().isBefore(toDtExclusive))) {
                continue;
            }
            if (descTerm != null) {
                String desc = nullToEmpty(m.getDescription()).toLowerCase(Locale.ROOT);
                if (!desc.contains(descTerm)) {
                    continue;
                }
            }
            ProductLedgerLabMovementResponse dto = archivedSet.contains(m)
                    ? lookups.toLabMovement(m)
                    : toLabMovement(m);
            if (refTerm != null) {
                String hay = (nullToEmpty(dto.getReferenceNumber())
                        + " "
                        + nullToEmpty(dto.getReferenceType())
                        + " "
                        + nullToEmpty(dto.getDescription())).toLowerCase(Locale.ROOT);
                if (!hay.contains(refTerm)) {
                    continue;
                }
            }
            out.add(dto);
        }

        if (stockFilter != null) {
            applySizeLevelStock(out, loadKardexAscForKeys(
                    stockFilter.getProductId(), stockFilter.getLocationId(), stockFilter.getColorId()));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public ProductLedgerLabMovementResponse getMovement(Long id)
            throws BusinessException, ResourceNotFoundException {
        guard.requireEramirez();
        ProductInventoryKardex entity = kardexRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ProductInventoryKardex", id));
        requireAllowedLocation(entity.getLocationId());
        ProductLedgerLabMovementResponse dto = toLabMovement(entity);
        applySizeLevelStock(List.of(dto), loadKardexAscForKeys(
                entity.getProductId(), entity.getLocationId(), entity.getColorId()));
        return dto;
    }

    @Transactional
    public ProductLedgerLabMovementResponse createMovement(ProductLedgerLabMovementUpsertRequest request)
            throws BusinessException, ResourceNotFoundException {
        String actor = guard.requireEramirezUsername();
        validateUpsert(request, true);
        ProductInventoryLocation stock = stockRepository.findById(request.getStockId())
                .orElseThrow(() -> new ResourceNotFoundException("ProductInventoryLocation", request.getStockId()));
        requireAllowedLocation(stock.getLocationId());
        validateSizeLabelForStock(stock, blankToNull(request.getSizeLabel()));

        Long createdBy = request.getCreatedBy() != null
                ? request.getCreatedBy()
                : securityUtil.getCurrentUserId();

        BigDecimal qty = request.getQuantity();
        BigDecimal before = request.getQuantityBefore() != null
                ? request.getQuantityBefore()
                : nullToZero(stock.getQuantity());
        BigDecimal after = request.getQuantityAfter() != null
                ? request.getQuantityAfter()
                : before.add(qty);

        ProductInventoryKardex entity = ProductInventoryKardex.builder()
                .productId(stock.getProductId())
                .locationId(stock.getLocationId())
                .colorId(stock.getColorId())
                .sizeLabel(blankToNull(ProductInventorySizesJson.normalizeKey(request.getSizeLabel())))
                .movementType(request.getMovementType().trim().toUpperCase(Locale.ROOT))
                .quantity(qty)
                .quantityBefore(before)
                .quantityAfter(after)
                .unitCost(request.getUnitCost())
                .totalCost(request.getTotalCost())
                .referenceType(blankToNull(request.getReferenceType()))
                .referenceId(request.getReferenceId())
                .referenceNumber(blankToNull(request.getReferenceNumber()))
                .referenceLineId(request.getReferenceLineId())
                .description(blankToNull(request.getDescription()))
                .movementDate(request.getMovementDate() != null
                        ? request.getMovementDate()
                        : GuatemalaDateTime.now())
                .createdBy(createdBy)
                .build();

        entity = kardexRepository.save(entity);
        replayStockQuietly(stock.getId());
        log.warn("PRODUCT_LEDGER_LAB_CREATE actor={} movementId={} stockId={} type={} qty={}",
                actor, entity.getId(), stock.getId(), entity.getMovementType(), qty);
        return getMovement(entity.getId());
    }

    @Transactional
    public ProductLedgerLabMovementResponse updateMovement(Long id, ProductLedgerLabMovementUpsertRequest request)
            throws BusinessException, ResourceNotFoundException {
        String actor = guard.requireEramirezUsername();
        ProductInventoryKardex existing = kardexRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ProductInventoryKardex", id));
        requireAllowedLocation(existing.getLocationId());
        validateUpsert(request, false);

        Long previousStockId = findStockForKardex(existing)
                .map(ProductInventoryLocation::getId)
                .orElse(null);

        final Long requestedStockId = request.getStockId();
        ProductInventoryLocation stock;
        if (requestedStockId != null) {
            stock = stockRepository.findById(requestedStockId)
                    .orElseThrow(() -> new ResourceNotFoundException("ProductInventoryLocation", requestedStockId));
            requireAllowedLocation(stock.getLocationId());
        } else {
            stock = findStockForKardex(existing)
                    .orElseThrow(() -> new BusinessException(
                            "No hay product_inventory_location para product="
                                    + existing.getProductId() + " location=" + existing.getLocationId()
                                    + " color=" + existing.getColorId()));
        }
        Long stockId = stock.getId();

        String type = request.getMovementType() != null
                ? request.getMovementType().trim().toUpperCase(Locale.ROOT)
                : existing.getMovementType();
        BigDecimal qty = request.getQuantity() != null ? request.getQuantity() : existing.getQuantity();
        if (qty == null || qty.compareTo(BigDecimal.ZERO) == 0) {
            throw new BusinessException("quantity no puede ser 0.");
        }

        String sizeLabel = request.getSizeLabel() != null
                ? blankToNull(ProductInventorySizesJson.normalizeKey(request.getSizeLabel()))
                : existing.getSizeLabel();
        validateSizeLabelForStock(stock, sizeLabel);

        existing.setProductId(stock.getProductId());
        existing.setLocationId(stock.getLocationId());
        existing.setColorId(stock.getColorId());
        existing.setMovementType(type);
        existing.setQuantity(qty);
        existing.setSizeLabel(sizeLabel);
        if (request.getQuantityBefore() != null) {
            existing.setQuantityBefore(request.getQuantityBefore());
        }
        if (request.getQuantityAfter() != null) {
            existing.setQuantityAfter(request.getQuantityAfter());
        }
        if (request.getUnitCost() != null) {
            existing.setUnitCost(request.getUnitCost());
        }
        if (request.getTotalCost() != null) {
            existing.setTotalCost(request.getTotalCost());
        }
        if (request.getReferenceType() != null) {
            existing.setReferenceType(blankToNull(request.getReferenceType()));
        }
        if (request.getReferenceId() != null) {
            existing.setReferenceId(request.getReferenceId());
        }
        if (request.getReferenceNumber() != null) {
            existing.setReferenceNumber(blankToNull(request.getReferenceNumber()));
        }
        if (request.getReferenceLineId() != null) {
            existing.setReferenceLineId(request.getReferenceLineId());
        }
        if (request.getDescription() != null) {
            existing.setDescription(blankToNull(request.getDescription()));
        }
        if (request.getMovementDate() != null) {
            existing.setMovementDate(request.getMovementDate());
        }
        if (request.getCreatedBy() != null) {
            existing.setCreatedBy(request.getCreatedBy());
        }

        kardexRepository.save(existing);
        replayStockQuietly(stockId);
        if (previousStockId != null && !Objects.equals(previousStockId, stockId)) {
            replayStockQuietly(previousStockId);
        }

        log.warn("PRODUCT_LEDGER_LAB_UPDATE actor={} movementId={} stockId={} type={} qty={}",
                actor, id, stockId, type, qty);
        return getMovement(id);
    }

    @Transactional
    public void deleteMovement(Long id) throws BusinessException, ResourceNotFoundException {
        String actor = guard.requireEramirezUsername();
        ProductInventoryKardex existing = kardexRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ProductInventoryKardex", id));
        requireAllowedLocation(existing.getLocationId());
        Long stockId = findStockForKardex(existing).map(ProductInventoryLocation::getId).orElse(null);
        kardexRepository.delete(existing);
        if (stockId != null) {
            replayStockQuietly(stockId);
        }
        log.warn("PRODUCT_LEDGER_LAB_DELETE actor={} movementId={} stockId={}", actor, id, stockId);
    }

    @Transactional
    public ProductLedgerLabStockResponse updateStock(Long stockId, ProductLedgerLabStockUpdateRequest request)
            throws BusinessException, ResourceNotFoundException {
        String actor = guard.requireEramirezUsername();
        ProductInventoryLocation stock = stockRepository.findById(stockId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductInventoryLocation", stockId));
        requireAllowedLocation(stock.getLocationId());
        if (request == null) {
            throw new BusinessException("Body requerido.");
        }
        if (request.getQuantity() != null) {
            stock.setQuantity(request.getQuantity());
        }
        if (request.getMin() != null) {
            stock.setMin(request.getMin());
        }
        if (request.getSizesData() != null) {
            stock.setSizesData(request.getSizesData().isBlank() ? null : request.getSizesData().trim());
        }
        stock.setUpdatedBy(securityUtil.getCurrentUserId());
        stock = stockRepository.save(stock);
        log.warn("PRODUCT_LEDGER_LAB_STOCK_UPDATE actor={} stockId={} quantity={}",
                actor, stockId, stock.getQuantity());
        return toStockResponse(stock, resolveProduct(stock), resolveColor(stock), resolveLocation(stock), null);
    }

    @Transactional
    public ProductLedgerLabStockResponse replayStock(Long stockId)
            throws BusinessException, ResourceNotFoundException {
        String actor = guard.requireEramirezUsername();
        ProductInventoryLocation stock = stockRepository.findById(stockId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductInventoryLocation", stockId));
        requireAllowedLocation(stock.getLocationId());
        doReplay(stock);
        log.warn("PRODUCT_LEDGER_LAB_REPLAY actor={} stockId={}", actor, stockId);
        stock = stockRepository.findById(stockId).orElseThrow();
        return toStockResponse(stock, resolveProduct(stock), resolveColor(stock), resolveLocation(stock), null);
    }

    @Transactional
    public ProductLedgerLabReplayAllResponse replayAllStocks(Long locationId)
            throws BusinessException, ResourceNotFoundException {
        String actor = guard.requireEramirezUsername();
        if (locationId == null) {
            throw new BusinessException("Indica locationId.");
        }
        requireAllowedLocation(locationId);
        if (!locationRepository.existsById(locationId)) {
            throw new ResourceNotFoundException("Location", locationId);
        }
        List<ProductInventoryLocation> stocks = stockRepository.findByLocationId(locationId);
        int stockCount = 0;
        for (ProductInventoryLocation stock : stocks) {
            doReplay(stock);
            stockCount++;
        }
        log.warn("PRODUCT_LEDGER_LAB_REPLAY_ALL actor={} locationId={} stockCount={}",
                actor, locationId, stockCount);
        return ProductLedgerLabReplayAllResponse.builder()
                .locationId(locationId)
                .stockCount(stockCount)
                .build();
    }

    /**
     * Recalcula quantity + sizes_data desde kardex y reescribe quantity_before/after.
     */
    private void doReplay(ProductInventoryLocation stock) {
        if (stock == null) {
            return;
        }
        List<ProductInventoryKardex> movements = loadKardexAscForKeys(
                stock.getProductId(), stock.getLocationId(), stock.getColorId());

        BigDecimal running = BigDecimal.ZERO;
        Map<String, BigDecimal> bySize = new LinkedHashMap<>();
        boolean anySized = false;

        for (ProductInventoryKardex movement : movements) {
            BigDecimal delta = nullToZero(movement.getQuantity());
            BigDecimal before = running;
            BigDecimal after = running.add(delta);
            running = after;

            movement.setQuantityBefore(before);
            movement.setQuantityAfter(after);
            kardexRepository.save(movement);

            String sizeKey = ProductInventorySizesJson.normalizeKey(movement.getSizeLabel());
            if (!sizeKey.isEmpty()) {
                anySized = true;
                bySize.merge(sizeKey, delta, BigDecimal::add);
            }
        }

        stock.setQuantity(running);
        if (anySized) {
            Map<String, BigDecimal> cleaned = new LinkedHashMap<>();
            for (Map.Entry<String, BigDecimal> e : bySize.entrySet()) {
                if (e.getValue() != null && e.getValue().compareTo(BigDecimal.ZERO) != 0) {
                    cleaned.put(e.getKey(), e.getValue());
                }
            }
            stock.setSizesData(cleaned.isEmpty() ? null : ProductInventorySizesJson.serialize(cleaned));
        } else {
            stock.setSizesData(null);
        }
        stock.setUpdatedBy(securityUtil.getCurrentUserId());
        stockRepository.save(stock);
    }

    private void replayStockQuietly(Long stockId) {
        if (stockId == null) {
            return;
        }
        stockRepository.findById(stockId).ifPresent(this::doReplay);
    }

    private void validateUpsert(ProductLedgerLabMovementUpsertRequest request, boolean creating)
            throws BusinessException {
        if (request == null) {
            throw new BusinessException("Body requerido.");
        }
        if (creating) {
            if (request.getStockId() == null) {
                throw new BusinessException("stockId es obligatorio.");
            }
            if (request.getMovementType() == null || request.getMovementType().isBlank()) {
                throw new BusinessException("movementType es obligatorio.");
            }
            if (request.getQuantity() == null) {
                throw new BusinessException("quantity es obligatorio.");
            }
            if (request.getQuantity().compareTo(BigDecimal.ZERO) == 0) {
                throw new BusinessException("quantity no puede ser 0.");
            }
        } else if (request.getQuantity() != null
                && request.getQuantity().compareTo(BigDecimal.ZERO) == 0) {
            throw new BusinessException("quantity no puede ser 0.");
        }
    }

    private void validateSizeLabelForStock(ProductInventoryLocation stock, String sizeLabel)
            throws BusinessException {
        if (stock == null) {
            return;
        }
        if (!ProductInventorySizesJson.normalizeKey(sizeLabel).isEmpty()) {
            return;
        }
        ProductEntity product = resolveProduct(stock);
        boolean cinchoRequiresSize = CinchoProductUtils.isCinchoLineForProduction(product);
        boolean hasBreakdown = ProductInventorySizesJson.hasNonEmptyBreakdown(stock.getSizesData());
        if (cinchoRequiresSize || hasBreakdown) {
            throw new BusinessException(
                    "Indique la talla (sizeLabel) para movimientos de cincho / stock con tallas.");
        }
    }

    private void requireAllowedLocation(Long locationId) throws BusinessException {
        if (locationId == null) {
            throw new BusinessException("locationId requerido.");
        }
        Set<Long> allowed = allowedLocationIds();
        if (!allowed.contains(locationId)) {
            throw new BusinessException(
                    "Ubicación no permitida (solo Bodega PT y Devoluciones).");
        }
    }

    /** Lectura: las ubicaciones de despacho y la reserva de Luis Felipe. */
    private void requireReadableLocation(Long locationId) throws BusinessException {
        if (locationId == null) {
            throw new BusinessException("locationId requerido.");
        }
        if (allowedLocationIds().contains(locationId)) {
            return;
        }
        boolean isReserve = locationRepository.findByCode(ProductInventoryService.RESERVE_LOCATION_CODE)
                .map(reserve -> Objects.equals(reserve.getId(), locationId))
                .orElse(false);
        if (!isReserve) {
            throw new BusinessException(
                    "Ubicación no permitida (solo Bodega PT, Devoluciones y la reserva de Luis Felipe).");
        }
    }

    private Set<Long> allowedLocationIds() throws BusinessException {
        return productInventoryService.getDispatchSourceWarehouses().stream()
                .map(LocationEntity::getId)
                .collect(Collectors.toCollection(HashSet::new));
    }

    private List<ProductInventoryKardex> loadKardexAscForKeys(Long productId, Long locationId, Long colorId) {
        return kardexRepository.findByProductLocationColorAsc(productId, locationId, colorId);
    }

    private java.util.Optional<ProductInventoryLocation> findStockForKardex(ProductInventoryKardex k) {
        if (k == null) {
            return java.util.Optional.empty();
        }
        return findStockForKardexKeys(k.getProductId(), k.getLocationId(), k.getColorId());
    }

    private java.util.Optional<ProductInventoryLocation> findStockForKardexKeys(
            Long productId, Long locationId, Long colorId) {
        if (productId == null || locationId == null) {
            return java.util.Optional.empty();
        }
        return stockRepository.findByProductIdAndLocationIdAndColorId(productId, locationId, colorId);
    }

    private void applySizeLevelStock(
            List<ProductLedgerLabMovementResponse> dtos,
            List<ProductInventoryKardex> chronological
    ) {
        if (dtos == null || dtos.isEmpty() || chronological == null) {
            return;
        }
        Map<String, BigDecimal> runningBySize = new HashMap<>();
        Map<Long, BigDecimal[]> sizeBeforeAfter = new HashMap<>();
        for (ProductInventoryKardex m : chronological) {
            String sizeKey = ProductInventorySizesJson.normalizeKey(m.getSizeLabel());
            if (sizeKey.isEmpty()) {
                continue;
            }
            BigDecimal before = runningBySize.getOrDefault(sizeKey, BigDecimal.ZERO);
            BigDecimal after = before.add(nullToZero(m.getQuantity()));
            runningBySize.put(sizeKey, after);
            sizeBeforeAfter.put(m.getId(), new BigDecimal[]{before, after});
        }
        for (ProductLedgerLabMovementResponse dto : dtos) {
            BigDecimal[] pair = sizeBeforeAfter.get(dto.getId());
            if (pair != null) {
                dto.setSizeStockBefore(pair[0]);
                dto.setSizeStockAfter(pair[1]);
            }
        }
    }

    /**
     * Nombres, stock y usuarios cargados de una vez para los movimientos archivados (pueden ser miles
     * en Bodega PT); el mapeo normal hace varias consultas por fila.
     */
    private final class ArchivedLookups {
        private final Map<Long, ProductEntity> products = new HashMap<>();
        private final Map<Long, LocationEntity> locations = new HashMap<>();
        private final Map<Long, ColorEntity> colors = new HashMap<>();
        private final Map<String, Long> stockIds = new HashMap<>();
        private final Map<Long, String> usernames = new HashMap<>();

        ArchivedLookups(List<ProductInventoryKardex> rows) {
            Set<Long> productIds = new HashSet<>();
            Set<Long> locationIds = new HashSet<>();
            Set<Long> colorIds = new HashSet<>();
            Set<Long> userIds = new HashSet<>();
            for (ProductInventoryKardex k : rows) {
                if (k.getProductId() != null) productIds.add(k.getProductId());
                if (k.getLocationId() != null) locationIds.add(k.getLocationId());
                if (k.getColorId() != null) colorIds.add(k.getColorId());
                if (k.getCreatedBy() != null) userIds.add(k.getCreatedBy());
            }
            productRepository.findAllById(productIds).forEach(p -> products.put(p.getId(), p));
            locationRepository.findAllById(locationIds).forEach(l -> locations.put(l.getId(), l));
            colorRepository.findAllById(colorIds).forEach(c -> colors.put(c.getId(), c));
            userRepository.findAllById(userIds).forEach(u -> usernames.put(u.getId(), u.getUsername()));
            for (Long locId : locationIds) {
                for (ProductInventoryLocation s : stockRepository.findByLocationId(locId)) {
                    stockIds.putIfAbsent(stockKey(s.getProductId(), s.getLocationId(), s.getColorId()), s.getId());
                }
            }
        }

        ProductLedgerLabMovementResponse toLabMovement(ProductInventoryKardex k) {
            ProductEntity product = products.get(k.getProductId());
            LocationEntity location = locations.get(k.getLocationId());
            ColorEntity color = k.getColorId() != null ? colors.get(k.getColorId()) : null;
            return ProductLedgerLabMovementResponse.builder()
                    .id(k.getId())
                    .stockId(stockIds.get(stockKey(k.getProductId(), k.getLocationId(), k.getColorId())))
                    .locationId(k.getLocationId())
                    .locationCode(location != null ? location.getCode() : null)
                    .locationName(location != null ? location.getName() : null)
                    .productId(k.getProductId())
                    .productCode(product != null ? product.getCode() : null)
                    .productName(product != null ? product.getName() : null)
                    .colorId(k.getColorId())
                    .colorName(color != null ? color.getName() : null)
                    .movementType(k.getMovementType())
                    .quantity(k.getQuantity())
                    .sizeLabel(k.getSizeLabel())
                    .quantityBefore(k.getQuantityBefore())
                    .quantityAfter(k.getQuantityAfter())
                    .unitCost(k.getUnitCost())
                    .totalCost(k.getTotalCost())
                    .referenceType(k.getReferenceType())
                    .referenceId(k.getReferenceId())
                    .referenceNumber(k.getReferenceNumber())
                    .referenceLineId(k.getReferenceLineId())
                    .description(k.getDescription())
                    .movementDate(k.getMovementDate())
                    .createdAt(k.getCreatedAt())
                    .createdBy(k.getCreatedBy())
                    .username(k.getCreatedBy() != null ? usernames.get(k.getCreatedBy()) : null)
                    .build();
        }

        private String stockKey(Long productId, Long locationId, Long colorId) {
            return productId + "|" + locationId + "|" + colorId;
        }
    }

    private ProductLedgerLabMovementResponse toLabMovement(ProductInventoryKardex entity) {
        ProductEntity product = entity.getProduct() != null
                ? entity.getProduct()
                : (entity.getProductId() != null
                ? productRepository.findById(entity.getProductId()).orElse(null)
                : null);
        LocationEntity location = entity.getLocation() != null
                ? entity.getLocation()
                : (entity.getLocationId() != null
                ? locationRepository.findById(entity.getLocationId()).orElse(null)
                : null);
        ColorEntity color = entity.getColorId() != null
                ? colorRepository.findById(entity.getColorId()).orElse(null)
                : null;
        Long stockId = findStockForKardex(entity).map(ProductInventoryLocation::getId).orElse(null);
        String username = null;
        if (entity.getCreatedBy() != null) {
            username = userRepository.findById(entity.getCreatedBy())
                    .map(UserEntity::getUsername)
                    .orElse(null);
        }
        return ProductLedgerLabMovementResponse.builder()
                .id(entity.getId())
                .stockId(stockId)
                .locationId(entity.getLocationId())
                .locationCode(location != null ? location.getCode() : null)
                .locationName(location != null ? location.getName() : null)
                .productId(entity.getProductId())
                .productCode(product != null ? product.getCode() : null)
                .productName(product != null ? product.getName() : null)
                .colorId(entity.getColorId())
                .colorName(color != null ? color.getName() : null)
                .movementType(entity.getMovementType())
                .quantity(entity.getQuantity())
                .sizeLabel(entity.getSizeLabel())
                .quantityBefore(entity.getQuantityBefore())
                .quantityAfter(entity.getQuantityAfter())
                .unitCost(entity.getUnitCost())
                .totalCost(entity.getTotalCost())
                .referenceType(entity.getReferenceType())
                .referenceId(entity.getReferenceId())
                .referenceNumber(entity.getReferenceNumber())
                .referenceLineId(entity.getReferenceLineId())
                .description(entity.getDescription())
                .movementDate(entity.getMovementDate())
                .createdAt(entity.getCreatedAt())
                .createdBy(entity.getCreatedBy())
                .username(username)
                .build();
    }

    private ProductLedgerLabStockResponse toStockResponse(
            ProductInventoryLocation stock,
            ProductEntity product,
            ColorEntity color,
            LocationEntity location,
            Integer movementCount
    ) {
        Map<String, BigDecimal> sizes = ProductInventorySizesJson.parse(stock.getSizesData());
        Integer count = movementCount;
        if (count == null) {
            count = (int) kardexRepository.countByProductLocationColor(
                    stock.getProductId(), stock.getLocationId(), stock.getColorId());
        } else if (count < 0) {
            count = null;
        }
        return ProductLedgerLabStockResponse.builder()
                .id(stock.getId())
                .locationId(stock.getLocationId())
                .locationCode(location != null ? location.getCode() : null)
                .locationName(location != null ? location.getName() : null)
                .productId(stock.getProductId())
                .productCode(product != null ? product.getCode() : null)
                .productName(product != null ? product.getName() : null)
                .colorId(stock.getColorId())
                .colorName(color != null ? color.getName() : null)
                .quantity(stock.getQuantity())
                .min(stock.getMin())
                .sizesData(stock.getSizesData())
                .sizes(sizes.isEmpty() ? null : sizes)
                .movementCount(count)
                .updatedAt(stock.getUpdatedAt())
                .build();
    }

    private ProductEntity resolveProduct(ProductInventoryLocation stock) {
        if (stock.getProduct() != null) {
            return stock.getProduct();
        }
        return stock.getProductId() != null
                ? productRepository.findById(stock.getProductId()).orElse(null)
                : null;
    }

    private ColorEntity resolveColor(ProductInventoryLocation stock) {
        if (stock.getColor() != null) {
            return stock.getColor();
        }
        return stock.getColorId() != null
                ? colorRepository.findById(stock.getColorId()).orElse(null)
                : null;
    }

    private LocationEntity resolveLocation(ProductInventoryLocation stock) {
        if (stock.getLocation() != null) {
            return stock.getLocation();
        }
        return stock.getLocationId() != null
                ? locationRepository.findById(stock.getLocationId()).orElse(null)
                : null;
    }

    private static String normalizeTerm(String value) {
        if (value == null) {
            return null;
        }
        String t = value.trim().toLowerCase(Locale.ROOT);
        return t.isEmpty() ? null : t;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }

    private static BigDecimal nullToZero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
