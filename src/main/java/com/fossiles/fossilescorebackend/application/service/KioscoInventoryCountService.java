package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.KioscoNotificationRecipientRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioscoPhysicalCountItemUpsertRequest;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoKardexReportResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoNotificationRecipientResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoPhysicalCountReportResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoPhysicalCountSessionSummaryResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.util.ProductAudienceCategory;
import com.fossiles.fossilescorebackend.application.util.ProductCinchoType;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoNotificationRecipientEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoPhysicalCountEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoPhysicalCountItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoPhysicalCountStatus;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoStockEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductCategoryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoNotificationRecipientRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoPhysicalCountItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoPhysicalCountRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoStockRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductCategoryRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductInventorySizesJson;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

/**
 * Conteo fisico de inventario kiosco: cruza el Kardex de {@link KioscoInventoryService} con el
 * conteo capturado por las supervisoras en bodega/vitrinas, agrupado por categoria de producto.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class KioscoInventoryCountService {

    private static final List<String> COUNT_LOCATION_KEYS = List.of("V1", "V2", "V3", "V4", "V5", "V6", "V7", "E", "BO");
    private static final List<String> CINCHO_SIZE_LOCATION_KEYS = List.of("E", "BO");
    private static final String UNCATEGORIZED_LABEL = "Sin categoría";

    /** Diferencia absoluta minima (unidades) para considerar un producto como discrepancia relevante. */
    public static final int DIFF_ALERT_THRESHOLD = 3;

    private final KioscoPhysicalCountRepository countRepository;
    private final KioscoPhysicalCountItemRepository itemRepository;
    private final KioscoNotificationRecipientRepository notificationRecipientRepository;
    private final KioscoInventoryService kioscoInventoryService;
    private final KioscoStockRepository kioscoStockRepository;
    private final LocationRepository locationRepository;
    private final ProductRepository productRepository;
    private final ProductCategoryRepository productCategoryRepository;
    private final UserRepository userRepository;
    private final SecurityUtil securityUtil;

    public KioscoPhysicalCountReportResponse startOrGetSession(Long locationId, LocalDate from, LocalDate to)
            throws BusinessException, ResourceNotFoundException {
        KioscoPhysicalCountEntity count = countRepository
                .findByLocationIdAndPeriodFromAndPeriodTo(locationId, from, to)
                .orElse(null);
        if (count == null) {
            count = countRepository.save(KioscoPhysicalCountEntity.builder()
                    .locationId(locationId)
                    .periodFrom(from)
                    .periodTo(to)
                    .status(KioscoPhysicalCountStatus.DRAFT)
                    .generatedBy(resolveCurrentUserId())
                    .generatedAt(LocalDateTime.now())
                    .build());
        }
        return buildAndPersistReport(count);
    }

    @Transactional(readOnly = true)
    public KioscoPhysicalCountReportResponse getReport(Long countId) throws BusinessException, ResourceNotFoundException {
        return buildReport(findCountOrThrow(countId));
    }

    public KioscoPhysicalCountReportResponse upsertItems(Long countId, List<KioscoPhysicalCountItemUpsertRequest> items)
            throws BusinessException, ResourceNotFoundException {
        KioscoPhysicalCountEntity count = findCountOrThrow(countId);
        if (count.getStatus() == KioscoPhysicalCountStatus.CERRADO) {
            throw new BusinessException("El conteo está cerrado y no admite más cambios.");
        }
        if (items == null || items.isEmpty()) {
            throw new BusinessException("Debes indicar al menos un conteo para guardar.");
        }
        Long userId = resolveCurrentUserId();
        for (KioscoPhysicalCountItemUpsertRequest req : items) {
            if (req.getProductId() == null) {
                throw new BusinessException("productId es obligatorio en cada conteo.");
            }
            Map<String, BigDecimal> normalized = req.getCounts() != null
                    ? normalizeCounts(req.getCounts())
                    : null;
            KioscoPhysicalCountItemEntity item = itemRepository
                    .findByCountIdAndProductIdAndColorId(countId, req.getProductId(), req.getColorId())
                    .orElseGet(() -> KioscoPhysicalCountItemEntity.builder()
                            .countId(countId)
                            .productId(req.getProductId())
                            .colorId(req.getColorId())
                            .build());
            if (normalized != null) {
                item.setCountsData(ProductInventorySizesJson.serializeIncludingZeros(normalized));
            }
            if (req.getPhysicalSizes() != null) {
                item.setSizeCountsData(ProductInventorySizesJson.serializeIncludingZeros(
                        normalizePhysicalSizes(req.getPhysicalSizes())));
            }
            if (req.getPhysicalSizesByLocation() != null) {
                item.setSizeLocationCountsData(ProductInventorySizesJson.serializeByLocation(
                        normalizePhysicalSizesByLocation(req.getPhysicalSizesByLocation())));
            }
            if (normalized == null && req.getPhysicalSizes() == null && req.getPhysicalSizesByLocation() == null) {
                continue;
            }
            item.setUpdatedBy(userId);
            itemRepository.save(item);
        }
        return buildAndPersistReport(count);
    }

    public KioscoPhysicalCountReportResponse markReviewed(Long countId, String notes)
            throws BusinessException, ResourceNotFoundException {
        KioscoPhysicalCountEntity count = findCountOrThrow(countId);
        if (count.getStatus() == KioscoPhysicalCountStatus.CERRADO) {
            throw new BusinessException("El conteo está cerrado y no admite más cambios.");
        }
        count.setStatus(KioscoPhysicalCountStatus.REVISADO);
        count.setReviewedBy(resolveCurrentUserId());
        count.setReviewedAt(LocalDateTime.now());
        count.setDiffNotifiedAt(null);
        if (notes != null && !notes.isBlank()) {
            count.setNotes(notes.trim());
        }
        countRepository.save(count);
        return buildAndPersistReport(count);
    }

    /**
     * Cierra un conteo revisado, dejandolo de solo lectura. El ajuste de las diferencias siempre
     * es manual (movimiento AJUSTE con motivo) para mantener trazabilidad; cerrar solo bloquea edicion.
     */
    public KioscoPhysicalCountReportResponse cerrarConteo(Long countId) throws BusinessException, ResourceNotFoundException {
        KioscoPhysicalCountEntity count = findCountOrThrow(countId);
        if (count.getStatus() != KioscoPhysicalCountStatus.REVISADO) {
            throw new BusinessException("Solo se puede cerrar un conteo que ya fue revisado.");
        }
        count.setStatus(KioscoPhysicalCountStatus.CERRADO);
        count.setClosedBy(resolveCurrentUserId());
        count.setClosedAt(LocalDateTime.now());
        countRepository.save(count);
        return buildAndPersistReport(count);
    }

    @Transactional(readOnly = true)
    public List<KioscoPhysicalCountSessionSummaryResponse> listSessions(Long locationId) {
        return countRepository.findByLocationIdOrderByGeneratedAtDesc(locationId).stream()
                .map(this::toSessionSummary)
                .collect(Collectors.toList());
    }

    /** Conteos revisados con diferencias >= {@link #DIFF_ALERT_THRESHOLD} pendientes de cerrar. */
    @Transactional(readOnly = true)
    public List<KioscoPhysicalCountSessionSummaryResponse> listAlerts(Long locationId) {
        List<KioscoPhysicalCountEntity> pending = locationId != null
                ? countRepository.findByStatusAndMaxAbsDiffGreaterThanEqualAndLocationIdOrderByReviewedAtAsc(
                        KioscoPhysicalCountStatus.REVISADO, DIFF_ALERT_THRESHOLD, locationId)
                : countRepository.findByStatusAndMaxAbsDiffGreaterThanEqualOrderByReviewedAtAsc(
                        KioscoPhysicalCountStatus.REVISADO, DIFF_ALERT_THRESHOLD);
        return pending.stream().map(this::toSessionSummary).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<KioscoNotificationRecipientResponse> listNotificationRecipients() {
        return notificationRecipientRepository.findAllByOrderByNameAsc().stream()
                .map(this::toRecipientResponse)
                .collect(Collectors.toList());
    }

    public KioscoNotificationRecipientResponse addNotificationRecipient(KioscoNotificationRecipientRequest request)
            throws BusinessException {
        String name = request != null && request.getName() != null ? request.getName().trim() : "";
        String email = request != null && request.getEmail() != null ? request.getEmail().trim() : "";
        if (name.isEmpty() || email.isEmpty()) {
            throw new BusinessException("Nombre y correo son obligatorios.");
        }
        KioscoNotificationRecipientEntity recipient = notificationRecipientRepository.save(
                KioscoNotificationRecipientEntity.builder()
                        .name(name)
                        .email(email)
                        .active(true)
                        .build());
        return toRecipientResponse(recipient);
    }

    public void removeNotificationRecipient(Long recipientId) throws ResourceNotFoundException {
        if (!notificationRecipientRepository.existsById(recipientId)) {
            throw new ResourceNotFoundException("KioscoNotificationRecipient", recipientId);
        }
        notificationRecipientRepository.deleteById(recipientId);
    }

    private KioscoPhysicalCountEntity findCountOrThrow(Long countId) throws ResourceNotFoundException {
        return countRepository.findById(countId)
                .orElseThrow(() -> new ResourceNotFoundException("KioscoPhysicalCount", countId));
    }

    /** Recalcula el reporte y persiste maxAbsDiff en la sesion, para que las consultas de alertas queden al dia. */
    private KioscoPhysicalCountReportResponse buildAndPersistReport(KioscoPhysicalCountEntity count)
            throws BusinessException, ResourceNotFoundException {
        KioscoPhysicalCountReportResponse response = buildReport(count);
        if (!Objects.equals(count.getMaxAbsDiff(), response.getMaxAbsDiff())) {
            count.setMaxAbsDiff(response.getMaxAbsDiff());
            countRepository.save(count);
        }
        return response;
    }

    private KioscoPhysicalCountReportResponse buildReport(KioscoPhysicalCountEntity count)
            throws BusinessException, ResourceNotFoundException {
        List<KioscoKardexReportResponse.KioscoKardexRow> kardexRows = kioscoInventoryService.buildKardexRows(
                count.getLocationId(), count.getPeriodFrom(), count.getPeriodTo(), true);

        Map<String, KioscoPhysicalCountItemEntity> itemsByKey = itemRepository.findByCountId(count.getId()).stream()
                .collect(Collectors.toMap(i -> itemKey(i.getProductId(), i.getColorId()), i -> i, (a, b) -> a));

        Map<String, KioscoStockEntity> stockByKey = kioscoStockRepository
                .findByLocationIdOrderByProductIdAscColorIdAsc(count.getLocationId()).stream()
                .collect(Collectors.toMap(s -> itemKey(s.getProductId(), s.getColorId()), s -> s, (a, b) -> a));

        List<Long> productIds = kardexRows.stream()
                .map(KioscoKardexReportResponse.KioscoKardexRow::getProductId)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, ProductEntity> productsById = new LinkedHashMap<>();
        for (ProductEntity product : productRepository.findAllById(productIds)) {
            productsById.put(product.getId(), product);
        }
        Set<Long> categoryIds = productsById.values().stream()
                .map(ProductEntity::getCategoryId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, ProductCategoryEntity> categoriesById = new LinkedHashMap<>();
        for (ProductCategoryEntity category : productCategoryRepository.findAllById(categoryIds)) {
            categoriesById.put(category.getId(), category);
        }

        Map<String, List<KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow>> rowsByCategoryKey = new LinkedHashMap<>();
        Map<String, String> categoryNameByKey = new LinkedHashMap<>();
        List<KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow> allRows = new ArrayList<>();

        for (KioscoKardexReportResponse.KioscoKardexRow kardexRow : kardexRows) {
            if (!hasAssignedColor(kardexRow)) {
                continue;
            }

            ProductEntity product = productsById.get(kardexRow.getProductId());
            Long categoryId = product != null ? product.getCategoryId() : null;
            String categoryKey = categoryId != null ? String.valueOf(categoryId) : "NONE";
            String categoryName = categoryId != null
                    ? Optional.ofNullable(categoriesById.get(categoryId)).map(ProductCategoryEntity::getName).orElse(UNCATEGORIZED_LABEL)
                    : UNCATEGORIZED_LABEL;
            categoryNameByKey.putIfAbsent(categoryKey, categoryName);

            KioscoPhysicalCountItemEntity item = itemsByKey.get(itemKey(kardexRow.getProductId(), kardexRow.getColorId()));
            Map<String, BigDecimal> countedValues = item != null
                    ? ProductInventorySizesJson.parse(item.getCountsData())
                    : Map.of();
            Map<String, Integer> physicalSizes = toSystemSizesMap(item != null ? item.getSizeCountsData() : null);
            Map<String, Map<String, Integer>> physicalSizesByLocation = toPhysicalSizesByLocationMap(
                    item != null ? item.getSizeLocationCountsData() : null);

            KioscoStockEntity stock = stockByKey.get(itemKey(kardexRow.getProductId(), kardexRow.getColorId()));
            Map<String, Integer> systemSizes = toSystemSizesMap(stock != null ? stock.getSizesData() : null);
            String sizesSummary = formatSizesSummary(systemSizes);
            String physicalSizesSummary = formatSizesSummary(physicalSizes);

            Map<String, Integer> counts = new LinkedHashMap<>();
            int total = 0;
            for (String key : COUNT_LOCATION_KEYS) {
                int value = countedValues.getOrDefault(key, BigDecimal.ZERO).intValue();
                counts.put(key, value);
                total += value;
            }

            KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow row = KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow.builder()
                    .productId(kardexRow.getProductId())
                    .productCode(kardexRow.getProductCode())
                    .productName(kardexRow.getProductName())
                    .colorId(kardexRow.getColorId())
                    .colorName(kardexRow.getColorName())
                    .audienceCategory(product != null
                            ? ProductAudienceCategory.normalizeProductAudience(product.getAudienceCategory())
                            : ProductAudienceCategory.UNISEX)
                    .cinchoType(product != null ? ProductCinchoType.normalizeCinchoType(product.getCinchoType()) : null)
                    .packaging(ProductCinchoType.isPackagingProductCode(kardexRow.getProductCode()))
                    .systemSizes(systemSizes.isEmpty() ? null : systemSizes)
                    .physicalSizes(physicalSizes.isEmpty() ? null : physicalSizes)
                    .physicalSizesByLocation(physicalSizesByLocation)
                    .sizesSummary(sizesSummary)
                    .physicalSizesSummary(physicalSizesSummary)
                    .inventarioInicial(kardexRow.getInventarioInicial())
                    .comprasAjustes(kardexRow.getComprasAjustes())
                    .anulacionCompras(kardexRow.getAnulacionCompras())
                    .entradas(kardexRow.getEntradas())
                    .ventas(kardexRow.getVentas())
                    .anulacionVenta(kardexRow.getAnulacionVenta())
                    .salida(kardexRow.getSalida())
                    .inventarioFinal(kardexRow.getInventarioFinal())
                    .counts(counts)
                    .total(total)
                    .diferencia(kardexRow.getInventarioFinal() - total)
                    .build();
            allRows.add(row);
            rowsByCategoryKey.computeIfAbsent(categoryKey, k -> new ArrayList<>()).add(row);
        }

        List<String> orderedCategoryKeys = new ArrayList<>(rowsByCategoryKey.keySet());
        orderedCategoryKeys.sort(Comparator.comparing(
                key -> "NONE".equals(key) ? "\uFFFF" + UNCATEGORIZED_LABEL : categoryNameByKey.getOrDefault(key, ""),
                String.CASE_INSENSITIVE_ORDER));

        List<KioscoPhysicalCountReportResponse.KioscoPhysicalCountCategoryGroup> categories = new ArrayList<>();
        for (String key : orderedCategoryKeys) {
            List<KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow> rows = rowsByCategoryKey.get(key);
            categories.add(KioscoPhysicalCountReportResponse.KioscoPhysicalCountCategoryGroup.builder()
                    .categoryId("NONE".equals(key) ? null : Long.valueOf(key))
                    .categoryName(categoryNameByKey.get(key))
                    .rows(rows)
                    .subtotal(sumRows(rows))
                    .build());
        }

        LocationEntity location = locationRepository.findById(count.getLocationId())
                .orElseThrow(() -> new ResourceNotFoundException("Location", count.getLocationId()));

        int maxAbsDiff = allRows.stream().mapToInt(r -> Math.abs(r.getDiferencia())).max().orElse(0);

        return KioscoPhysicalCountReportResponse.builder()
                .id(count.getId())
                .locationId(location.getId())
                .locationCode(location.getCode())
                .locationName(location.getName())
                .periodFrom(count.getPeriodFrom())
                .periodTo(count.getPeriodTo())
                .status(count.getStatus().name())
                .notes(count.getNotes())
                .generatedBy(count.getGeneratedBy())
                .generatedByName(resolveUsername(count.getGeneratedBy()))
                .generatedAt(count.getGeneratedAt())
                .reviewedBy(count.getReviewedBy())
                .reviewedByName(resolveUsername(count.getReviewedBy()))
                .reviewedAt(count.getReviewedAt())
                .closedBy(count.getClosedBy())
                .closedByName(resolveUsername(count.getClosedBy()))
                .closedAt(count.getClosedAt())
                .maxAbsDiff(maxAbsDiff)
                .categories(categories)
                .totalGeneral(sumRows(allRows))
                .build();
    }

    private KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow sumRows(
            List<KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow> rows
    ) {
        Map<String, Integer> totalCounts = new LinkedHashMap<>();
        for (String key : COUNT_LOCATION_KEYS) {
            totalCounts.put(key, rows.stream().mapToInt(r -> r.getCounts().getOrDefault(key, 0)).sum());
        }
        return KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow.builder()
                .inventarioInicial(sumField(rows, KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow::getInventarioInicial))
                .comprasAjustes(sumField(rows, KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow::getComprasAjustes))
                .anulacionCompras(sumField(rows, KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow::getAnulacionCompras))
                .entradas(sumField(rows, KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow::getEntradas))
                .ventas(sumField(rows, KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow::getVentas))
                .anulacionVenta(sumField(rows, KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow::getAnulacionVenta))
                .salida(sumField(rows, KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow::getSalida))
                .inventarioFinal(sumField(rows, KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow::getInventarioFinal))
                .counts(totalCounts)
                .total(sumField(rows, KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow::getTotal))
                .diferencia(sumField(rows, KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow::getDiferencia))
                .build();
    }

    private int sumField(
            List<KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow> rows,
            ToIntFunction<KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow> extractor
    ) {
        return rows.stream().mapToInt(extractor).sum();
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
            if (COUNT_LOCATION_KEYS.contains(key)) {
                throw new BusinessException("La talla '" + key + "' coincide con una ubicacion de conteo; use otro valor.");
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
            String locKey = locEntry.getKey() == null ? "" : locEntry.getKey().trim().toUpperCase(Locale.ROOT);
            if (!CINCHO_SIZE_LOCATION_KEYS.contains(locKey)) {
                throw new BusinessException("Ubicacion de cincho invalida: " + locEntry.getKey()
                        + ". Solo se admite E (vitrina) o BO (bodega).");
            }
            Map<String, BigDecimal> sizes = normalizePhysicalSizes(locEntry.getValue());
            normalized.put(locKey, sizes);
        }
        return normalized;
    }

    private Map<String, Map<String, Integer>> toPhysicalSizesByLocationMap(String json) {
        Map<String, Map<String, Integer>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, BigDecimal>> locEntry
                : ProductInventorySizesJson.parseByLocation(json).entrySet()) {
            Map<String, Integer> sizes = new LinkedHashMap<>();
            for (Map.Entry<String, BigDecimal> sizeEntry : locEntry.getValue().entrySet()) {
                int qty = sizeEntry.getValue() != null ? sizeEntry.getValue().intValue() : 0;
                if (qty > 0) {
                    sizes.put(sizeEntry.getKey(), qty);
                }
            }
            if (!sizes.isEmpty()) {
                out.put(locEntry.getKey(), sizes);
            }
        }
        return out.isEmpty() ? null : out;
    }

    private String itemKey(Long productId, Long colorId) {
        return productId + ":" + (colorId != null ? colorId : "");
    }

    private boolean hasAssignedColor(KioscoKardexReportResponse.KioscoKardexRow kardexRow) {
        if (kardexRow == null || kardexRow.getColorId() == null) {
            return false;
        }
        String colorName = kardexRow.getColorName();
        return colorName != null && !colorName.isBlank();
    }

    private Map<String, Integer> toSystemSizesMap(String sizesDataJson) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> entry : ProductInventorySizesJson.parse(sizesDataJson).entrySet()) {
            int qty = entry.getValue() != null ? entry.getValue().intValue() : 0;
            if (qty > 0) {
                out.put(entry.getKey(), qty);
            }
        }
        return out;
    }

    private String formatSizesSummary(Map<String, Integer> sizes) {
        if (sizes == null || sizes.isEmpty()) {
            return null;
        }
        return sizes.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(this::compareSizeKeys))
                .map(e -> e.getKey() + ": " + e.getValue())
                .collect(Collectors.joining(" · "));
    }

    private int compareSizeKeys(String left, String right) {
        try {
            BigDecimal l = new BigDecimal(left.trim());
            BigDecimal r = new BigDecimal(right.trim());
            return l.compareTo(r);
        } catch (NumberFormatException ignored) {
            return left.compareToIgnoreCase(right);
        }
    }

    private String resolveUsername(Long userId) {
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId).map(UserEntity::getUsername).orElse(null);
    }

    private Long resolveCurrentUserId() throws BusinessException {
        Long userId = securityUtil.getCurrentUserId();
        if (userId == null) {
            throw new BusinessException("No se pudo determinar el usuario autenticado.");
        }
        return userId;
    }

    private KioscoPhysicalCountSessionSummaryResponse toSessionSummary(KioscoPhysicalCountEntity count) {
        return KioscoPhysicalCountSessionSummaryResponse.builder()
                .id(count.getId())
                .periodFrom(count.getPeriodFrom())
                .periodTo(count.getPeriodTo())
                .status(count.getStatus().name())
                .notes(count.getNotes())
                .generatedByName(resolveUsername(count.getGeneratedBy()))
                .generatedAt(count.getGeneratedAt())
                .reviewedByName(resolveUsername(count.getReviewedBy()))
                .reviewedAt(count.getReviewedAt())
                .closedByName(resolveUsername(count.getClosedBy()))
                .closedAt(count.getClosedAt())
                .maxAbsDiff(count.getMaxAbsDiff() != null ? count.getMaxAbsDiff() : 0)
                .build();
    }

    private KioscoNotificationRecipientResponse toRecipientResponse(KioscoNotificationRecipientEntity recipient) {
        return KioscoNotificationRecipientResponse.builder()
                .id(recipient.getId())
                .name(recipient.getName())
                .email(recipient.getEmail())
                .active(Boolean.TRUE.equals(recipient.getActive()))
                .build();
    }
}
