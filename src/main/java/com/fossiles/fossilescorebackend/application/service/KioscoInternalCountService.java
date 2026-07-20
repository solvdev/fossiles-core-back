package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.KioscoPhysicalCountItemUpsertRequest;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoInternalCountSummaryResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoPhysicalCountReportResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.util.ProductAudienceCategory;
import com.fossiles.fossilescorebackend.application.util.ProductCinchoType;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoInternalCountEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoInternalCountItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoInternalCountStatus;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoStockEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductCategoryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoInternalCountItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoInternalCountRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoStockRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductCategoryRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductInventorySizesJson;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class KioscoInternalCountService {

    private static final List<String> COUNT_LOCATION_KEYS = List.of(
            "V1", "V2", "V3", "V4", "V5", "V6", "V7", "E", "BO");
    private static final String REPORT_TYPE_INTERNAL = "INTERNO_ENCARGADA";

    private final KioscoInternalCountRepository internalCountRepository;
    private final KioscoInternalCountItemRepository internalCountItemRepository;
    private final KioscoStockRepository kioscoStockRepository;
    private final LocationRepository locationRepository;
    private final ProductRepository productRepository;
    private final ProductCategoryRepository productCategoryRepository;
    private final ColorRepository colorRepository;
    private final UserRepository userRepository;

    @Transactional
    public KioscoPhysicalCountReportResponse startOrGetDraft(Long locationId, LocalDate countDate)
            throws BusinessException, ResourceNotFoundException {
        validateLocation(locationId);
        LocalDate date = countDate != null ? countDate : LocalDate.now();
        KioscoInternalCountEntity entity = internalCountRepository.findByLocationIdAndCountDate(locationId, date)
                .orElseGet(() -> internalCountRepository.save(KioscoInternalCountEntity.builder()
                        .locationId(locationId)
                        .countDate(date)
                        .status(KioscoInternalCountStatus.DRAFT)
                        .createdBy(resolveCurrentUserId())
                        .build()));
        return buildReport(entity);
    }

    @Transactional(readOnly = true)
    public KioscoPhysicalCountReportResponse getReport(Long internalCountId)
            throws ResourceNotFoundException, BusinessException {
        return buildReport(findOrThrow(internalCountId));
    }

    @Transactional
    public KioscoPhysicalCountReportResponse upsertItems(
            Long internalCountId,
            List<KioscoPhysicalCountItemUpsertRequest> items
    ) throws BusinessException, ResourceNotFoundException {
        KioscoInternalCountEntity count = findOrThrow(internalCountId);
        assertEditable(count);
        if (items == null || items.isEmpty()) {
            throw new BusinessException("Debes indicar al menos un conteo para guardar.");
        }
        Long userId = resolveCurrentUserId();
        for (KioscoPhysicalCountItemUpsertRequest req : items) {
            if (req.getProductId() == null) {
                throw new BusinessException("productId es obligatorio en cada conteo.");
            }
            KioscoInternalCountItemEntity item = internalCountItemRepository
                    .findByInternalCountIdAndProductIdAndColorId(
                            internalCountId, req.getProductId(), req.getColorId())
                    .orElseGet(() -> KioscoInternalCountItemEntity.builder()
                            .internalCountId(internalCountId)
                            .productId(req.getProductId())
                            .colorId(req.getColorId())
                            .build());
            applyItemPayload(item, req, userId);
            internalCountItemRepository.save(item);
        }
        return buildReport(count);
    }

    @Transactional
    public KioscoPhysicalCountReportResponse saveSnapshot(Long internalCountId, String notes)
            throws BusinessException, ResourceNotFoundException {
        KioscoInternalCountEntity count = findOrThrow(internalCountId);
        count.setStatus(KioscoInternalCountStatus.SAVED);
        count.setSavedAt(LocalDateTime.now());
        if (notes != null && !notes.isBlank()) {
            count.setNotes(notes.trim());
        }
        internalCountRepository.save(count);
        return buildReport(count);
    }

    @Transactional(readOnly = true)
    public List<KioscoInternalCountSummaryResponse> listHistory(Long locationId) {
        return internalCountRepository.findByLocationIdOrderByCountDateDescSavedAtDesc(locationId).stream()
                .map(this::toSummary)
                .collect(Collectors.toList());
    }

    private KioscoPhysicalCountReportResponse buildReport(KioscoInternalCountEntity count)
            throws BusinessException, ResourceNotFoundException {
        LocationEntity location = locationRepository.findById(count.getLocationId())
                .orElseThrow(() -> new ResourceNotFoundException("Location", count.getLocationId()));

        Map<String, List<KioscoStockEntity>> stocksByKey = kioscoStockRepository
                .findByLocationIdOrderByProductIdAscColorIdAscHardwareConditionAsc(count.getLocationId()).stream()
                .collect(Collectors.groupingBy(s -> itemKey(s.getProductId(), s.getColorId())));

        Map<String, KioscoInternalCountItemEntity> itemsByKey = internalCountItemRepository
                .findByInternalCountId(count.getId()).stream()
                .collect(Collectors.toMap(i -> itemKey(i.getProductId(), i.getColorId()), i -> i, (a, b) -> a));

        List<Long> productIds = stocksByKey.values().stream()
                .flatMap(List::stream)
                .map(KioscoStockEntity::getProductId)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, ProductEntity> productsById = productRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(ProductEntity::getId, p -> p, (a, b) -> a));
        List<Long> colorIds = stocksByKey.values().stream()
                .flatMap(List::stream)
                .map(KioscoStockEntity::getColorId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, ColorEntity> colorsById = colorRepository.findAllById(colorIds).stream()
                .collect(Collectors.toMap(ColorEntity::getId, c -> c, (a, b) -> a));
        Map<Long, ProductCategoryEntity> categoriesById = productCategoryRepository.findAll().stream()
                .collect(Collectors.toMap(ProductCategoryEntity::getId, c -> c, (a, b) -> a));

        Map<String, List<KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow>> rowsByCategory = new LinkedHashMap<>();
        Map<String, String> categoryNames = new LinkedHashMap<>();
        List<KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow> allRows = new ArrayList<>();

        for (Map.Entry<String, List<KioscoStockEntity>> entry : stocksByKey.entrySet()) {
            List<KioscoStockEntity> stocks = entry.getValue();
            if (stocks.isEmpty()) {
                continue;
            }
            KioscoStockEntity primary = stocks.get(0);
            ProductEntity product = productsById.get(primary.getProductId());
            if (product == null) {
                continue;
            }
            int inventarioFinal = stocks.stream().mapToInt(s -> safeInt(s.getCurrentStock())).sum();
            if (inventarioFinal <= 0 && !itemsByKey.containsKey(entry.getKey())) {
                continue;
            }

            KioscoInternalCountItemEntity item = itemsByKey.get(entry.getKey());
            Map<String, Integer> counts = parseCounts(item != null ? item.getCountsData() : null);
            Map<String, Integer> physicalSizes = parseCounts(item != null ? item.getSizeCountsData() : null);
            Map<String, Map<String, Integer>> physicalSizesByLocation = parseHardwareMap(
                    item != null ? item.getSizeLocationCountsData() : null);
            Map<String, Map<String, Integer>> hardwareLocationCounts = parseHardwareMap(
                    item != null ? item.getHardwareLocationCountsData() : null);
            int total = counts.values().stream().mapToInt(Integer::intValue).sum();
            if (total == 0 && hardwareLocationCounts != null) {
                total = hardwareLocationCounts.values().stream()
                        .flatMap(m -> m.values().stream())
                        .mapToInt(Integer::intValue)
                        .sum();
            }

            Map<String, Integer> inventarioFinalByHardware = new LinkedHashMap<>();
            for (KioscoStockEntity stock : stocks) {
                inventarioFinalByHardware.merge(stock.getHardwareCondition(), safeInt(stock.getCurrentStock()), Integer::sum);
            }

            String categoryKey = resolveCategoryKey(product, categoriesById);
            categoryNames.putIfAbsent(categoryKey, resolveCategoryName(categoryKey, product, categoriesById));

            KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow row =
                    KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow.builder()
                            .productId(primary.getProductId())
                            .productCode(product.getCode())
                            .productName(product.getName())
                            .colorId(primary.getColorId())
                            .colorName(Optional.ofNullable(primary.getColorId())
                                    .map(colorsById::get)
                                    .map(ColorEntity::getName)
                                    .orElse(null))
                            .audienceCategory(ProductAudienceCategory.normalizeProductAudience(product.getAudienceCategory()))
                            .cinchoType(ProductCinchoType.normalizeCinchoType(product.getCinchoType()))
                            .cinchoForKids(Boolean.TRUE.equals(product.getCinchoForKids()))
                            .packaging(ProductCinchoType.isPackagingProductCode(product.getCode()))
                            .hardwareCondition(stocks.size() == 1 ? primary.getHardwareCondition() : null)
                            .inventarioFinal(inventarioFinal)
                            .inventarioFinalByHardware(inventarioFinalByHardware.isEmpty() ? null : inventarioFinalByHardware)
                            .counts(counts)
                            .hardwareLocationCounts(hardwareLocationCounts)
                            .physicalSizes(physicalSizes.isEmpty() ? null : physicalSizes)
                            .physicalSizesByLocation(physicalSizesByLocation)
                            .systemSizes(parseCounts(primary.getSizesData()))
                            .total(total)
                            .diferencia(KioscoInventoryCountService.computeDiferenciaConteo(total, inventarioFinal, 0))
                            .build();
            rowsByCategory.computeIfAbsent(categoryKey, k -> new ArrayList<>()).add(row);
            allRows.add(row);
        }

        List<KioscoPhysicalCountReportResponse.KioscoPhysicalCountCategoryGroup> categories = new ArrayList<>();
        for (Map.Entry<String, List<KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow>> catEntry : rowsByCategory.entrySet()) {
            categories.add(KioscoPhysicalCountReportResponse.KioscoPhysicalCountCategoryGroup.builder()
                    .categoryName(categoryNames.get(catEntry.getKey()))
                    .rows(catEntry.getValue())
                    .subtotal(sumRows(catEntry.getValue()))
                    .build());
        }

        return KioscoPhysicalCountReportResponse.builder()
                .id(count.getId())
                .locationId(location.getId())
                .locationCode(location.getCode())
                .locationName(location.getName())
                .periodFrom(count.getCountDate())
                .periodTo(count.getCountDate())
                .status(count.getStatus().name())
                .notes(count.getNotes())
                .generatedBy(count.getCreatedBy())
                .generatedByName(resolveUsername(count.getCreatedBy()))
                .generatedAt(count.getCreatedAt())
                .reportType(REPORT_TYPE_INTERNAL)
                .asOfDate(count.getCountDate())
                .categories(categories)
                .totalGeneral(sumRows(allRows))
                .build();
    }

    private void applyItemPayload(
            KioscoInternalCountItemEntity item,
            KioscoPhysicalCountItemUpsertRequest req,
            Long userId
    ) throws BusinessException {
        if (req.getCounts() != null) {
            item.setCountsData(ProductInventorySizesJson.serializeIncludingZeros(
                    normalizeCounts(req.getCounts())));
        }
        if (req.getPhysicalSizes() != null) {
            item.setSizeCountsData(ProductInventorySizesJson.serializeIncludingZeros(
                    normalizePhysicalSizes(req.getPhysicalSizes())));
        }
        if (req.getPhysicalSizesByLocation() != null) {
            item.setSizeLocationCountsData(ProductInventorySizesJson.serializeByLocation(
                    normalizePhysicalSizesByLocation(req.getPhysicalSizesByLocation())));
        }
        if (req.getHardwareLocationCounts() != null) {
            item.setHardwareLocationCountsData(ProductInventorySizesJson.serializeByLocation(
                    normalizeHardwareLocationCounts(req.getHardwareLocationCounts())));
            syncCountsFromHardware(item, req.getHardwareLocationCounts());
        }
        if (req.getObservation() != null) {
            String trimmed = req.getObservation().trim();
            item.setObservation(trimmed.isEmpty() ? null : trimmed);
        }
        item.setUpdatedBy(userId);
    }

    private Map<String, BigDecimal> normalizeCounts(Map<String, Integer> raw) throws BusinessException {
        Map<String, BigDecimal> normalized = new LinkedHashMap<>();
        if (raw == null) {
            return normalized;
        }
        for (Map.Entry<String, Integer> entry : raw.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim().toUpperCase(Locale.ROOT);
            if (!COUNT_LOCATION_KEYS.contains(key)) {
                continue;
            }
            int value = entry.getValue() != null ? entry.getValue() : 0;
            if (value < 0) {
                throw new BusinessException("El conteo de " + key + " no puede ser negativo.");
            }
            normalized.put(key, BigDecimal.valueOf(value));
        }
        return normalized;
    }

    private Map<String, BigDecimal> normalizePhysicalSizes(Map<String, Integer> raw) throws BusinessException {
        Map<String, BigDecimal> normalized = new LinkedHashMap<>();
        if (raw == null) {
            return normalized;
        }
        for (Map.Entry<String, Integer> entry : raw.entrySet()) {
            String key = ProductInventorySizesJson.normalizeKey(entry.getKey());
            if (key.isEmpty()) {
                continue;
            }
            int value = entry.getValue() != null ? entry.getValue() : 0;
            if (value < 0) {
                throw new BusinessException("El conteo de talla " + key + " no puede ser negativo.");
            }
            normalized.put(key, BigDecimal.valueOf(value));
        }
        return normalized;
    }

    private Map<String, Map<String, BigDecimal>> normalizePhysicalSizesByLocation(
            Map<String, Map<String, Integer>> raw
    ) throws BusinessException {
        Map<String, Map<String, BigDecimal>> normalized = new LinkedHashMap<>();
        if (raw == null) {
            return normalized;
        }
        for (Map.Entry<String, Map<String, Integer>> locEntry : raw.entrySet()) {
            normalized.put(locEntry.getKey().trim().toUpperCase(Locale.ROOT),
                    normalizePhysicalSizes(locEntry.getValue()));
        }
        return normalized;
    }

    private Map<String, Map<String, BigDecimal>> normalizeHardwareLocationCounts(
            Map<String, Map<String, Integer>> raw
    ) throws BusinessException {
        Map<String, Map<String, BigDecimal>> normalized = new LinkedHashMap<>();
        if (raw == null) {
            return normalized;
        }
        for (Map.Entry<String, Map<String, Integer>> locEntry : raw.entrySet()) {
            String locKey = locEntry.getKey() == null ? "" : locEntry.getKey().trim().toUpperCase(Locale.ROOT);
            if (!COUNT_LOCATION_KEYS.contains(locKey)) {
                throw new BusinessException("Ubicación de conteo inválida: " + locEntry.getKey());
            }
            Map<String, BigDecimal> hardwareMap = new LinkedHashMap<>();
            if (locEntry.getValue() != null) {
                for (Map.Entry<String, Integer> hwEntry : locEntry.getValue().entrySet()) {
                    String hw = hwEntry.getKey() == null ? "" : hwEntry.getKey().trim().toUpperCase(Locale.ROOT);
                    if (!"NUEVO".equals(hw) && !"VIEJO".equals(hw)) {
                        throw new BusinessException("Herraje inválido: " + hwEntry.getKey());
                    }
                    int value = hwEntry.getValue() != null ? hwEntry.getValue() : 0;
                    if (value < 0) {
                        throw new BusinessException("El conteo de herraje " + hw + " no puede ser negativo.");
                    }
                    hardwareMap.put(hw, BigDecimal.valueOf(value));
                }
            }
            normalized.put(locKey, hardwareMap);
        }
        return normalized;
    }

    private void syncCountsFromHardware(
            KioscoInternalCountItemEntity item,
            Map<String, Map<String, Integer>> hardwareLocationCounts
    ) {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Integer>> locEntry : hardwareLocationCounts.entrySet()) {
            int sum = locEntry.getValue() != null
                    ? locEntry.getValue().values().stream().mapToInt(v -> v != null ? v : 0).sum()
                    : 0;
            totals.put(locEntry.getKey(), BigDecimal.valueOf(sum));
        }
        item.setCountsData(ProductInventorySizesJson.serializeIncludingZeros(totals));
    }

    private Map<String, Integer> parseCounts(String json) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> e : ProductInventorySizesJson.parse(json).entrySet()) {
            out.put(e.getKey(), e.getValue() != null ? e.getValue().intValue() : 0);
        }
        return out;
    }

    private Map<String, Map<String, Integer>> parseHardwareMap(String json) {
        Map<String, Map<String, Integer>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, BigDecimal>> locEntry
                : ProductInventorySizesJson.parseByLocation(json).entrySet()) {
            Map<String, Integer> hw = new LinkedHashMap<>();
            for (Map.Entry<String, BigDecimal> e : locEntry.getValue().entrySet()) {
                hw.put(e.getKey(), e.getValue() != null ? e.getValue().intValue() : 0);
            }
            if (!hw.isEmpty()) {
                out.put(locEntry.getKey(), hw);
            }
        }
        return out.isEmpty() ? null : out;
    }

    private KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow sumRows(
            List<KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow> rows
    ) {
        Map<String, Integer> totalCounts = new LinkedHashMap<>();
        for (String key : COUNT_LOCATION_KEYS) {
            totalCounts.put(key, rows.stream().mapToInt(r -> r.getCounts().getOrDefault(key, 0)).sum());
        }
        int total = rows.stream().mapToInt(KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow::getTotal).sum();
        return KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow.builder()
                .inventarioFinal(rows.stream().mapToInt(
                        KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow::getInventarioFinal).sum())
                .counts(totalCounts)
                .total(total)
                .diferencia(rows.stream().mapToInt(
                        KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow::getDiferencia).sum())
                .build();
    }

    private KioscoInternalCountEntity findOrThrow(Long id) throws ResourceNotFoundException {
        return internalCountRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("KioscoInternalCount", id));
    }

    private void assertEditable(KioscoInternalCountEntity count) throws BusinessException {
        if (count.getStatus() == KioscoInternalCountStatus.SAVED) {
            throw new BusinessException("Este conteo interno ya fue guardado. Abre el borrador del día para seguir contando.");
        }
    }

    private void validateLocation(Long locationId) throws ResourceNotFoundException {
        if (!locationRepository.existsById(locationId)) {
            throw new ResourceNotFoundException("Location", locationId);
        }
    }

    private KioscoInternalCountSummaryResponse toSummary(KioscoInternalCountEntity entity) {
        return KioscoInternalCountSummaryResponse.builder()
                .id(entity.getId())
                .locationId(entity.getLocationId())
                .countDate(entity.getCountDate())
                .status(entity.getStatus().name())
                .notes(entity.getNotes())
                .createdBy(entity.getCreatedBy())
                .createdByName(resolveUsername(entity.getCreatedBy()))
                .createdAt(entity.getCreatedAt())
                .savedAt(entity.getSavedAt())
                .build();
    }

    private String resolveUsername(Long userId) {
        if (userId == null) {
            return "—";
        }
        return userRepository.findById(userId).map(u -> u.getUsername()).orElse("—");
    }

    private Long resolveCurrentUserId() {
        return SecurityUtil.getCurrentUserId().orElse(null);
    }

    private static String itemKey(Long productId, Long colorId) {
        return productId + ":" + (colorId != null ? colorId : "");
    }

    private static int safeInt(Integer value) {
        return value != null ? value : 0;
    }

    private String resolveCategoryKey(ProductEntity product, Map<Long, ProductCategoryEntity> categoriesById) {
        if (ProductCinchoType.isPackagingProductCode(product.getCode())) {
            return "EMPAQUES";
        }
        Long categoryId = product.getCategoryId();
        return categoryId != null ? String.valueOf(categoryId) : "NONE";
    }

    private String resolveCategoryName(
            String categoryKey,
            ProductEntity product,
            Map<Long, ProductCategoryEntity> categoriesById
    ) {
        if ("EMPAQUES".equals(categoryKey)) {
            return "Empaques";
        }
        if ("NONE".equals(categoryKey)) {
            return "Sin categoría";
        }
        return Optional.ofNullable(categoriesById.get(product.getCategoryId()))
                .map(ProductCategoryEntity::getName)
                .orElse("Sin categoría");
    }
}
