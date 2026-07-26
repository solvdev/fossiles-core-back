package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.KioscoOpeningInventoryApplyRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioscoOpeningInventoryItemUpsertRequest;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoOpeningInventoryReportResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoOpeningInventoryStatusResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoOpeningInventorySummaryResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.util.KioscoInventoryInitRules;
import com.fossiles.fossilescorebackend.application.util.ProductHardwareCondition;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoMovementEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoOpeningInventoryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoOpeningInventoryItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoOpeningInventoryStatus;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoStockEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoMovementRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoOpeningInventoryItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoOpeningInventoryRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoStockRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductInventorySizesJson;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class KioscoOpeningInventoryService {

    public static final String OPENING_INVENTORY_REASON = "Inventario inicial - migración";

    private final KioscoOpeningInventoryRepository openingInventoryRepository;
    private final KioscoOpeningInventoryItemRepository openingInventoryItemRepository;
    private final KioscoInventoryService kioscoInventoryService;
    private final KioscoStockRepository kioscoStockRepository;
    private final KioscoMovementRepository kioscoMovementRepository;
    private final LocationRepository locationRepository;
    private final ProductRepository productRepository;
    private final ColorRepository colorRepository;
    private final UserRepository userRepository;
    private final SecurityUtil securityUtil;

    @Transactional
    public KioscoOpeningInventoryReportResponse startOrGetDraft(Long locationId)
            throws BusinessException, ResourceNotFoundException {
        validateLocation(locationId);
        assertNoAppliedInventory(locationId);
        KioscoOpeningInventoryEntity entity = openingInventoryRepository
                .findByLocationIdAndStatus(locationId, KioscoOpeningInventoryStatus.DRAFT)
                .orElseGet(() -> openingInventoryRepository.save(KioscoOpeningInventoryEntity.builder()
                        .locationId(locationId)
                        .status(KioscoOpeningInventoryStatus.DRAFT)
                        .createdBy(resolveCurrentUserId())
                        .build()));
        return buildReport(entity, List.of());
    }

    @Transactional(readOnly = true)
    public KioscoOpeningInventoryReportResponse getById(Long id)
            throws ResourceNotFoundException, BusinessException {
        return buildReport(findOrThrow(id), List.of());
    }

    @Transactional
    public KioscoOpeningInventoryReportResponse upsertItems(
            Long openingInventoryId,
            List<KioscoOpeningInventoryItemUpsertRequest> items
    ) throws BusinessException, ResourceNotFoundException {
        KioscoOpeningInventoryEntity session = findOrThrow(openingInventoryId);
        assertEditable(session);
        if (items == null || items.isEmpty()) {
            throw new BusinessException("Debes indicar al menos un ítem para guardar.");
        }
        Long userId = resolveCurrentUserId();
        for (KioscoOpeningInventoryItemUpsertRequest req : items) {
            validateAndUpsertItem(session, req, userId);
        }
        return buildReport(session, List.of());
    }

    @Transactional
    public KioscoOpeningInventoryReportResponse apply(
            Long openingInventoryId,
            KioscoOpeningInventoryApplyRequest request
    ) throws BusinessException, ResourceNotFoundException {
        KioscoOpeningInventoryEntity session = findOrThrow(openingInventoryId);
        assertEditable(session);
        assertNoAppliedInventory(session.getLocationId());

        List<KioscoOpeningInventoryItemEntity> items = openingInventoryItemRepository
                .findByOpeningInventoryIdOrderByProductIdAscColorIdAsc(openingInventoryId);
        if (items.isEmpty()) {
            throw new BusinessException("No hay ítems capturados para aplicar el inventario inicial.");
        }

        List<String> warnings = collectPreApplyWarnings(session.getLocationId());
        Long userId = request != null && request.getUserId() != null
                ? request.getUserId()
                : resolveCurrentUserId();

        Map<Long, ProductEntity> productsById = loadProducts(items);
        int entradasApplied = 0;
        for (KioscoOpeningInventoryItemEntity item : items) {
            ProductEntity product = productsById.get(item.getProductId());
            if (product == null) {
                throw new ResourceNotFoundException("Product", item.getProductId());
            }
            int targetQty = safeInt(item.getQuantity());
            Map<String, Integer> targetSizes = parseSizes(item.getSizesData());
            if (needsAdjustment(session.getLocationId(), item, product, targetQty, targetSizes)) {
                kioscoInventoryService.registrarInventarioInicial(
                        session.getLocationId(),
                        item.getProductId(),
                        item.getColorId(),
                        targetQty,
                        KioscoInventoryInitRules.isCinchoProduct(product) ? targetSizes : null,
                        OPENING_INVENTORY_REASON,
                        userId,
                        item.getHardwareCondition()
                );
                entradasApplied++;
            }
        }
        if (entradasApplied == 0) {
            throw new BusinessException(
                    "Ningún ítem difiere del stock actual. Revise las cantidades capturadas.");
        }

        session.setStatus(KioscoOpeningInventoryStatus.APLICADO);
        session.setAppliedBy(userId);
        session.setAppliedAt(LocalDateTime.now());
        if (request != null && request.getNotes() != null && !request.getNotes().isBlank()) {
            session.setNotes(request.getNotes().trim());
        }
        openingInventoryRepository.save(session);
        return buildReport(session, warnings);
    }

    @Transactional(readOnly = true)
    public KioscoOpeningInventoryStatusResponse getStatus(Long locationId) throws ResourceNotFoundException {
        validateLocation(locationId);
        KioscoOpeningInventoryEntity applied = openingInventoryRepository
                .findByLocationIdAndStatus(locationId, KioscoOpeningInventoryStatus.APLICADO)
                .orElse(null);
        if (applied != null) {
            int itemCount = openingInventoryItemRepository
                    .findByOpeningInventoryIdOrderByProductIdAscColorIdAsc(applied.getId()).size();
            return KioscoOpeningInventoryStatusResponse.builder()
                    .status("APLICADO")
                    .appliedId(applied.getId())
                    .appliedAt(applied.getAppliedAt())
                    .appliedByName(resolveUsername(applied.getAppliedBy()))
                    .draftItemCount(itemCount)
                    .build();
        }
        KioscoOpeningInventoryEntity draft = openingInventoryRepository
                .findByLocationIdAndStatus(locationId, KioscoOpeningInventoryStatus.DRAFT)
                .orElse(null);
        if (draft != null) {
            int itemCount = openingInventoryItemRepository
                    .findByOpeningInventoryIdOrderByProductIdAscColorIdAsc(draft.getId()).size();
            return KioscoOpeningInventoryStatusResponse.builder()
                    .status("DRAFT")
                    .draftId(draft.getId())
                    .draftItemCount(itemCount)
                    .build();
        }
        return KioscoOpeningInventoryStatusResponse.builder()
                .status("NONE")
                .draftItemCount(0)
                .build();
    }

    @Transactional(readOnly = true)
    public List<KioscoOpeningInventorySummaryResponse> listApplied(Long locationId)
            throws ResourceNotFoundException {
        if (locationId != null) {
            validateLocation(locationId);
        }
        List<KioscoOpeningInventoryEntity> sessions = locationId != null
                ? openingInventoryRepository.findByLocationIdOrderByCreatedAtDesc(locationId).stream()
                        .filter(s -> s.getStatus() == KioscoOpeningInventoryStatus.APLICADO)
                        .collect(Collectors.toList())
                : openingInventoryRepository.findAll().stream()
                        .filter(s -> s.getStatus() == KioscoOpeningInventoryStatus.APLICADO)
                        .sorted((a, b) -> {
                            LocalDateTime atA = a.getAppliedAt() != null ? a.getAppliedAt() : a.getCreatedAt();
                            LocalDateTime atB = b.getAppliedAt() != null ? b.getAppliedAt() : b.getCreatedAt();
                            return atB.compareTo(atA);
                        })
                        .collect(Collectors.toList());
        return sessions.stream().map(this::toSummary).collect(Collectors.toList());
    }

    private void validateAndUpsertItem(
            KioscoOpeningInventoryEntity session,
            KioscoOpeningInventoryItemUpsertRequest req,
            Long userId
    ) throws BusinessException, ResourceNotFoundException {
        if (req.getProductId() == null) {
            throw new BusinessException("productId es obligatorio en cada ítem.");
        }
        ProductEntity product = productRepository.findById(req.getProductId())
                .orElseThrow(() -> new ResourceNotFoundException("Product", req.getProductId()));
        boolean packaging = KioscoInventoryInitRules.isPackagingProduct(product);
        boolean foss = KioscoInventoryInitRules.isCinchoProduct(product);

        Long colorId = req.getColorId();
        String hardware = resolveItemHardware(req.getHardwareCondition());
        if (packaging) {
            if (colorId != null) {
                throw new BusinessException("Los empaques SUM- no usan color; omita colorId.");
            }
        } else if (colorId == null) {
            throw new BusinessException("colorId es obligatorio para productos con variantes por color.");
        } else if (!colorRepository.existsById(colorId)) {
            throw new ResourceNotFoundException("Color", colorId);
        }

        int quantity = req.getQuantity() != null ? req.getQuantity() : 0;
        if (quantity < 0) {
            throw new BusinessException("La cantidad no puede ser negativa.");
        }

        Map<String, Integer> sizes = req.getSizes();
        if (foss) {
            if (quantity > 0 && (sizes == null || sizes.isEmpty())) {
                throw new BusinessException(
                        "Los cinchos FOSS requieren desglose por talla (sizes).");
            }
            if (sizes != null && !sizes.isEmpty()) {
                int sizeTotal = sizes.values().stream().mapToInt(v -> v != null ? v : 0).sum();
                if (quantity != sizeTotal) {
                    throw new BusinessException(
                            "quantity debe coincidir con la suma de sizes (" + sizeTotal + ").");
                }
            }
        } else if (sizes != null && !sizes.isEmpty()) {
            throw new BusinessException("Este producto no usa desglose por talla.");
        }

        if (quantity == 0 && (sizes == null || sizes.isEmpty())) {
            openingInventoryItemRepository.deleteByOpeningInventoryIdAndProductIdAndColorIdAndHardwareCondition(
                    session.getId(), req.getProductId(), colorId, hardware);
            return;
        }

        KioscoOpeningInventoryItemEntity item = openingInventoryItemRepository
                .findByOpeningInventoryIdAndProductIdAndColorIdAndHardwareCondition(
                        session.getId(), req.getProductId(), colorId, hardware)
                .orElseGet(() -> KioscoOpeningInventoryItemEntity.builder()
                        .openingInventoryId(session.getId())
                        .productId(req.getProductId())
                        .colorId(colorId)
                        .hardwareCondition(hardware)
                        .build());
        item.setQuantity(quantity);
        item.setHardwareCondition(hardware);
        if (foss && sizes != null && !sizes.isEmpty()) {
            item.setSizesData(ProductInventorySizesJson.serializeIncludingZeros(normalizeSizes(sizes)));
        } else {
            item.setSizesData(null);
        }
        item.setUpdatedBy(userId);
        openingInventoryItemRepository.save(item);
    }

    private boolean needsAdjustment(
            Long locationId,
            KioscoOpeningInventoryItemEntity item,
            ProductEntity product,
            int targetQty,
            Map<String, Integer> targetSizes
    ) {
        List<KioscoStockEntity> stocks = kioscoStockRepository
                .findByLocationIdOrderByProductIdAscColorIdAscHardwareConditionAsc(locationId).stream()
                .filter(s -> Objects.equals(s.getProductId(), item.getProductId())
                        && Objects.equals(s.getColorId(), item.getColorId())
                        && Objects.equals(
                                ProductHardwareCondition.normalize(s.getHardwareCondition()),
                                ProductHardwareCondition.normalize(item.getHardwareCondition())))
                .collect(Collectors.toList());
        int currentQty = stocks.stream().mapToInt(s -> safeInt(s.getCurrentStock())).sum();
        if (KioscoInventoryInitRules.isCinchoProduct(product)) {
            Map<String, Integer> currentSizes = parseSizes(
                    stocks.isEmpty() ? null : stocks.get(0).getSizesData());
            return currentQty != targetQty || !sizesEqual(currentSizes, targetSizes);
        }
        return currentQty != targetQty;
    }

    private KioscoOpeningInventoryReportResponse buildReport(
            KioscoOpeningInventoryEntity session,
            List<String> warnings
    ) throws ResourceNotFoundException {
        LocationEntity location = locationRepository.findById(session.getLocationId())
                .orElseThrow(() -> new ResourceNotFoundException("Location", session.getLocationId()));
        List<KioscoOpeningInventoryItemEntity> items = openingInventoryItemRepository
                .findByOpeningInventoryIdOrderByProductIdAscColorIdAsc(session.getId());

        List<Long> productIds = items.stream().map(KioscoOpeningInventoryItemEntity::getProductId).distinct()
                .collect(Collectors.toList());
        Map<Long, ProductEntity> productsById = productRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(ProductEntity::getId, p -> p, (a, b) -> a));
        List<Long> colorIds = items.stream()
                .map(KioscoOpeningInventoryItemEntity::getColorId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, ColorEntity> colorsById = colorRepository.findAllById(colorIds).stream()
                .collect(Collectors.toMap(ColorEntity::getId, c -> c, (a, b) -> a));

        List<KioscoOpeningInventoryReportResponse.ItemRow> rows = new ArrayList<>();
        for (KioscoOpeningInventoryItemEntity item : items) {
            ProductEntity product = productsById.get(item.getProductId());
            ColorEntity color = item.getColorId() != null ? colorsById.get(item.getColorId()) : null;
            Map<String, Integer> sizes = parseSizes(item.getSizesData());
            rows.add(KioscoOpeningInventoryReportResponse.ItemRow.builder()
                    .productId(item.getProductId())
                    .productCode(product != null ? product.getCode() : null)
                    .productName(product != null ? product.getName() : null)
                    .colorId(item.getColorId())
                    .colorName(color != null ? color.getName() : null)
                    .hardwareCondition(item.getHardwareCondition())
                    .hardwareLabel(resolveHardwareLabel(item.getHardwareCondition()))
                    .quantity(item.getQuantity())
                    .sizes(sizes.isEmpty() ? null : sizes)
                    .sizesSummary(formatSizesSummary(sizes))
                    .packaging(product != null && KioscoInventoryInitRules.isPackagingProduct(product))
                    .build());
        }

        return KioscoOpeningInventoryReportResponse.builder()
                .id(session.getId())
                .locationId(session.getLocationId())
                .locationName(location.getName())
                .locationCode(location.getCode())
                .status(session.getStatus().name())
                .notes(session.getNotes())
                .createdBy(session.getCreatedBy())
                .createdByName(resolveUsername(session.getCreatedBy()))
                .appliedBy(session.getAppliedBy())
                .appliedByName(resolveUsername(session.getAppliedBy()))
                .appliedAt(session.getAppliedAt())
                .createdAt(session.getCreatedAt())
                .updatedAt(session.getUpdatedAt())
                .itemCount(rows.size())
                .items(rows)
                .warnings(warnings != null && !warnings.isEmpty() ? warnings : null)
                .build();
    }

    private List<String> collectPreApplyWarnings(Long locationId) {
        List<String> warnings = new ArrayList<>();
        long foreignMovements = kioscoMovementRepository.findByLocationIdOrderByCreatedAtDesc(locationId).stream()
                .filter(m -> !isOpeningInventoryMovement(m))
                .count();
        if (foreignMovements > 0) {
            warnings.add("Este kiosko ya tiene "
                    + foreignMovements
                    + " movimiento(s) distintos del inventario inicial. Revise antes de aplicar.");
        }
        return warnings;
    }

    private boolean isOpeningInventoryMovement(KioscoMovementEntity movement) {
        String reason = movement.getReason();
        return reason != null && reason.contains(OPENING_INVENTORY_REASON);
    }

    private Map<Long, ProductEntity> loadProducts(List<KioscoOpeningInventoryItemEntity> items) {
        List<Long> productIds = items.stream()
                .map(KioscoOpeningInventoryItemEntity::getProductId)
                .distinct()
                .collect(Collectors.toList());
        return productRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(ProductEntity::getId, p -> p, (a, b) -> a));
    }

    private KioscoOpeningInventorySummaryResponse toSummary(KioscoOpeningInventoryEntity entity) {
        int itemCount = openingInventoryItemRepository
                .findByOpeningInventoryIdOrderByProductIdAscColorIdAsc(entity.getId()).size();
        return KioscoOpeningInventorySummaryResponse.builder()
                .id(entity.getId())
                .locationId(entity.getLocationId())
                .status(entity.getStatus().name())
                .notes(entity.getNotes())
                .createdBy(entity.getCreatedBy())
                .createdByName(resolveUsername(entity.getCreatedBy()))
                .appliedBy(entity.getAppliedBy())
                .appliedByName(resolveUsername(entity.getAppliedBy()))
                .appliedAt(entity.getAppliedAt())
                .createdAt(entity.getCreatedAt())
                .itemCount(itemCount)
                .build();
    }

    private KioscoOpeningInventoryEntity findOrThrow(Long id) throws ResourceNotFoundException {
        return openingInventoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("KioscoOpeningInventory", id));
    }

    private void assertEditable(KioscoOpeningInventoryEntity session) throws BusinessException {
        if (session.getStatus() == KioscoOpeningInventoryStatus.APLICADO) {
            throw new BusinessException("Este inventario inicial ya fue aplicado y no se puede modificar.");
        }
    }

    private void assertNoAppliedInventory(Long locationId) throws BusinessException {
        if (openingInventoryRepository.existsByLocationIdAndStatus(
                locationId, KioscoOpeningInventoryStatus.APLICADO)) {
            throw new BusinessException(
                    "Este kiosko ya tiene un inventario inicial aplicado. No se puede iniciar otro.");
        }
    }

    private void validateLocation(Long locationId) throws ResourceNotFoundException {
        if (!locationRepository.existsById(locationId)) {
            throw new ResourceNotFoundException("Location", locationId);
        }
    }

    private Map<String, BigDecimal> normalizeSizes(Map<String, Integer> raw) throws BusinessException {
        Map<String, BigDecimal> normalized = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return normalized;
        }
        for (Map.Entry<String, Integer> entry : raw.entrySet()) {
            String key = ProductInventorySizesJson.normalizeKey(entry.getKey());
            if (key.isEmpty()) {
                continue;
            }
            int value = entry.getValue() != null ? entry.getValue() : 0;
            if (value < 0) {
                throw new BusinessException("La cantidad de talla " + key + " no puede ser negativa.");
            }
            normalized.put(key, BigDecimal.valueOf(value));
        }
        return normalized;
    }

    private Map<String, Integer> parseSizes(String json) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> e : ProductInventorySizesJson.parse(json).entrySet()) {
            out.put(e.getKey(), e.getValue() != null ? e.getValue().intValue() : 0);
        }
        return out;
    }

    private boolean sizesEqual(Map<String, Integer> left, Map<String, Integer> right) {
        Map<String, Integer> a = left != null ? left : Map.of();
        Map<String, Integer> b = right != null ? right : Map.of();
        for (String key : a.keySet()) {
            if (!Objects.equals(a.get(key), b.getOrDefault(key, 0))) {
                return false;
            }
        }
        for (String key : b.keySet()) {
            if (!Objects.equals(b.get(key), a.getOrDefault(key, 0))) {
                return false;
            }
        }
        return true;
    }

    private String formatSizesSummary(Map<String, Integer> sizes) {
        if (sizes == null || sizes.isEmpty()) {
            return null;
        }
        return sizes.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue() > 0)
                .sorted(Map.Entry.comparingByKey((a, b) -> {
                    try {
                        return Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
                    } catch (NumberFormatException ex) {
                        return a.compareTo(b);
                    }
                }))
                .map(e -> e.getKey() + ":" + e.getValue())
                .collect(Collectors.joining(", "));
    }

    private String resolveItemHardware(String raw) throws BusinessException {
        String hardware = ProductHardwareCondition.normalize(raw);
        if (hardware == null) {
            hardware = ProductHardwareCondition.NUEVO;
        }
        if (!ProductHardwareCondition.NUEVO.equals(hardware)
                && !ProductHardwareCondition.VIEJO.equals(hardware)) {
            throw new BusinessException("Herraje inválido: use NUEVO o VIEJO.");
        }
        return hardware;
    }

    private String resolveHardwareLabel(String hardware) {
        if (ProductHardwareCondition.VIEJO.equals(ProductHardwareCondition.normalize(hardware))) {
            return "Herraje viejo";
        }
        return "Herraje nuevo";
    }

    private String resolveUsername(Long userId) {
        if (userId == null) {
            return "—";
        }
        return userRepository.findById(userId).map(u -> u.getUsername()).orElse("—");
    }

    private Long resolveCurrentUserId() {
        return securityUtil.getCurrentUserId();
    }

    private static int safeInt(Integer value) {
        return value != null ? value : 0;
    }
}
