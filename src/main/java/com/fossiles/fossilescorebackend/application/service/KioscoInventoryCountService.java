package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.KioscoNotificationRecipientRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioscoPhysicalCountItemUpsertRequest;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoKardexReportResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoNotificationRecipientResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoPhysicalCountItemSyncResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoPhysicalCountLiveSessionResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoPhysicalCountPresenceResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoPhysicalCountReportResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoPhysicalCountSessionSummaryResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.util.ProductAudienceCategory;
import com.fossiles.fossilescorebackend.application.util.ProductCinchoType;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoNotificationRecipientEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoPhysicalCountEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoPhysicalCountItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoPhysicalCountPresenceEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoPhysicalCountStatus;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoStockEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskExchangeSlipEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductCategoryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoNotificationRecipientRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoPhysicalCountItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoPhysicalCountPresenceRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoPhysicalCountRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoStockRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskExchangeSlipRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductCategoryRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    /** Segundos sin heartbeat antes de considerar desconectado en la sesión en vivo. */
    public static final int PRESENCE_TTL_SECONDS = 90;

    private final KioscoPhysicalCountRepository countRepository;
    private final KioscoPhysicalCountItemRepository itemRepository;
    private final KioscoPhysicalCountPresenceRepository presenceRepository;
    private final KioscoNotificationRecipientRepository notificationRecipientRepository;
    private final KioscoInventoryService kioscoInventoryService;
    private final KioscoStockRepository kioscoStockRepository;
    private final KioskExchangeSlipRepository exchangeSlipRepository;
    private final LocationRepository locationRepository;
    private final ProductRepository productRepository;
    private final ProductCategoryRepository productCategoryRepository;
    private final UserRepository userRepository;
    private final SecurityUtil securityUtil;
    private final ObjectMapper objectMapper;

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
        KioscoPhysicalCountEntity count = findCountOrThrow(countId);
        KioscoPhysicalCountReportResponse report = buildReport(count, null);
        refreshFirstCountClosingSnapshotIfNeeded(count, report);
        return report;
    }

    /**
     * Si el conteo cerrado es el primero del kiosko, reescribe {@code closing_balances_data}
     * con el Fin. recalculado (Ini.=0) para que el siguiente no herede un Fin. inflado.
     */
    private void refreshFirstCountClosingSnapshotIfNeeded(
            KioscoPhysicalCountEntity count,
            KioscoPhysicalCountReportResponse report
    ) {
        if (count == null || report == null) {
            return;
        }
        if (count.getStatus() != KioscoPhysicalCountStatus.CERRADO) {
            return;
        }
        if (resolvePreviousClosedPhysicalCount(count).isPresent()) {
            return;
        }
        String serialized = serializeClosingBalances(extractClosingBalances(report));
        Integer maxAbsDiff = report.getMaxAbsDiff();
        if (Objects.equals(count.getClosingBalancesData(), serialized)
                && Objects.equals(count.getMaxAbsDiff(), maxAbsDiff)) {
            return;
        }
        count.setClosingBalancesData(serialized);
        count.setMaxAbsDiff(maxAbsDiff);
        countRepository.save(count);
        log.info(
                "PHYSICAL_COUNT_REFRESH_FIRST_CLOSING countId={} locationId={} maxAbsDiff={}",
                count.getId(), count.getLocationId(), maxAbsDiff);
    }

    /**
     * Heartbeat + presencia + cambios de ítems desde {@code since} (colaboración en vivo del conteo).
     */
    public KioscoPhysicalCountLiveSessionResponse pollLiveSession(Long countId, LocalDateTime since)
            throws BusinessException, ResourceNotFoundException {
        KioscoPhysicalCountEntity count = findCountOrThrow(countId);
        Long userId = resolveCurrentUserId();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime presenceCutoff = now.minusSeconds(PRESENCE_TTL_SECONDS);

        List<KioscoPhysicalCountPresenceResponse> participants = List.of();
        List<KioscoPhysicalCountItemSyncResponse> items = List.of();

        if (count.getStatus() == KioscoPhysicalCountStatus.DRAFT) {
            presenceRepository.deleteStaleForCount(countId, presenceCutoff);
            KioscoPhysicalCountPresenceEntity presence = presenceRepository
                    .findByCountIdAndUserId(countId, userId)
                    .orElseGet(() -> KioscoPhysicalCountPresenceEntity.builder()
                            .countId(countId)
                            .userId(userId)
                            .build());
            presence.setLastSeenAt(now);
            presenceRepository.save(presence);

            participants = presenceRepository
                    .findByCountIdAndLastSeenAtAfterOrderByLastSeenAtDesc(countId, presenceCutoff)
                    .stream()
                    .map(p -> KioscoPhysicalCountPresenceResponse.builder()
                            .userId(p.getUserId())
                            .userName(resolveUserDisplayName(p.getUserId()))
                            .lastSeenAt(p.getLastSeenAt())
                            .self(Objects.equals(p.getUserId(), userId))
                            .build())
                    .collect(Collectors.toList());

            LocalDateTime itemSince = since != null ? since : now.minusDays(1);
            items = itemRepository.findByCountIdAndUpdatedAtAfter(countId, itemSince).stream()
                    .map(this::toItemSyncResponse)
                    .collect(Collectors.toList());
        }

        return KioscoPhysicalCountLiveSessionResponse.builder()
                .serverTime(now)
                .participants(participants)
                .items(items)
                .build();
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
            if (req.getHardwareLocationCounts() != null) {
                item.setHardwareLocationCountsData(ProductInventorySizesJson.serializeByLocation(
                        normalizeHardwareLocationCounts(req.getHardwareLocationCounts())));
                syncCountsFromHardwareLocationCounts(item, req.getHardwareLocationCounts());
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
                    || req.getPhysicalSizesByLocation() != null
                    || req.getHardwareLocationCounts() != null;
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
     * Guarda observaciones generales de la sesión (hallazgos al contar).
     * Editable hasta cerrar; no confundir con notes de revisión ni observation por ítem.
     */
    public KioscoPhysicalCountReportResponse updateObservations(Long countId, String observations)
            throws BusinessException, ResourceNotFoundException {
        KioscoPhysicalCountEntity count = findCountOrThrow(countId);
        if (count.getStatus() == KioscoPhysicalCountStatus.CERRADO) {
            throw new BusinessException("El conteo está cerrado y no admite más cambios.");
        }
        String trimmed = observations != null ? observations.trim() : "";
        count.setObservations(trimmed.isEmpty() ? null : trimmed);
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
        KioscoPhysicalCountReportResponse report = buildReport(count);
        count.setClosingBalancesData(serializeClosingBalances(extractClosingBalances(report)));
        count.setMaxAbsDiff(report.getMaxAbsDiff());
        countRepository.save(count);
        return report;
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

        // Ini. = Fin. del último conteo CERRADO anterior/contiguo (no replay floored del ledger).
        Optional<KioscoPhysicalCountEntity> previousClosedCount = resolvePreviousClosedPhysicalCount(count);
        PreviousClosingBalances previousClosing = previousClosedCount
                .map(this::loadPreviousClosingBalances)
                .orElse(null);

        // Si el periodo se solapa con el cerrado anterior (mismo día de corte), el kardex arranca
        // el día siguiente al cierre para no contar dos veces los movimientos del día compartido.
        // Primer conteo: ampliar desde el primer movimiento del kiosko (Ini.=0; historial en columnas).
        LocalDate effectiveKardexFrom = count.getPeriodFrom();
        if (previousClosedCount.isPresent()) {
            LocalDate dayAfterPrev = previousClosedCount.get().getPeriodTo().plusDays(1);
            if (dayAfterPrev.isAfter(effectiveKardexFrom)) {
                effectiveKardexFrom = dayAfterPrev;
            }
        } else {
            Optional<LocalDate> earliestMovement = kioscoInventoryService
                    .findEarliestMovementDate(count.getLocationId());
            if (earliestMovement.isPresent() && earliestMovement.get().isBefore(effectiveKardexFrom)) {
                effectiveKardexFrom = earliestMovement.get();
            }
        }
        Map<String, List<KioscoStockEntity>> stocksByProductColor = kioscoStockRepository
                .findByLocationIdOrderByProductIdAscColorIdAscHardwareConditionAsc(count.getLocationId()).stream()
                .collect(Collectors.groupingBy(s -> itemKey(s.getProductId(), s.getColorId())));
        Map<String, KioscoStockEntity> stockByKey = new LinkedHashMap<>();
        stocksByProductColor.forEach((key, stocks) -> stockByKey.put(key, stocks.get(0)));
        if (!isSubcount) {
            stocksByProductColor.values().stream()
                    .flatMap(List::stream)
                    .forEach(kioscoInventoryService::syncFossCurrentStockFromSizes);
        }

        List<KioscoKardexReportResponse.KioscoKardexRow> rawKardexRows;
        Map<Long, Map<String, KioscoInventoryService.SizeKardexBucket>> kardexByStockAndSize;
        if (effectiveKardexFrom.isAfter(kardexTo)) {
            rawKardexRows = kioscoInventoryService.buildKardexRows(
                    count.getLocationId(), count.getPeriodFrom(), count.getPeriodFrom(), true, finAsOf, count.getId())
                    .stream()
                    .map(this::zeroPeriodKardexColumns)
                    .collect(Collectors.toList());
            kardexByStockAndSize = Map.of();
        } else {
            rawKardexRows = kioscoInventoryService.buildKardexRows(
                    count.getLocationId(), effectiveKardexFrom, kardexTo, true, finAsOf, count.getId());
            kardexByStockAndSize = kioscoInventoryService.buildKardexByStockAndSize(
                    count.getLocationId(), effectiveKardexFrom, kardexTo, count.getId());
        }
        Map<String, Map<String, Integer>> inventarioFinalByHardwareLookup =
                buildInventarioFinalByHardwareLookup(rawKardexRows);
        List<KioscoKardexReportResponse.KioscoKardexRow> kardexRows =
                mergeKardexRowsByProductColor(rawKardexRows);
        applyPendingReturnSalidasForCount(count, kardexRows, kardexByStockAndSize, stockByKey);

        LocalDateTime periodStart = count.getPeriodFrom().atStartOfDay();
        LocalDateTime openingCutoffExclusive = previousClosedCount
                .map(c -> c.getPeriodTo().plusDays(1).atStartOfDay())
                .orElse(periodStart);
        // Primer conteo: Ini.=0 (sin ledger). Siguientes: Ini.=Fin. del CERRADO (previousClosing).
        Map<Long, Integer> openingBalanceByStockId = Map.of();
        Map<Long, Map<String, Integer>> openingBalanceByStockAndSize = Map.of();

        // Solo el hueco entre conteos (si periodFrom > periodTo anterior) va a Ent.; el primer conteo no
        // (su historial pre-periodFrom ya está en el kardex ampliado).
        Map<Long, Integer> gapEntradasByStockId = previousClosedCount.isPresent()
                ? kioscoInventoryService.computePrePeriodEntradasByStockId(
                        count.getLocationId(), openingCutoffExclusive, periodStart)
                : Map.of();
        Map<Long, Map<String, Integer>> gapEntradasByStockAndSize = previousClosedCount.isPresent()
                ? kioscoInventoryService.computePrePeriodEntradasByStockAndSize(
                        count.getLocationId(), openingCutoffExclusive, periodStart)
                : Map.of();
        applyPrePeriodEntradasToKardexBySize(kardexByStockAndSize, gapEntradasByStockAndSize);

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
            Map<String, Map<String, Integer>> hardwareLocationCounts = toHardwareLocationCountsMap(
                    item != null ? item.getHardwareLocationCountsData() : null);

            String productColorKey = itemKey(kardexRow.getProductId(), kardexRow.getColorId());
            List<KioscoStockEntity> stocksForRow = stocksByProductColor.getOrDefault(productColorKey, List.of());
            KioscoStockEntity stock = stockByKey.get(productColorKey);
            Map<String, KioscoInventoryService.SizeKardexBucket> sizeKardexForStock = mergeSizeKardexForStocks(
                    stocksForRow, kardexByStockAndSize);
            // Incluir tallas con movimiento/envío en el periodo aunque el stock actual sea 0.
            // Unir sizes_data de todos los herrajes (NUEVO+VIEJO); no solo el primer stock.
            Map<String, Integer> systemSizes = enrichSizesWithMovementKeys(
                    resolveSystemSizesForStocks(stocksForRow), sizeKardexForStock);
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

            Map<String, Integer> inventarioFinalByHardware = inventarioFinalByHardwareLookup.getOrDefault(
                    productColorKey, Map.of());

            int gapEntradas = stocksForRow.stream()
                    .mapToInt(s -> gapEntradasByStockId.getOrDefault(s.getId(), 0))
                    .sum();
            // Primer conteo: Ini.=0. Siguientes: Fin. del CERRADO anterior (0 si SKU nuevo).
            int inventarioInicial = resolveOpeningInventarioInicial(productColorKey, previousClosing, 0);
            // Ent. = periodo (+ hueco entre conteos). En el primer conteo el kardex ya incluye historial previo.
            int entradas = kardexRow.getEntradas() + gapEntradas;
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
                    .hardwareCondition(stocksForRow.size() == 1 && stock != null
                            ? stock.getHardwareCondition() : null)
                    .inventarioFinalByHardware(inventarioFinalByHardware.isEmpty() ? null : inventarioFinalByHardware)
                    .hardwareLocationCounts(hardwareLocationCounts)
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
                    .diferencia(computeDiferenciaConteo(
                            total,
                            inventarioFinal,
                            salidaDevolucionForDiff(
                                    ProductCinchoType.isPackagingProductCode(kardexRow.getProductCode()),
                                    kardexRow.getSalidaDevolucion())))
                    .build();
            Map<String, Integer> openingBalanceBySize = resolveOpeningBalanceBySize(
                    productColorKey, previousClosing, stocksForRow, openingBalanceByStockAndSize);
            List<KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow> displayRows = isSubcount
                    ? List.of(applyRowObservation(row, generalObservation, null))
                    : expandRowsForDisplay(
                            row, product, sizeKardexForStock, openingBalanceBySize,
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
                .observations(count.getObservations())
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

    /** Suma sizes_data de todos los stocks del producto/color (p.ej. NUEVO + VIEJO). */
    private Map<String, Integer> resolveSystemSizesForStocks(List<KioscoStockEntity> stocks) {
        Map<String, Integer> merged = new LinkedHashMap<>();
        if (stocks == null) {
            return merged;
        }
        for (KioscoStockEntity stock : stocks) {
            for (Map.Entry<String, Integer> entry : resolveSystemSizesForReport(stock).entrySet()) {
                merged.merge(entry.getKey(), Math.max(0, entry.getValue()), Integer::sum);
            }
        }
        return merged;
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

    private Optional<KioscoPhysicalCountEntity> resolvePreviousClosedPhysicalCount(KioscoPhysicalCountEntity count) {
        if (count == null || count.getLocationId() == null || count.getPeriodFrom() == null) {
            return Optional.empty();
        }
        Long excludeId = count.getId() != null ? count.getId() : -1L;
        return countRepository.findFirstByLocationIdAndStatusAndPeriodToLessThanEqualAndIdNotOrderByPeriodToDescIdDesc(
                count.getLocationId(),
                KioscoPhysicalCountStatus.CERRADO,
                count.getPeriodFrom(),
                excludeId);
    }

    private PreviousClosingBalances loadPreviousClosingBalances(KioscoPhysicalCountEntity previousCount) {
        PreviousClosingBalances fromSnapshot = deserializeClosingBalances(previousCount.getClosingBalancesData());
        if (fromSnapshot != null) {
            return fromSnapshot;
        }
        try {
            return extractClosingBalances(buildReport(previousCount));
        } catch (BusinessException | ResourceNotFoundException e) {
            throw new IllegalStateException(
                    "No se pudo cargar el Fin. del conteo cerrado #" + previousCount.getId()
                            + " para usarlo como Ini. del siguiente periodo",
                    e);
        }
    }

    private KioscoKardexReportResponse.KioscoKardexRow zeroPeriodKardexColumns(
            KioscoKardexReportResponse.KioscoKardexRow row
    ) {
        if (row == null) {
            return null;
        }
        return row.toBuilder()
                .comprasAjustes(0)
                .anulacionCompras(0)
                .entradas(0)
                .ventas(0)
                .anulacionVenta(0)
                .salida(0)
                .salidaDevolucion(0)
                .build();
    }

    String serializeClosingBalances(PreviousClosingBalances balances) {
        if (balances == null) {
            return null;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("byProductColor", balances.byProductColor);
            payload.put("byProductColorAndSize", balances.byProductColorAndSize);
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo serializar el snapshot de Fin. del conteo", e);
        }
    }

    PreviousClosingBalances deserializeClosingBalances(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(json, new TypeReference<>() {});
            Map<String, Integer> byProductColor = new LinkedHashMap<>();
            Object rawByPc = payload.get("byProductColor");
            if (rawByPc instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getKey() == null || entry.getValue() == null) {
                        continue;
                    }
                    byProductColor.put(String.valueOf(entry.getKey()), toNonNegInt(entry.getValue()));
                }
            }
            Map<String, Map<String, Integer>> byProductColorAndSize = new LinkedHashMap<>();
            Object rawBySize = payload.get("byProductColorAndSize");
            if (rawBySize instanceof Map<?, ?> outer) {
                for (Map.Entry<?, ?> entry : outer.entrySet()) {
                    if (entry.getKey() == null || !(entry.getValue() instanceof Map<?, ?> inner)) {
                        continue;
                    }
                    Map<String, Integer> sizes = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> sizeEntry : inner.entrySet()) {
                        if (sizeEntry.getKey() == null || sizeEntry.getValue() == null) {
                            continue;
                        }
                        sizes.put(String.valueOf(sizeEntry.getKey()), toNonNegInt(sizeEntry.getValue()));
                    }
                    byProductColorAndSize.put(String.valueOf(entry.getKey()), sizes);
                }
            }
            return new PreviousClosingBalances(byProductColor, byProductColorAndSize);
        } catch (Exception e) {
            log.warn("closing_balances_data inválido; se recalculará el Fin. del conteo cerrado: {}", e.getMessage());
            return null;
        }
    }

    private static int toNonNegInt(Object value) {
        if (value instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        try {
            return Math.max(0, Integer.parseInt(String.valueOf(value)));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Extrae Fin. del reporte anterior por producto+color (y por talla si aplica).
     * Incluye Fin.=0 para que el siguiente Ini. no caiga al replay del ledger.
     */
    static PreviousClosingBalances extractClosingBalances(KioscoPhysicalCountReportResponse report) {
        Map<String, Integer> byProductColor = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> byProductColorAndSize = new LinkedHashMap<>();
        if (report == null || report.getCategories() == null) {
            return new PreviousClosingBalances(byProductColor, byProductColorAndSize);
        }
        for (KioscoPhysicalCountReportResponse.KioscoPhysicalCountCategoryGroup category : report.getCategories()) {
            if (category == null || category.getRows() == null) {
                continue;
            }
            for (KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow row : category.getRows()) {
                if (row == null || row.getProductId() == null) {
                    continue;
                }
                String key = row.getProductId() + ":" + (row.getColorId() != null ? row.getColorId() : "");
                int fin = Math.max(0, row.getInventarioFinal());
                byProductColor.merge(key, fin, Integer::sum);
                String sizeLabel = row.getSizeLabel();
                if (sizeLabel != null && !sizeLabel.isBlank()) {
                    byProductColorAndSize
                            .computeIfAbsent(key, k -> new LinkedHashMap<>())
                            .merge(sizeLabel.trim(), fin, Integer::sum);
                }
            }
        }
        return new PreviousClosingBalances(byProductColor, byProductColorAndSize);
    }

    static int resolveOpeningInventarioInicial(
            String productColorKey,
            PreviousClosingBalances previousClosing,
            int ledgerOpening
    ) {
        // Primer conteo (sin CERRADO previo): Ini.=0; el stock entra por Ent./movimientos del kardex.
        if (previousClosing == null) {
            return 0;
        }
        if (previousClosing.byProductColor.containsKey(productColorKey)) {
            return Math.max(0, previousClosing.byProductColor.get(productColorKey));
        }
        // SKU nuevo respecto al conteo cerrado: no hereda ledger pre-cierre; arranca en 0
        // (las entradas del hueco van a Ent.).
        return 0;
    }

    private static Map<String, Integer> resolveOpeningBalanceBySize(
            String productColorKey,
            PreviousClosingBalances previousClosing,
            List<KioscoStockEntity> stocksForRow,
            Map<Long, Map<String, Integer>> openingBalanceByStockAndSize
    ) {
        if (previousClosing != null) {
            Map<String, Integer> fromPrevious = previousClosing.byProductColorAndSize.get(productColorKey);
            if (fromPrevious != null) {
                return fromPrevious;
            }
            if (previousClosing.byProductColor.containsKey(productColorKey)) {
                return Map.of();
            }
            return Map.of();
        }
        // Primer conteo: sin Ini. por talla desde ledger.
        return Map.of();
    }

    static final class PreviousClosingBalances {
        final Map<String, Integer> byProductColor;
        final Map<String, Map<String, Integer>> byProductColorAndSize;

        PreviousClosingBalances(
                Map<String, Integer> byProductColor,
                Map<String, Map<String, Integer>> byProductColorAndSize
        ) {
            this.byProductColor = byProductColor != null ? byProductColor : Map.of();
            this.byProductColorAndSize = byProductColorAndSize != null ? byProductColorAndSize : Map.of();
        }
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

    /** Empaques SUM-: diferencia simple físico − Fin. (sin ajuste por devolución a bodega). */
    static int salidaDevolucionForDiff(boolean packaging, int salidaDevolucion) {
        return packaging ? 0 : salidaDevolucion;
    }

    private List<KioscoKardexReportResponse.KioscoKardexRow> mergeKardexRowsByProductColor(
            List<KioscoKardexReportResponse.KioscoKardexRow> rows
    ) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        Map<String, KioscoKardexReportResponse.KioscoKardexRow> merged = new LinkedHashMap<>();
        for (KioscoKardexReportResponse.KioscoKardexRow row : rows) {
            String key = itemKey(row.getProductId(), row.getColorId());
            KioscoKardexReportResponse.KioscoKardexRow existing = merged.get(key);
            if (existing == null) {
                merged.put(key, row);
                continue;
            }
            merged.put(key, KioscoKardexReportResponse.KioscoKardexRow.builder()
                    .productId(existing.getProductId())
                    .productCode(existing.getProductCode())
                    .productName(existing.getProductName())
                    .colorId(existing.getColorId())
                    .colorName(existing.getColorName())
                    .audienceCategory(existing.getAudienceCategory())
                    .cinchoType(existing.getCinchoType())
                    .inventarioInicial(existing.getInventarioInicial() + row.getInventarioInicial())
                    .comprasAjustes(existing.getComprasAjustes() + row.getComprasAjustes())
                    .anulacionCompras(existing.getAnulacionCompras() + row.getAnulacionCompras())
                    .entradas(existing.getEntradas() + row.getEntradas())
                    .ventas(existing.getVentas() + row.getVentas())
                    .anulacionVenta(existing.getAnulacionVenta() + row.getAnulacionVenta())
                    .salida(existing.getSalida() + row.getSalida())
                    .salidaDevolucion(existing.getSalidaDevolucion() + row.getSalidaDevolucion())
                    .inventarioFinal(existing.getInventarioFinal() + row.getInventarioFinal())
                    .build());
        }
        return new ArrayList<>(merged.values());
    }

    private Map<String, Map<String, Integer>> buildInventarioFinalByHardwareLookup(
            List<KioscoKardexReportResponse.KioscoKardexRow> rows
    ) {
        Map<String, Map<String, Integer>> out = new LinkedHashMap<>();
        if (rows == null) {
            return out;
        }
        for (KioscoKardexReportResponse.KioscoKardexRow row : rows) {
            String hardware = row.getHardwareCondition();
            if (hardware == null || hardware.isBlank()) {
                continue;
            }
            String key = itemKey(row.getProductId(), row.getColorId());
            out.computeIfAbsent(key, k -> new LinkedHashMap<>())
                    .merge(hardware, row.getInventarioFinal(), Integer::sum);
        }
        return out;
    }

    private Map<String, KioscoInventoryService.SizeKardexBucket> mergeSizeKardexForStocks(
            List<KioscoStockEntity> stocks,
            Map<Long, Map<String, KioscoInventoryService.SizeKardexBucket>> kardexByStockAndSize
    ) {
        Map<String, KioscoInventoryService.SizeKardexBucket> merged = new LinkedHashMap<>();
        if (stocks == null) {
            return merged;
        }
        for (KioscoStockEntity stock : stocks) {
            Map<String, KioscoInventoryService.SizeKardexBucket> part =
                    kardexByStockAndSize.getOrDefault(stock.getId(), Map.of());
            for (Map.Entry<String, KioscoInventoryService.SizeKardexBucket> entry : part.entrySet()) {
                String sizeKey = entry.getKey();
                KioscoInventoryService.SizeKardexBucket bucket = entry.getValue();
                if (bucket == null || bucket.isEmpty()) {
                    continue;
                }
                KioscoInventoryService.SizeKardexBucket current =
                        merged.getOrDefault(sizeKey, KioscoInventoryService.SizeKardexBucket.empty());
                merged.put(sizeKey, current.plus(
                        bucket.comprasAjustes,
                        bucket.anulacionCompras,
                        bucket.entradas,
                        bucket.ventas,
                        bucket.anulacionVenta,
                        bucket.salida,
                        bucket.salidaDevolucion
                ));
            }
        }
        return merged;
    }

    private Map<String, Map<String, Integer>> toHardwareLocationCountsMap(String json) {
        Map<String, Map<String, Integer>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, BigDecimal>> locEntry
                : ProductInventorySizesJson.parseByLocation(json).entrySet()) {
            Map<String, Integer> hardwareCounts = new LinkedHashMap<>();
            for (Map.Entry<String, BigDecimal> hwEntry : locEntry.getValue().entrySet()) {
                int qty = hwEntry.getValue() != null ? hwEntry.getValue().intValue() : 0;
                if (qty > 0) {
                    hardwareCounts.put(hwEntry.getKey(), qty);
                }
            }
            if (!hardwareCounts.isEmpty()) {
                out.put(locEntry.getKey(), hardwareCounts);
            }
        }
        return out.isEmpty() ? null : out;
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

    private void syncCountsFromHardwareLocationCounts(
            KioscoPhysicalCountItemEntity item,
            Map<String, Map<String, Integer>> hardwareLocationCounts
    ) {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        if (hardwareLocationCounts == null) {
            return;
        }
        for (Map.Entry<String, Map<String, Integer>> locEntry : hardwareLocationCounts.entrySet()) {
            int sum = locEntry.getValue() != null
                    ? locEntry.getValue().values().stream().mapToInt(v -> v != null ? v : 0).sum()
                    : 0;
            totals.put(locEntry.getKey(), BigDecimal.valueOf(sum));
        }
        item.setCountsData(ProductInventorySizesJson.serializeIncludingZeros(totals));
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

    /**
     * Expande por talla usando el kardex ya fusionado de todos los herrajes del producto/color.
     * No usar un solo {@code kiosco_stock_id}: con NUEVO+VIEJO el primero suele estar en 0 y
     * ocultaba Ent./Fin. del stock que sí movió (p.ej. VIEJO).
     */
    private List<KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow> expandRowsForDisplay(
            KioscoPhysicalCountReportResponse.KioscoPhysicalCountRow base,
            ProductEntity product,
            Map<String, KioscoInventoryService.SizeKardexBucket> sizeKardexMerged,
            Map<String, Integer> openingBalanceBySize,
            String generalObservation,
            Map<String, String> sizeObservations
    ) {
        Map<String, KioscoInventoryService.SizeKardexBucket> sizeKardex =
                sizeKardexMerged != null ? sizeKardexMerged : Map.of();
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
            // Inv. inicial = Fin. del conteo cerrado anterior por talla; si no hay desglose, el agregado va a la 1ª.
            int inventarioInicial = Math.max(0, openingBalanceBySize.getOrDefault(size, 0));
            if (inventarioInicial == 0 && first && openingBalanceBySize.isEmpty()) {
                inventarioInicial = Math.max(0, base.getInventarioInicial());
            }
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
                .diferencia(computeDiferenciaConteo(
                        total,
                        inventarioFinal,
                        salidaDevolucionForDiff(base.isPackaging(), bucket.salidaDevolucion)))
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

    private String resolveUserDisplayName(Long userId) {
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId).map(user -> {
            String first = safeTrim(user.getFirstName());
            String last = safeTrim(user.getLastName());
            String full = (first + " " + last).trim();
            if (!full.isEmpty()) {
                return full;
            }
            return user.getUsername();
        }).orElse(null);
    }

    private KioscoPhysicalCountItemSyncResponse toItemSyncResponse(KioscoPhysicalCountItemEntity item) {
        Map<String, BigDecimal> countedValues = ProductInventorySizesJson.parse(item.getCountsData());
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String key : COUNT_LOCATION_KEYS) {
            counts.put(key, countedValues.getOrDefault(key, BigDecimal.ZERO).intValue());
        }
        Map<String, Integer> physicalSizes = toSystemSizesMap(item.getSizeCountsData());
        return KioscoPhysicalCountItemSyncResponse.builder()
                .productId(item.getProductId())
                .colorId(item.getColorId())
                .counts(counts)
                .physicalSizes(physicalSizes.isEmpty() ? null : physicalSizes)
                .physicalSizesByLocation(toPhysicalSizesByLocationMap(item.getSizeLocationCountsData()))
                .hardwareLocationCounts(toHardwareLocationCountsMap(item.getHardwareLocationCountsData()))
                .observation(item.getObservation())
                .sizeObservations(ProductInventorySizesJson.parseStringMap(item.getSizeObservationsData()))
                .updatedAt(item.getUpdatedAt())
                .updatedBy(item.getUpdatedBy())
                .updatedByName(resolveUserDisplayName(item.getUpdatedBy()))
                .build();
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
                .observations(count.getObservations())
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
