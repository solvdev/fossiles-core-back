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
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskExchangeSlipEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductCategoryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoNotificationRecipientRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoPhysicalCountItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoPhysicalCountRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoStockRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskExchangeSlipRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductCategoryRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.CinchoProductUtils;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductInventorySizesJson;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import java.util.TreeSet;
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
    private static final String PACKAGING_CATEGORY_KEY = "PACKAGING";
    private static final String PACKAGING_CATEGORY_NAME = "Empaques";

    private static final String REPORT_TYPE_PRINCIPAL = "PRINCIPAL";
    private static final String REPORT_TYPE_SUBCONTEO = "SUBCONTEO";

    /** Diferencia absoluta minima (unidades) para considerar un producto como discrepancia relevante. */
    public static final int DIFF_ALERT_THRESHOLD = 3;

    private static final String SLIP_TYPE_RETURN = "RETURN";
    private static final String STATUS_PENDING_REINTEGRO = "PENDING_REINTEGRO";
    private static final String STATUS_REINTEGRATED = "REINTEGRATED";
    private static final String STATUS_COMPLETED = "COMPLETED";

    private final KioscoPhysicalCountRepository countRepository;
    private final KioscoPhysicalCountItemRepository itemRepository;
    private final KioscoNotificationRecipientRepository notificationRecipientRepository;
    private final KioscoInventoryService kioscoInventoryService;
    private final KioscoStockRepository kioscoStockRepository;
    private final KioskExchangeSlipRepository exchangeSlipRepository;
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
        return buildReport(findCountOrThrow(countId), null);
    }

    @Transactional(readOnly = true)
    public KioscoPhysicalCountReportResponse getSubcountReport(Long countId, LocalDate asOf)
            throws BusinessException, ResourceNotFoundException {
        if (asOf == null) {
            throw new BusinessException("Debes indicar la fecha de corte (asOf).");
        }
        KioscoPhysicalCountEntity count = findCountOrThrow(countId);
        if (asOf.isBefore(count.getPeriodFrom()) || asOf.isAfter(count.getPeriodTo())) {
            throw new BusinessException(
                    "La fecha de corte debe estar entre " + count.getPeriodFrom() + " y " + count.getPeriodTo() + ".");
        }
        return buildReport(count, asOf);
    }

    public KioscoPhysicalCountReportResponse upsertItems(Long countId, List<KioscoPhysicalCountItemUpsertRequest> items)
            throws BusinessException, ResourceNotFoundException {
        KioscoPhysicalCountEntity count = findCountOrThrow(countId);
        assertCountEditable(count);
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
            if (req.getObservation() != null) {
                String trimmed = req.getObservation().trim();
                item.setObservation(trimmed.isEmpty() ? null : trimmed);
            }
            if (req.getSizeObservations() != null) {
                Map<String, String> merged = ProductInventorySizesJson.parseStringMap(item.getSizeObservationsData());
                for (Map.Entry<String, String> entry : req.getSizeObservations().entrySet()) {
                    String sizeKey = ProductInventorySizesJson.normalizeKey(entry.getKey());
                    if (sizeKey.isEmpty()) {
                        continue;
                    }
                    String value = entry.getValue() != null ? entry.getValue().trim() : "";
                    if (value.isEmpty()) {
                        merged.remove(sizeKey);
                    } else {
                        merged.put(sizeKey, value);
                    }
                }
                item.setSizeObservationsData(ProductInventorySizesJson.serializeStringMap(merged));
            }
            boolean hasCountChanges = normalized != null
                    || req.getPhysicalSizes() != null
                    || req.getPhysicalSizesByLocation() != null;
            boolean hasObservationChanges = req.getObservation() != null || req.getSizeObservations() != null;
            if (!hasCountChanges && !hasObservationChanges) {
                continue;
            }
            item.setUpdatedBy(userId);
            itemRepository.save(item);
        }
        return buildAndPersistReport(count);
    }

    /**
     * Marca el conteo físico como terminado: bloquea la edición de vitrinas antes de la revisión.
     */
    public KioscoPhysicalCountReportResponse terminarConteo(Long countId)
            throws BusinessException, ResourceNotFoundException {
        KioscoPhysicalCountEntity count = findCountOrThrow(countId);
        if (count.getStatus() != KioscoPhysicalCountStatus.DRAFT) {
            throw new BusinessException("Solo se puede terminar un conteo en borrador.");
        }
        count.setStatus(KioscoPhysicalCountStatus.CONTADO);
        countRepository.save(count);
        return buildAndPersistReport(count);
    }

    public KioscoPhysicalCountReportResponse markReviewed(Long countId, String notes)
            throws BusinessException, ResourceNotFoundException {
        KioscoPhysicalCountEntity count = findCountOrThrow(countId);
        if (count.getStatus() == KioscoPhysicalCountStatus.CERRADO) {
            throw new BusinessException("El conteo está cerrado y no admite más cambios.");
        }
        if (count.getStatus() == KioscoPhysicalCountStatus.DRAFT) {
            throw new BusinessException("Debe terminar el conteo físico antes de marcarlo como revisado.");
        }
        if (count.getStatus() != KioscoPhysicalCountStatus.CONTADO) {
            throw new BusinessException("Solo se puede revisar un conteo que ya fue marcado como contado.");
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

    private void assertCountEditable(KioscoPhysicalCountEntity count) throws BusinessException {
        if (count.getStatus() == KioscoPhysicalCountStatus.CERRADO) {
            throw new BusinessException("El conteo está cerrado y no admite más cambios.");
        }
        if (count.getStatus() != KioscoPhysicalCountStatus.DRAFT) {
            throw new BusinessException(
                    "El conteo ya fue terminado; las vitrinas están bloqueadas. Solo se puede revisar o exportar.");
        }
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
        return buildReport(count, null);
    }

    private KioscoPhysicalCountReportResponse buildReport(KioscoPhysicalCountEntity count, LocalDate balanceAsOf)
            throws BusinessException, ResourceNotFoundException {
        boolean isSubcount = balanceAsOf != null;
        LocalDate kardexTo = isSubcount ? balanceAsOf : count.getPeriodTo();
        // Fin. siempre al cierre del periodo/corte (replay de movimientos), no stock vivo.
        LocalDate finAsOf = isSubcount ? balanceAsOf : count.getPeriodTo();

        Map<String, KioscoStockEntity> stockByKey = kioscoStockRepository
                .findByLocationIdOrderByProductIdAscColorIdAsc(count.getLocationId()).stream()
                .collect(Collectors.toMap(s -> itemKey(s.getProductId(), s.getColorId()), s -> s, (a, b) -> a));
        if (!isSubcount) {
            stockByKey.values().forEach(kioscoInventoryService::syncFossCurrentStockFromSizes);
        }

        List<KioscoKardexReportResponse.KioscoKardexRow> kardexRows = kioscoInventoryService.buildKardexRows(
                count.getLocationId(), count.getPeriodFrom(), kardexTo, true, finAsOf, count.getId());
        Map<Long, Map<String, KioscoInventoryService.SizeKardexBucket>> kardexByStockAndSize =
                kioscoInventoryService.buildKardexByStockAndSize(
                        count.getLocationId(), count.getPeriodFrom(), kardexTo, count.getId());
        applyPendingReturnSalidasForCount(count, kardexRows, kardexByStockAndSize, stockByKey);

        Optional<KioscoPhysicalCountEntity> previousCount = resolvePreviousPhysicalCount(count);
        LocalDateTime openingCutoffExclusive = previousCount
                .map(c -> c.getPeriodTo().plusDays(1).atStartOfDay())
                .orElse(null);
        Map<Long, Integer> openingBalanceByStockId = openingCutoffExclusive != null
                ? kioscoInventoryService.computeStockBalanceByStockId(count.getLocationId(), openingCutoffExclusive)
                : Map.of();
        Map<Long, Map<String, Integer>> openingBalanceByStockAndSize = openingCutoffExclusive != null
                ? kioscoInventoryService.computeSizeBalanceByStockAndSize(
                        count.getLocationId(), openingCutoffExclusive)
                : Map.of();

        LocalDateTime periodStart = count.getPeriodFrom().atStartOfDay();
        Map<Long, Integer> prePeriodEntradasByStockId = kioscoInventoryService.computePrePeriodEntradasByStockId(
                count.getLocationId(), openingCutoffExclusive, periodStart);
        Map<Long, Map<String, Integer>> prePeriodEntradasByStockAndSize =
                kioscoInventoryService.computePrePeriodEntradasByStockAndSize(
                        count.getLocationId(), openingCutoffExclusive, periodStart);
        applyPrePeriodEntradasToKardexBySize(kardexByStockAndSize, prePeriodEntradasByStockAndSize);
        openingBalanceByStockAndSize = subtractQuantityMapsByStock(
                openingBalanceByStockAndSize, prePeriodEntradasByStockAndSize);

        Map<String, KioscoPhysicalCountItemEntity> itemsByKey = itemRepository.findByCountId(count.getId()).stream()
                .collect(Collectors.toMap(i -> itemKey(i.getProductId(), i.getColorId()), i -> i, (a, b) -> a));

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
            if (!shouldIncludeInPhysicalCount(kardexRow)) {
                continue;
            }

            ProductEntity product = productsById.get(kardexRow.getProductId());
            String categoryKey = resolveDisplayCategoryKey(product, kardexRow.getProductCode(), categoriesById);
            String categoryName = resolveDisplayCategoryName(categoryKey, categoriesById);
            categoryNameByKey.putIfAbsent(categoryKey, categoryName);

            KioscoPhysicalCountItemEntity item = itemsByKey.get(itemKey(kardexRow.getProductId(), kardexRow.getColorId()));
            Map<String, BigDecimal> countedValues = item != null
                    ? ProductInventorySizesJson.parse(item.getCountsData())
                    : Map.of();
            Map<String, Integer> physicalSizes = toSystemSizesMap(item != null ? item.getSizeCountsData() : null);
            Map<String, Map<String, Integer>> physicalSizesByLocation = toPhysicalSizesByLocationMap(
                    item != null ? item.getSizeLocationCountsData() : null);

            KioscoStockEntity stock = stockByKey.get(itemKey(kardexRow.getProductId(), kardexRow.getColorId()));
            Map<String, KioscoInventoryService.SizeKardexBucket> sizeKardexForStock = stock != null
                    ? kardexByStockAndSize.getOrDefault(stock.getId(), Map.of())
                    : Map.of();
            // Incluir tallas con movimiento/envío en el periodo aunque el stock actual sea 0.
            Map<String, Integer> systemSizes = enrichSizesWithMovementKeys(
                    resolveSystemSizesForReport(stock), sizeKardexForStock);
            String sizesSummary = formatSizesSummary(systemSizes);
            String physicalSizesSummary = formatSizesSummary(physicalSizes);
            String generalObservation = item != null && item.getObservation() != null
                    ? item.getObservation().trim() : "";
            Map<String, String> sizeObservations = item != null
                    ? ProductInventorySizesJson.parseStringMap(item.getSizeObservationsData())
                    : Map.of();

            Map<String, Integer> counts = new LinkedHashMap<>();
            int total = computePhysicalCountTotal(
                    product, countedValues, physicalSizes, physicalSizesByLocation);

            for (String key : COUNT_LOCATION_KEYS) {
                int value = countedValues.getOrDefault(key, BigDecimal.ZERO).intValue();
                counts.put(key, value);
            }

            int prePeriodEntradas = stock != null
                    ? prePeriodEntradasByStockId.getOrDefault(stock.getId(), 0)
                    : 0;
            int inventarioInicial = stock != null
                    ? openingBalanceByStockId.getOrDefault(stock.getId(), 0)
                    : 0;
            inventarioInicial = Math.max(0, inventarioInicial - prePeriodEntradas);
            int entradas = kardexRow.getEntradas() + prePeriodEntradas;
            // Fin. = Ini. + movimientos del periodo (algebraico): +Ent -Vtas +Anul.Vta -Sal (+Compras -Anul.Compras)
            int inventarioFinal = Math.max(0, inventarioInicial + kardexRowNetDelta(kardexRow, entradas));

            Map<String, Integer> rowSystemSizes = isSubcount ? null : (systemSizes.isEmpty() ? null : systemSizes);
            String rowSizesSummary = isSubcount ? null : sizesSummary;

            Long productCategoryId = product != null ? product.getCategoryId() : null;
            String productCategoryName = productCategoryId != null
                    ? Optional.ofNullable(categoriesById.get(productCategoryId))
                            .map(ProductCategoryEntity::getName).orElse(UNCATEGORIZED_LABEL)
                    : UNCATEGORIZED_LABEL;

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
                    .cinchoForKids(product != null && Boolean.TRUE.equals(product.getCinchoForKids()))
                    .hardwareCondition(stock != null ? stock.getHardwareCondition() : null)
                    .packaging(ProductCinchoType.isPackagingProductCode(kardexRow.getProductCode()))
                    .productCategoryId(productCategoryId)
                    .productCategoryName(productCategoryName)
                    .systemSizes(rowSystemSizes)
                    .physicalSizes(physicalSizes.isEmpty() ? null : physicalSizes)
                    .physicalSizesByLocation(physicalSizesByLocation)
                    .sizesSummary(rowSizesSummary)
                    .physicalSizesSummary(physicalSizesSummary)
                    .inventarioInicial(inventarioInicial)
                    .comprasAjustes(kardexRow.getComprasAjustes())
                    .anulacionCompras(kardexRow.getAnulacionCompras())
                    .entradas(entradas)
                    .ventas(kardexRow.getVentas())
                    .anulacionVenta(kardexRow.getAnulacionVenta())
                    .salida(kardexRow.getSalida())
                    .salidaDevolucion(kardexRow.getSalidaDevolucion())
                    .inventarioFinal(inventarioFinal)
                    .counts(counts)
                    .total(total)
                    .diferencia(computeDiferenciaConteo(total, inventarioFinal, kardexRow.getSalidaDevolucion()))
                    .build();
            Map<String, Integer> openingBalanceBySize = stock != null
                    ? openingBalanceByStockAndSize.getOrDefault(stock.getId(), Map.of())
                    : Map.of();
            List<KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow> displayRows = isSubcount
                    ? List.of(applyRowObservation(row, generalObservation, null))
                    : expandRowsForDisplay(
                            row, product, stock, kardexByStockAndSize, openingBalanceBySize,
                            generalObservation, sizeObservations);
            for (KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow displayRow : displayRows) {
                allRows.add(displayRow);
                rowsByCategoryKey.computeIfAbsent(categoryKey, k -> new ArrayList<>()).add(displayRow);
            }
        }

        List<String> orderedCategoryKeys = new ArrayList<>(rowsByCategoryKey.keySet());
        orderedCategoryKeys.sort(Comparator.comparing(
                key -> "NONE".equals(key) ? "\uFFFF" + UNCATEGORIZED_LABEL : categoryNameByKey.getOrDefault(key, ""),
                String.CASE_INSENSITIVE_ORDER));

        List<KioscoPhysicalCountReportResponse.KioscoPhysicalCountCategoryGroup> categories = new ArrayList<>();
        for (String key : orderedCategoryKeys) {
            List<KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow> rows = rowsByCategoryKey.get(key);
            categories.add(KioscoPhysicalCountReportResponse.KioscoPhysicalCountCategoryGroup.builder()
                    .categoryId(resolveDisplayCategoryId(key))
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
                .reportType(isSubcount ? REPORT_TYPE_SUBCONTEO : REPORT_TYPE_PRINCIPAL)
                .asOfDate(balanceAsOf)
                .parentCountId(isSubcount ? count.getId() : null)
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
                .salidaDevolucion(sumField(rows, KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow::getSalidaDevolucion))
                .inventarioFinal(sumField(rows, KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow::getInventarioFinal))
                .counts(totalCounts)
                .total(sumField(rows, KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow::getTotal))
                // No recalcular con Σ salidaDevolucion: computeDiferenciaConteo no es lineal
                // (solo descuenta devoluciones cuando hay sobrante). Sumar diffs de fila.
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

    private boolean shouldIncludeInPhysicalCount(KioscoKardexReportResponse.KioscoKardexRow kardexRow) {
        if (hasAssignedColor(kardexRow)) {
            return true;
        }
        if (ProductCinchoType.isPackagingProductCode(kardexRow != null ? kardexRow.getProductCode() : null)) {
            return true;
        }
        // Entradas de envío u otro movimiento del periodo: no depender solo de stock actual.
        return hasKardexActivity(kardexRow);
    }

    private boolean hasKardexActivity(KioscoKardexReportResponse.KioscoKardexRow kardexRow) {
        if (kardexRow == null) {
            return false;
        }
        return safeInt(kardexRow.getInventarioInicial()) > 0
                || safeInt(kardexRow.getInventarioFinal()) > 0
                || safeInt(kardexRow.getComprasAjustes()) > 0
                || safeInt(kardexRow.getAnulacionCompras()) > 0
                || safeInt(kardexRow.getEntradas()) > 0
                || safeInt(kardexRow.getVentas()) > 0
                || safeInt(kardexRow.getAnulacionVenta()) > 0
                || safeInt(kardexRow.getSalida()) > 0;
    }

    private static int safeInt(Integer value) {
        return value != null ? value : 0;
    }

    private boolean hasAssignedColor(KioscoKardexReportResponse.KioscoKardexRow kardexRow) {
        if (kardexRow == null || kardexRow.getColorId() == null) {
            return false;
        }
        String colorName = kardexRow.getColorName();
        return colorName != null && !colorName.isBlank();
    }

    private Map<String, Integer> resolveSystemSizesForReport(KioscoStockEntity stock) {
        if (stock == null) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(toSystemSizesMap(stock.getSizesData()));
    }

    /**
     * Agrega tallas que tuvieron kardex/envío en el periodo aunque {@code sizes_data} ya esté en 0.
     */
    private Map<String, Integer> enrichSizesWithMovementKeys(
            Map<String, Integer> systemSizes,
            Map<String, KioscoInventoryService.SizeKardexBucket> sizeKardex
    ) {
        Map<String, Integer> out = systemSizes != null ? new LinkedHashMap<>(systemSizes) : new LinkedHashMap<>();
        if (sizeKardex == null || sizeKardex.isEmpty()) {
            return out;
        }
        for (Map.Entry<String, KioscoInventoryService.SizeKardexBucket> e : sizeKardex.entrySet()) {
            String size = e.getKey();
            if (size == null || size.isBlank()) {
                continue;
            }
            KioscoInventoryService.SizeKardexBucket bucket = e.getValue();
            if (bucket == null || bucket.isEmpty()) {
                continue;
            }
            out.putIfAbsent(size, 0);
        }
        return out;
    }

    private int computePhysicalCountTotal(
            ProductEntity product,
            Map<String, BigDecimal> countedValues,
            Map<String, Integer> physicalSizes,
            Map<String, Map<String, Integer>> physicalSizesByLocation
    ) {
        int locationTotal = 0;
        for (String key : COUNT_LOCATION_KEYS) {
            locationTotal += countedValues.getOrDefault(key, BigDecimal.ZERO).intValue();
        }
        if (!CinchoProductUtils.isFossCinchoProduct(product)) {
            return locationTotal;
        }
        if (physicalSizesByLocation != null && !physicalSizesByLocation.isEmpty()) {
            int sizeTotal = physicalSizesByLocation.values().stream()
                    .filter(Objects::nonNull)
                    .flatMap(loc -> loc.values().stream())
                    .mapToInt(value -> value != null ? value : 0)
                    .sum();
            if (sizeTotal > 0) {
                return sizeTotal;
            }
        }
        if (physicalSizes != null && !physicalSizes.isEmpty()) {
            int sizeTotal = physicalSizes.values().stream().mapToInt(value -> value != null ? value : 0).sum();
            if (sizeTotal > 0) {
                return sizeTotal;
            }
        }
        return locationTotal;
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

    private String resolveDisplayCategoryKey(
            ProductEntity product,
            String productCode,
            Map<Long, ProductCategoryEntity> categoriesById
    ) {
        if (ProductCinchoType.isPackagingProductCode(productCode)) {
            return PACKAGING_CATEGORY_KEY;
        }
        Long categoryId = product != null ? product.getCategoryId() : null;
        if (categoryId == null) {
            return "NONE";
        }
        ProductCategoryEntity category = categoriesById.get(categoryId);
        if (category != null && isWalletCategory(category.getName()) && product != null) {
            String audience = ProductAudienceCategory.normalizeProductAudience(product.getAudienceCategory());
            return "WALLET:" + categoryId + ":" + audience;
        }
        return String.valueOf(categoryId);
    }

    private String resolveDisplayCategoryName(
            String categoryKey,
            Map<Long, ProductCategoryEntity> categoriesById
    ) {
        if (PACKAGING_CATEGORY_KEY.equals(categoryKey)) {
            return PACKAGING_CATEGORY_NAME;
        }
        if ("NONE".equals(categoryKey)) {
            return UNCATEGORIZED_LABEL;
        }
        if (categoryKey.startsWith("WALLET:")) {
            String[] parts = categoryKey.split(":");
            Long categoryId = Long.parseLong(parts[1]);
            String audience = parts.length > 2 ? parts[2] : ProductAudienceCategory.UNISEX;
            String categoryName = Optional.ofNullable(categoriesById.get(categoryId))
                    .map(ProductCategoryEntity::getName)
                    .orElse(UNCATEGORIZED_LABEL);
            return categoryName + " — " + resolveAudienceLabel(audience);
        }
        Long categoryId = Long.parseLong(categoryKey);
        return Optional.ofNullable(categoriesById.get(categoryId))
                .map(ProductCategoryEntity::getName)
                .orElse(UNCATEGORIZED_LABEL);
    }

    private Long resolveDisplayCategoryId(String categoryKey) {
        if (PACKAGING_CATEGORY_KEY.equals(categoryKey) || "NONE".equals(categoryKey)) {
            return null;
        }
        if (categoryKey.startsWith("WALLET:")) {
            return Long.parseLong(categoryKey.split(":")[1]);
        }
        return Long.valueOf(categoryKey);
    }

    private boolean isWalletCategory(String categoryName) {
        if (categoryName == null || categoryName.isBlank()) {
            return false;
        }
        return categoryName.toUpperCase(Locale.ROOT).contains("BILLETERA");
    }

    private String resolveAudienceLabel(String audience) {
        if (ProductAudienceCategory.DAMA.equals(audience)) {
            return "Dama";
        }
        if (ProductAudienceCategory.CABALLERO.equals(audience)) {
            return "Caballero";
        }
        return "Unisex";
    }

    private Optional<KioscoPhysicalCountEntity> resolvePreviousPhysicalCount(KioscoPhysicalCountEntity count) {
        if (count == null || count.getLocationId() == null || count.getPeriodFrom() == null) {
            return Optional.empty();
        }
        return countRepository.findFirstByLocationIdAndPeriodToLessThanAndIdNotOrderByPeriodToDescIdDesc(
                count.getLocationId(), count.getPeriodFrom(), count.getId());
    }

    private void applyPendingReturnSalidasForCount(
            KioscoPhysicalCountEntity count,
            List<KioscoKardexReportResponse.KioscoKardexRow> kardexRows,
            Map<Long, Map<String, KioscoInventoryService.SizeKardexBucket>> kardexByStockAndSize,
            Map<String, KioscoStockEntity> stockByKey
    ) {
        if (count == null || count.getId() == null) {
            return;
        }
        List<KioskExchangeSlipEntity> slips = exchangeSlipRepository.findByPhysicalCountId(count.getId());
        if (slips.isEmpty()) {
            slips = exchangeSlipRepository.findByKioskLocationIdOrderByCreatedAtDesc(count.getLocationId()).stream()
                    .filter(slip -> SLIP_TYPE_RETURN.equalsIgnoreCase(safeTrim(slip.getSlipType())))
                    .filter(slip -> slip.getPhysicalCountId() == null && isSlipCompletedInPeriod(slip, count))
                    .toList();
        }
        for (KioskExchangeSlipEntity slip : slips) {
            if (!SLIP_TYPE_RETURN.equalsIgnoreCase(safeTrim(slip.getSlipType()))) {
                continue;
            }
            if (!Boolean.TRUE.equals(slip.getApto())) {
                continue;
            }
            if (!isReturnSlipLinkedToCount(slip, count)) {
                continue;
            }
            if (slip.getReintegroMovementId() != null || STATUS_REINTEGRATED.equalsIgnoreCase(safeTrim(slip.getStatus()))) {
                continue;
            }
            if (!STATUS_PENDING_REINTEGRO.equalsIgnoreCase(safeTrim(slip.getStatus()))
                    && !STATUS_COMPLETED.equalsIgnoreCase(safeTrim(slip.getStatus()))) {
                continue;
            }
            int qty = slip.getReturnedQuantity() != null
                    ? slip.getReturnedQuantity().setScale(0, RoundingMode.HALF_UP).intValueExact()
                    : 0;
            if (qty <= 0) {
                continue;
            }
            String sizeKey = ProductInventorySizesJson.normalizeKey(slip.getReturnedSize());
            KioscoStockEntity stock = stockByKey.get(itemKey(slip.getReturnedProductId(), slip.getReturnedColorId()));
            if (stock == null) {
                continue;
            }
            applyReturnSalidaAdjustment(kardexRows, kardexByStockAndSize, stock, qty, sizeKey);
        }
    }

    private boolean isReturnSlipLinkedToCount(KioskExchangeSlipEntity slip, KioscoPhysicalCountEntity count) {
        if (slip == null || count == null) {
            return false;
        }
        if (Objects.equals(slip.getPhysicalCountId(), count.getId())) {
            return true;
        }
        return slip.getPhysicalCountId() == null && isSlipCompletedInPeriod(slip, count);
    }

    private boolean isSlipCompletedInPeriod(KioskExchangeSlipEntity slip, KioscoPhysicalCountEntity count) {
        if (slip.getCompletedAt() == null || count.getPeriodFrom() == null || count.getPeriodTo() == null) {
            return false;
        }
        LocalDate completedDate = slip.getCompletedAt().toLocalDate();
        return !completedDate.isBefore(count.getPeriodFrom()) && !completedDate.isAfter(count.getPeriodTo());
    }

    private void applyReturnSalidaAdjustment(
            List<KioscoKardexReportResponse.KioscoKardexRow> kardexRows,
            Map<Long, Map<String, KioscoInventoryService.SizeKardexBucket>> kardexByStockAndSize,
            KioscoStockEntity stock,
            int qty,
            String sizeKey
    ) {
        for (KioscoKardexReportResponse.KioscoKardexRow row : kardexRows) {
            if (!Objects.equals(row.getProductId(), stock.getProductId())
                    || !Objects.equals(row.getColorId(), stock.getColorId())) {
                continue;
            }
            row.setComprasAjustes(Math.max(0, row.getComprasAjustes() - qty));
            row.setSalida(row.getSalida() + qty);
            row.setSalidaDevolucion(row.getSalidaDevolucion() + qty);
            break;
        }

        Map<String, KioscoInventoryService.SizeKardexBucket> bySize =
                kardexByStockAndSize.computeIfAbsent(stock.getId(), k -> new LinkedHashMap<>());
        String bucketKey = sizeKey == null || sizeKey.isBlank() ? "" : sizeKey;
        KioscoInventoryService.SizeKardexBucket current =
                bySize.getOrDefault(bucketKey, KioscoInventoryService.SizeKardexBucket.empty());
        bySize.put(bucketKey, current.plus(-qty, 0, 0, 0, 0, qty, qty));
    }

    /**
     * Físico − Fin., descontando devoluciones a bodega que siguen en piso al contar
     * (registradas en Sal. pero aún no retiradas de vitrina/bodega kiosko).
     */
    static int computeDiferenciaConteo(int total, int inventarioFinal, int salidaDevolucion) {
        int raw = total - inventarioFinal;
        if (raw <= 0) {
            return raw;
        }
        return Math.max(0, raw - Math.max(0, salidaDevolucion));
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private static int kardexRowNetDelta(KioscoKardexReportResponse.KioscoKardexRow row) {
        return kardexRowNetDelta(row, row != null ? row.getEntradas() : 0);
    }

    private static int kardexRowNetDelta(KioscoKardexReportResponse.KioscoKardexRow row, int entradas) {
        if (row == null) {
            return 0;
        }
        return row.getComprasAjustes()
                - row.getAnulacionCompras()
                + entradas
                - row.getVentas()
                + row.getAnulacionVenta()
                - row.getSalida();
    }

    private static void applyPrePeriodEntradasToKardexBySize(
            Map<Long, Map<String, KioscoInventoryService.SizeKardexBucket>> kardexByStockAndSize,
            Map<Long, Map<String, Integer>> prePeriodEntradasByStockAndSize
    ) {
        if (prePeriodEntradasByStockAndSize == null || prePeriodEntradasByStockAndSize.isEmpty()) {
            return;
        }
        for (Map.Entry<Long, Map<String, Integer>> stockEntry : prePeriodEntradasByStockAndSize.entrySet()) {
            Map<String, KioscoInventoryService.SizeKardexBucket> bySize = kardexByStockAndSize
                    .computeIfAbsent(stockEntry.getKey(), k -> new LinkedHashMap<>());
            for (Map.Entry<String, Integer> sizeEntry : stockEntry.getValue().entrySet()) {
                KioscoInventoryService.SizeKardexBucket current = bySize.getOrDefault(
                        sizeEntry.getKey(), KioscoInventoryService.SizeKardexBucket.empty());
                bySize.put(sizeEntry.getKey(), current.plus(0, 0, sizeEntry.getValue(), 0, 0, 0));
            }
        }
    }

    private static Map<Long, Map<String, Integer>> subtractQuantityMapsByStock(
            Map<Long, Map<String, Integer>> baseByStock,
            Map<Long, Map<String, Integer>> subtractByStock
    ) {
        if (baseByStock == null || baseByStock.isEmpty()) {
            return Map.of();
        }
        Map<Long, Map<String, Integer>> adjusted = new LinkedHashMap<>();
        for (Map.Entry<Long, Map<String, Integer>> stockEntry : baseByStock.entrySet()) {
            Map<String, Integer> adjustedBySize = subtractQuantityMap(
                    stockEntry.getValue(),
                    subtractByStock != null
                            ? subtractByStock.getOrDefault(stockEntry.getKey(), Map.of())
                            : Map.of());
            if (!adjustedBySize.isEmpty()) {
                adjusted.put(stockEntry.getKey(), adjustedBySize);
            }
        }
        return adjusted;
    }

    private static Map<String, Integer> subtractQuantityMap(
            Map<String, Integer> base,
            Map<String, Integer> subtract
    ) {
        if (base == null || base.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : base.entrySet()) {
            int adjusted = Math.max(0, entry.getValue() - subtract.getOrDefault(entry.getKey(), 0));
            if (adjusted > 0) {
                result.put(entry.getKey(), adjusted);
            }
        }
        return result;
    }

    private List<KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow> expandRowsForDisplay(
            KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow base,
            ProductEntity product,
            KioscoStockEntity stock,
            Map<Long, Map<String, KioscoInventoryService.SizeKardexBucket>> kardexByStockAndSize,
            Map<String, Integer> openingBalanceBySize,
            String generalObservation,
            Map<String, String> sizeObservations
    ) {
        Map<String, KioscoInventoryService.SizeKardexBucket> sizeKardex = stock != null
                ? kardexByStockAndSize.getOrDefault(stock.getId(), Map.of())
                : Map.of();
        if (!shouldExpandRowBySize(product, base, sizeKardex)) {
            return List.of(applyRowObservation(base, generalObservation, null));
        }
        List<String> sizeKeys = collectSizeKeysForRow(base, sizeKardex.keySet());
        if (sizeKeys.isEmpty()) {
            return List.of(applyRowObservation(base, generalObservation, null));
        }
        boolean hasSizedMovements = sizeKardex.entrySet().stream()
                .anyMatch(e -> e.getKey() != null && !e.getKey().isBlank() && !e.getValue().isEmpty());

        List<KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow> expanded = new ArrayList<>();
        for (int i = 0; i < sizeKeys.size(); i++) {
            String size = sizeKeys.get(i);
            boolean first = i == 0;
            KioscoInventoryService.SizeKardexBucket bucket;
            if (hasSizedMovements) {
                bucket = sizeKardex.getOrDefault(size, KioscoInventoryService.SizeKardexBucket.empty());
                KioscoInventoryService.SizeKardexBucket unallocated =
                        sizeKardex.getOrDefault("", KioscoInventoryService.SizeKardexBucket.empty());
                if (first && !unallocated.isEmpty()) {
                    bucket = bucket.plus(
                            unallocated.comprasAjustes,
                            unallocated.anulacionCompras,
                            unallocated.entradas,
                            unallocated.ventas,
                            unallocated.anulacionVenta,
                            unallocated.salida,
                            unallocated.salidaDevolucion
                    );
                }
            } else {
                bucket = first ? bucketFromAggregateRow(base) : KioscoInventoryService.SizeKardexBucket.empty();
            }
            // Inv. inicial = cierre del conteo físico anterior (0 si es el primer corte).
            int inventarioInicial = Math.max(0, openingBalanceBySize.getOrDefault(size, 0));
            int inventarioFinal = Math.max(0, inventarioInicial + bucket.netDelta());
            expanded.add(buildExpandedRowForSize(
                    base, product, size, bucket, inventarioInicial, inventarioFinal, sizeObservations));
        }
        return expanded;
    }

    private static KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow applyRowObservation(
            KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow row,
            String generalObservation,
            String sizeObservation
    ) {
        String observation = sizeObservation != null ? sizeObservation : generalObservation;
        if (observation == null || observation.isBlank()) {
            return row;
        }
        return row.toBuilder().observation(observation.trim()).build();
    }

    private static KioscoInventoryService.SizeKardexBucket bucketFromAggregateRow(
            KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow base
    ) {
        return KioscoInventoryService.SizeKardexBucket.of(
                base.getComprasAjustes(),
                base.getAnulacionCompras(),
                base.getEntradas(),
                base.getVentas(),
                base.getAnulacionVenta(),
                base.getSalida(),
                base.getSalidaDevolucion()
        );
    }

    private boolean shouldExpandRowBySize(
            ProductEntity product,
            KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow base,
            Map<String, KioscoInventoryService.SizeKardexBucket> sizeKardex
    ) {
        if (base.isPackaging()) {
            return false;
        }
        boolean hasSizes = hasSizeBreakdown(base) || hasSizedKardexActivity(sizeKardex);
        if (!hasSizes) {
            return false;
        }
        return isCinchoForSizeExpansion(product, base);
    }

    private boolean hasSizedKardexActivity(Map<String, KioscoInventoryService.SizeKardexBucket> sizeKardex) {
        if (sizeKardex == null || sizeKardex.isEmpty()) {
            return false;
        }
        return sizeKardex.entrySet().stream()
                .anyMatch(e -> e.getKey() != null && !e.getKey().isBlank()
                        && e.getValue() != null && !e.getValue().isEmpty());
    }

    private boolean hasSizeBreakdown(KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow base) {
        if (base.getSystemSizes() != null && !base.getSystemSizes().isEmpty()) {
            return true;
        }
        if (base.getPhysicalSizes() != null && !base.getPhysicalSizes().isEmpty()) {
            return true;
        }
        return base.getPhysicalSizesByLocation() != null && !base.getPhysicalSizesByLocation().isEmpty();
    }

    private boolean isCinchoForSizeExpansion(
            ProductEntity product,
            KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow base
    ) {
        if (product != null && CinchoProductUtils.isFossCinchoProduct(product)) {
            return true;
        }
        if (base.getCinchoType() != null) {
            return true;
        }
        if (product != null && ProductCinchoType.normalizeCinchoType(product.getCinchoType()) != null) {
            return true;
        }
        return product != null && CinchoProductUtils.isMesaCinchosProduct(product);
    }

    private List<String> collectSizeKeysForRow(
            KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow base,
            Set<String> extraSizeKeys
    ) {
        Set<String> keys = new TreeSet<>(this::compareSizeKeys);
        if (base.getSystemSizes() != null) {
            keys.addAll(base.getSystemSizes().keySet());
        }
        if (base.getPhysicalSizes() != null) {
            keys.addAll(base.getPhysicalSizes().keySet());
        }
        if (base.getPhysicalSizesByLocation() != null) {
            for (Map<String, Integer> locSizes : base.getPhysicalSizesByLocation().values()) {
                if (locSizes != null) {
                    keys.addAll(locSizes.keySet());
                }
            }
        }
        if (extraSizeKeys != null) {
            for (String key : extraSizeKeys) {
                if (key != null && !key.isBlank()) {
                    keys.add(key);
                }
            }
        }
        return new ArrayList<>(keys);
    }

    private KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow buildExpandedRowForSize(
            KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow base,
            ProductEntity product,
            String size,
            KioscoInventoryService.SizeKardexBucket kardex,
            int inventarioInicial,
            int inventarioFinal,
            Map<String, String> sizeObservations
    ) {
        Map<String, Integer> counts = resolveCountsForSize(base, product, size);
        int total = resolvePhysicalTotalForSize(base, product, size, counts);
        KioscoInventoryService.SizeKardexBucket bucket =
                kardex != null ? kardex : KioscoInventoryService.SizeKardexBucket.empty();

        Map<String, Integer> singleSystemSize = inventarioFinal > 0 ? Map.of(size, inventarioFinal) : null;
        Map<String, Integer> singlePhysicalSize = null;
        if (base.getPhysicalSizes() != null && base.getPhysicalSizes().containsKey(size)) {
            singlePhysicalSize = Map.of(size, base.getPhysicalSizes().get(size));
        }

        return applyRowObservation(
                base.toBuilder()
                .sizeLabel(size)
                .sizesSummary(size)
                .physicalSizesSummary(String.valueOf(total))
                .systemSizes(singleSystemSize)
                .physicalSizes(singlePhysicalSize)
                .physicalSizesByLocation(buildSingleSizeByLocation(base.getPhysicalSizesByLocation(), size))
                .inventarioInicial(inventarioInicial)
                .comprasAjustes(bucket.comprasAjustes)
                .anulacionCompras(bucket.anulacionCompras)
                .entradas(bucket.entradas)
                .ventas(bucket.ventas)
                .anulacionVenta(bucket.anulacionVenta)
                .salida(bucket.salida)
                .salidaDevolucion(bucket.salidaDevolucion)
                .inventarioFinal(inventarioFinal)
                .counts(counts)
                .total(total)
                .diferencia(computeDiferenciaConteo(total, inventarioFinal, bucket.salidaDevolucion))
                .build(),
                null,
                sizeObservations != null ? sizeObservations.get(size) : null
        );
    }

    private Map<String, Integer> resolveCountsForSize(
            KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow base,
            ProductEntity product,
            String size
    ) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        boolean foss = CinchoProductUtils.isFossCinchoProduct(product);

        if (foss && base.getPhysicalSizesByLocation() != null) {
            for (String loc : CINCHO_SIZE_LOCATION_KEYS) {
                Map<String, Integer> locSizes = base.getPhysicalSizesByLocation().get(loc);
                counts.put(loc, locSizes != null ? locSizes.getOrDefault(size, 0) : 0);
            }
            for (String key : COUNT_LOCATION_KEYS) {
                counts.putIfAbsent(key, 0);
            }
            return counts;
        }

        int physicalForSize = base.getPhysicalSizes() != null
                ? base.getPhysicalSizes().getOrDefault(size, 0) : 0;
        if (physicalForSize > 0) {
            for (String key : COUNT_LOCATION_KEYS) {
                counts.put(key, "E".equals(key) ? physicalForSize : 0);
            }
            return counts;
        }

        Map<String, Integer> baseCounts = base.getCounts() != null ? base.getCounts() : Map.of();
        if (collectSizeKeysForRow(base, null).size() == 1) {
            for (String key : COUNT_LOCATION_KEYS) {
                counts.put(key, baseCounts.getOrDefault(key, 0));
            }
            return counts;
        }

        for (String key : COUNT_LOCATION_KEYS) {
            counts.put(key, 0);
        }
        return counts;
    }

    private int resolvePhysicalTotalForSize(
            KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow base,
            ProductEntity product,
            String size,
            Map<String, Integer> counts
    ) {
        if (CinchoProductUtils.isFossCinchoProduct(product) && base.getPhysicalSizesByLocation() != null) {
            int vitrine = Optional.ofNullable(base.getPhysicalSizesByLocation().get("E"))
                    .map(m -> m.getOrDefault(size, 0)).orElse(0);
            int warehouse = Optional.ofNullable(base.getPhysicalSizesByLocation().get("BO"))
                    .map(m -> m.getOrDefault(size, 0)).orElse(0);
            if (vitrine + warehouse > 0) {
                return vitrine + warehouse;
            }
        }
        if (base.getPhysicalSizes() != null) {
            int physical = base.getPhysicalSizes().getOrDefault(size, 0);
            if (physical > 0) {
                return physical;
            }
        }
        return counts.values().stream().mapToInt(value -> value != null ? value : 0).sum();
    }

    private Map<String, Map<String, Integer>> buildSingleSizeByLocation(
            Map<String, Map<String, Integer>> byLocation,
            String size
    ) {
        if (byLocation == null) {
            return null;
        }
        Map<String, Map<String, Integer>> result = new LinkedHashMap<>();
        for (String loc : CINCHO_SIZE_LOCATION_KEYS) {
            Map<String, Integer> locSizes = byLocation.get(loc);
            if (locSizes != null && locSizes.containsKey(size)) {
                int qty = locSizes.get(size);
                if (qty > 0) {
                    result.put(loc, Map.of(size, qty));
                }
            }
        }
        return result.isEmpty() ? null : result;
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
