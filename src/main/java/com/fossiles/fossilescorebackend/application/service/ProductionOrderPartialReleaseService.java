package com.fossiles.fossilescorebackend.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fossiles.fossilescorebackend.application.dto.request.OpcShipmentGenerateRequest;
import com.fossiles.fossilescorebackend.application.dto.request.PartialReleaseLineRequest;
import com.fossiles.fossilescorebackend.application.dto.request.PartialReleaseUpsertRequest;
import com.fossiles.fossilescorebackend.application.dto.request.ProductShipmentRequest;
import com.fossiles.fossilescorebackend.application.dto.response.PartialReleaseLineResponse;
import com.fossiles.fossilescorebackend.application.dto.response.PartialReleaseListResponse;
import com.fossiles.fossilescorebackend.application.dto.response.PartialReleaseResponse;
import com.fossiles.fossilescorebackend.application.dto.response.PartialReleaseSearchItemResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ProductShipmentResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.*;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductInventorySizesJson;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductionOrderItemPricing;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ProductionOrderPartialReleaseService {

    private final ProductionOrderPartialReleaseRepository releaseRepository;
    private final ProductionOrderItemRepository productionOrderItemRepository;
    private final ProductionOrderPartialReleaseLineRepository releaseLineRepository;
    private final ProductionOrderRepository productionOrderRepository;
    private final ProductRepository productRepository;
    private final ColorRepository colorRepository;
    private final CustomerRepository customerRepository;
    private final ProductShipmentRepository shipmentRepository;
    private final ProductShipmentDetailRepository shipmentDetailRepository;
    private final ProductDistributionService productDistributionService;
    private final SecurityUtil securityUtil;
    private final ObjectMapper objectMapper;
    private final UserRepository userRepository;

    public ProductionOrderPartialReleaseService(
            ProductionOrderPartialReleaseRepository releaseRepository,
            ProductionOrderPartialReleaseLineRepository releaseLineRepository,
            ProductionOrderRepository productionOrderRepository,
            ProductionOrderItemRepository productionOrderItemRepository,
            ProductRepository productRepository,
            ColorRepository colorRepository,
            CustomerRepository customerRepository,
            ProductShipmentRepository shipmentRepository,
            ProductShipmentDetailRepository shipmentDetailRepository,
            @Lazy ProductDistributionService productDistributionService,
            SecurityUtil securityUtil,
            ObjectMapper objectMapper,
            UserRepository userRepository) {
        this.releaseRepository = releaseRepository;
        this.releaseLineRepository = releaseLineRepository;
        this.productionOrderRepository = productionOrderRepository;
        this.productionOrderItemRepository = productionOrderItemRepository;
        this.productRepository = productRepository;
        this.colorRepository = colorRepository;
        this.customerRepository = customerRepository;
        this.shipmentRepository = shipmentRepository;
        this.shipmentDetailRepository = shipmentDetailRepository;
        this.productDistributionService = productDistributionService;
        this.securityUtil = securityUtil;
        this.objectMapper = objectMapper;
        this.userRepository = userRepository;
    }

    public PartialReleaseListResponse listForOrder(Long productionOrderId) throws ResourceNotFoundException, BusinessException {
        ProductionOrderEntity order = loadOrderForPartialReleases(productionOrderId);
        List<ProductionOrderItemEntity> orderItems = productionOrderItemRepository.findByProductionOrderId(productionOrderId);
        List<ProductionOrderPartialReleaseEntity> releases = releaseRepository.findByProductionOrderIdOrderBySequenceNumAsc(productionOrderId);
        Map<Long, ProductionOrderItemEntity> itemById = orderItems.stream()
                .collect(Collectors.toMap(ProductionOrderItemEntity::getId, i -> i, (a, b) -> a));

        List<PartialReleaseResponse> releaseResponses = releases.stream()
                .map(r -> toReleaseResponse(r, itemById, orderItems))
                .collect(Collectors.toList());

        return PartialReleaseListResponse.builder()
                .productionOrderId(productionOrderId)
                .productionOrderCode(order.getCode())
                .releases(releaseResponses)
                .orderItemAvailability(buildAvailabilityRows(orderItems, releases, null))
                .build();
    }

    @Transactional(readOnly = true)
    public List<PartialReleaseSearchItemResponse> searchForPrepare(String query, Integer limit) {
        String normalized = query == null ? "" : query.trim();
        // Sin texto: preview reciente rápido. Con texto: sin tope bajo (encuentra todos los matches).
        int defaultLimit = normalized.isEmpty() ? 80 : 5000;
        int maxLimit = normalized.isEmpty() ? 150 : 10000;
        int safeLimit = limit == null ? defaultLimit : Math.min(Math.max(limit, 1), maxLimit);
        List<ProductionOrderPartialReleaseEntity> rows = releaseRepository.searchForPrepare(normalized, safeLimit);
        if (rows.isEmpty()) {
            return List.of();
        }

        LinkedHashMap<Long, ProductionOrderPartialReleaseEntity> unique = new LinkedHashMap<>();
        rows.forEach((row) -> {
            if (row != null && row.getId() != null) {
                unique.putIfAbsent(row.getId(), row);
            }
        });

        Set<Long> orderIds = unique.values().stream()
                .map(ProductionOrderPartialReleaseEntity::getProductionOrderId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, ProductionOrderEntity> ordersById = productionOrderRepository.findAllById(orderIds).stream()
                .collect(Collectors.toMap(ProductionOrderEntity::getId, o -> o, (a, b) -> a));

        return unique.values().stream()
                .map((release) -> toSearchItem(release, ordersById.get(release.getProductionOrderId())))
                .collect(Collectors.toList());
    }

    private PartialReleaseSearchItemResponse toSearchItem(
            ProductionOrderPartialReleaseEntity release,
            ProductionOrderEntity order
    ) {
        Optional<ProductShipmentEntity> shipment = shipmentRepository.findByPartialReleaseId(release.getId()).stream()
                .filter(s -> !"CANCELLED".equalsIgnoreCase(String.valueOf(s.getStatus())))
                .findFirst();
        List<ProductionOrderPartialReleaseLineEntity> lines = loadLines(release.getId());

        return PartialReleaseSearchItemResponse.builder()
                .id(release.getId())
                .productionOrderId(release.getProductionOrderId())
                .orderCode(order != null ? order.getCode() : "")
                .customerName(order != null ? order.getCustomerName() : "")
                .orderType(order != null ? order.getOrderType() : "")
                .sequence(release.getSequenceNum())
                .label(release.getLabel())
                .status(release.getStatus())
                .shipmentId(shipment.map(ProductShipmentEntity::getId).orElse(null))
                .shipmentNumber(shipment.map(ProductShipmentEntity::getShipmentNumber).orElse(null))
                .shipmentStatus(shipment.map(s -> s.getStatus() == null ? null : s.getStatus().trim().toUpperCase())
                        .orElse(null))
                .totalUnits(computeReleaseTotalUnits(lines))
                .build();
    }

    @Transactional(rollbackFor = Exception.class)
    public PartialReleaseResponse createDraft(Long productionOrderId, PartialReleaseUpsertRequest request)
            throws ResourceNotFoundException, BusinessException {
        loadOrderForPartialReleases(productionOrderId);
        int nextSeq = releaseRepository.findTopByProductionOrderIdOrderBySequenceNumDesc(productionOrderId)
                .map(r -> r.getSequenceNum() + 1)
                .orElse(1);

        ProductionOrderPartialReleaseEntity release = ProductionOrderPartialReleaseEntity.builder()
                .productionOrderId(productionOrderId)
                .sequenceNum(nextSeq)
                .label(request != null && request.getLabel() != null && !request.getLabel().isBlank()
                        ? request.getLabel().trim()
                        : "Parcial " + nextSeq)
                .status("DRAFT")
                .notes(request != null ? trimToNull(request.getNotes()) : null)
                .createdBy(securityUtil.getCurrentUserId())
                .updatedBy(securityUtil.getCurrentUserId())
                .build();
        release = releaseRepository.save(release);

        if (request != null && request.getLines() != null) {
            saveLines(release, request.getLines(), request.getStatus());
        }
        if (request != null && "CONFIRMED".equals(normalizeStatus(request.getStatus()))) {
            validateReleaseHasLines(release.getId());
            validateLinesAgainstOrder(release, loadLines(release.getId()), release.getId());
            release.setStatus("CONFIRMED");
            releaseRepository.save(release);
        }

        return getRelease(productionOrderId, release.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public PartialReleaseResponse updateRelease(Long releaseId, PartialReleaseUpsertRequest request)
            throws ResourceNotFoundException, BusinessException {
        ProductionOrderPartialReleaseEntity release = releaseRepository.findById(releaseId)
                .orElseThrow(() -> new ResourceNotFoundException("PartialRelease", releaseId));
        loadOrderForPartialReleases(release.getProductionOrderId());
        assertEditable(release);

        if (request.getLabel() != null && !request.getLabel().isBlank()) {
            release.setLabel(request.getLabel().trim());
        }
        if (request.getNotes() != null) {
            release.setNotes(trimToNull(request.getNotes()));
        }
        release.setUpdatedBy(securityUtil.getCurrentUserId());

        if (request.getLines() != null) {
            if (request.getLines().isEmpty()) {
                throw new BusinessException(
                        "Debe incluir al menos un producto con cantidad. No se puede vaciar el parcial.");
            }
            replaceReleaseLines(release, request.getLines());
            if (loadLines(releaseId).isEmpty()) {
                throw new BusinessException(
                        "Ninguna línea quedó guardada: revise cantidades o tallas en «Incluir».");
            }
        }

        String targetStatus = normalizeStatus(request.getStatus());
        if ("CONFIRMED".equals(targetStatus)) {
            validateReleaseHasLines(releaseId);
            validateLinesAgainstOrder(release, loadLines(releaseId), null);
            release.setStatus("CONFIRMED");
        }

        releaseRepository.save(release);
        return getRelease(release.getProductionOrderId(), releaseId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteDraft(Long releaseId) throws ResourceNotFoundException, BusinessException {
        ProductionOrderPartialReleaseEntity release = releaseRepository.findById(releaseId)
                .orElseThrow(() -> new ResourceNotFoundException("PartialRelease", releaseId));
        loadOrderForPartialReleases(release.getProductionOrderId());
        if (!"DRAFT".equalsIgnoreCase(release.getStatus())) {
            throw new BusinessException("Solo se pueden eliminar liberaciones en borrador.");
        }
        releaseLineRepository.deleteByReleaseId(releaseId);
        releaseRepository.delete(release);
    }

    @Transactional(rollbackFor = Exception.class)
    public ProductShipmentResponse generateShipment(Long releaseId, OpcShipmentGenerateRequest request)
            throws ResourceNotFoundException, BusinessException {
        ProductionOrderPartialReleaseEntity release = releaseRepository.findById(releaseId)
                .orElseThrow(() -> new ResourceNotFoundException("PartialRelease", releaseId));
        ProductionOrderEntity order = loadOrderForPartialReleases(release.getProductionOrderId());

        if (!"CONFIRMED".equalsIgnoreCase(release.getStatus())) {
            throw new BusinessException("Confirme la liberación parcial antes de generar el envío.");
        }
        if (shipmentRepository.findByPartialReleaseId(releaseId).stream()
                .anyMatch(s -> !"DRAFT".equalsIgnoreCase(String.valueOf(s.getStatus()))
                        && !"CANCELLED".equalsIgnoreCase(String.valueOf(s.getStatus())))) {
            throw new BusinessException("Esta liberación ya tiene un envío generado.");
        }

        List<ProductionOrderPartialReleaseLineEntity> lines = loadLines(releaseId);
        validateReleaseHasLines(releaseId);
        List<ProductShipmentRequest.ProductShipmentDetailRequest> products = buildShipmentProductsFromReleaseLines(lines);

        boolean isCincho = isCinchoOrderType(order.getOrderType());
        ProductShipmentResponse result;
        if (isCincho) {
            OpcShipmentGenerateRequest opcReq = request != null ? request : new OpcShipmentGenerateRequest();
            if (opcReq.getDestinationAddress() == null || opcReq.getDestinationAddress().isBlank()) {
                opcReq.setDestinationAddress(resolveDefaultDestination(order));
            }
            result = productDistributionService.generateShipmentFromPartialRelease(
                    order.getId(), releaseId, products, opcReq);
        } else {
            ProductShipmentRequest createReq = ProductShipmentRequest.builder()
                    .partialReleaseId(releaseId)
                    .destinationAddress(request != null ? request.getDestinationAddress() : resolveDefaultDestination(order))
                    .notes(request != null ? request.getNotes() : null)
                    .documentDate(request != null ? request.getDocumentDate() : null)
                    .locationId(request != null ? request.getLocationId() : null)
                    .packingItems(request != null ? request.getPackingItems() : null)
                    .shippingCost(request != null ? request.getShippingCost() : null)
                    .products(products)
                    .build();
            ProductShipmentResponse draft = productDistributionService.createOrUpdateShipmentForPartialRelease(
                    order.getId(), releaseId, createReq);
            result = productDistributionService.confirmShipmentDraft(draft.getId());
        }

        release.setStatus("SHIPPED");
        release.setUpdatedBy(securityUtil.getCurrentUserId());
        releaseRepository.save(release);
        return result;
    }

    public PartialReleaseResponse getRelease(Long productionOrderId, Long releaseId)
            throws ResourceNotFoundException, BusinessException {
        loadOrderForPartialReleases(productionOrderId);
        ProductionOrderPartialReleaseEntity release = releaseRepository.findByIdAndProductionOrderId(releaseId, productionOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("PartialRelease", releaseId));
        List<ProductionOrderItemEntity> orderItems = productionOrderItemRepository.findByProductionOrderId(productionOrderId);
        Map<Long, ProductionOrderItemEntity> itemById = orderItems.stream()
                .collect(Collectors.toMap(ProductionOrderItemEntity::getId, i -> i, (a, b) -> a));
        return toReleaseResponse(release, itemById, orderItems);
    }

    /** Productos para envío desde líneas de liberación (uso interno y API). */
    public List<ProductShipmentRequest.ProductShipmentDetailRequest> buildShipmentProductsForRelease(Long releaseId)
            throws ResourceNotFoundException, BusinessException {
        ProductionOrderPartialReleaseEntity release = releaseRepository.findById(releaseId)
                .orElseThrow(() -> new ResourceNotFoundException("PartialRelease", releaseId));
        loadOrderForPartialReleases(release.getProductionOrderId());
        return buildShipmentProductsFromReleaseLines(loadLines(releaseId));
    }

    private PartialReleaseResponse toReleaseResponse(
            ProductionOrderPartialReleaseEntity release,
            Map<Long, ProductionOrderItemEntity> itemById,
            List<ProductionOrderItemEntity> orderItems) {
        List<ProductionOrderPartialReleaseEntity> allReleases =
                releaseRepository.findByProductionOrderIdOrderBySequenceNumAsc(release.getProductionOrderId());
        List<ProductionOrderPartialReleaseLineEntity> lines = loadLines(release.getId());

        Optional<ProductShipmentEntity> shipment = shipmentRepository.findByPartialReleaseId(release.getId()).stream()
                .filter(s -> !"CANCELLED".equalsIgnoreCase(String.valueOf(s.getStatus())))
                .findFirst();

        return PartialReleaseResponse.builder()
                .id(release.getId())
                .productionOrderId(release.getProductionOrderId())
                .sequence(release.getSequenceNum())
                .label(release.getLabel())
                .status(release.getStatus())
                .notes(release.getNotes())
                .shipmentId(shipment.map(ProductShipmentEntity::getId).orElse(null))
                .shipmentNumber(shipment.map(ProductShipmentEntity::getShipmentNumber).orElse(null))
                .shipmentStatus(shipment.map(s -> s.getStatus() == null ? null : s.getStatus().trim().toUpperCase()).orElse(null))
                .lines(lines.stream()
                        .map(line -> toLineResponse(line, itemById.get(line.getProductionOrderItemId()), orderItems, allReleases, release.getId()))
                        .collect(Collectors.toList()))
                .lineCount(lines.size())
                .savedLineCount((int) lines.stream().filter(this::lineHasPositiveQuantity).count())
                .totalUnits(computeReleaseTotalUnits(lines))
                .createdBy(release.getCreatedBy())
                .createdByName(resolveUserDisplayName(release.getCreatedBy()))
                .createdAt(release.getCreatedAt())
                .updatedAt(release.getUpdatedAt())
                .build();
    }

    private int computeReleaseTotalUnits(List<ProductionOrderPartialReleaseLineEntity> lines) {
        int total = 0;
        for (ProductionOrderPartialReleaseLineEntity line : lines) {
            if (!lineHasPositiveQuantity(line)) {
                continue;
            }
            Map<String, Integer> sizes = parseSizesMap(line.getSizesData());
            if (!sizes.isEmpty()) {
                total += sizes.values().stream().mapToInt(v -> v != null ? v : 0).sum();
            } else if (line.getQuantity() != null) {
                total += line.getQuantity();
            }
        }
        return total;
    }

    private List<PartialReleaseLineResponse> buildAvailabilityRows(
            List<ProductionOrderItemEntity> orderItems,
            List<ProductionOrderPartialReleaseEntity> releases,
            Long excludeReleaseId) {
        Long orderId = orderItems.isEmpty() ? null : orderItems.get(0).getProductionOrderId();
        ShippedAllocation shipped = loadShippedAllocation(orderId);
        return orderItems.stream()
                .map(item -> toAvailabilityRow(item, releases, excludeReleaseId, shipped))
                .collect(Collectors.toList());
    }

    private PartialReleaseLineResponse toAvailabilityRow(
            ProductionOrderItemEntity item,
            List<ProductionOrderPartialReleaseEntity> releases,
            Long excludeReleaseId) {
        return toAvailabilityRow(item, releases, excludeReleaseId, loadShippedAllocation(item.getProductionOrderId()));
    }

    /**
     * Pendiente = pedido − envíos no anulados (product/color/talla) − otros parciales CONFIRMED.
     * Un envío CANCELLED/VOID devuelve cantidad al pendiente. Los SHIPPED se cubren con el detalle
     * del envío para no restar dos veces.
     */
    private PartialReleaseLineResponse toAvailabilityRow(
            ProductionOrderItemEntity item,
            List<ProductionOrderPartialReleaseEntity> releases,
            Long excludeReleaseId,
            ShippedAllocation shipped) {
        Map<String, Integer> orderedSizes = parseSizesMap(item.getSizesData());
        int orderedTotal = orderedSizes.isEmpty()
                ? Math.max(0, item.getQuantity() != null ? item.getQuantity() : 0)
                : orderedSizes.values().stream().mapToInt(Integer::intValue).sum();

        Map<String, Integer> allocatedSizes = new LinkedHashMap<>();
        int allocatedTotal = 0;
        mergeShippedIntoAllocated(item, orderedSizes, shipped, allocatedSizes);
        if (orderedSizes.isEmpty()) {
            allocatedTotal += shipped.totalFor(item.getProductId(), item.getColorId());
        }

        for (ProductionOrderPartialReleaseEntity r : releases) {
            if (excludeReleaseId != null && excludeReleaseId.equals(r.getId())) {
                continue;
            }
            String status = r.getStatus() == null ? "" : r.getStatus().toUpperCase(Locale.ROOT);
            if (!"CONFIRMED".equals(status)) {
                continue;
            }
            for (ProductionOrderPartialReleaseLineEntity line : loadLines(r.getId())) {
                if (!item.getId().equals(line.getProductionOrderItemId())) {
                    continue;
                }
                Map<String, Integer> lineSizes = parseSizesMap(line.getSizesData());
                if (!lineSizes.isEmpty()) {
                    mergeAdd(allocatedSizes, lineSizes);
                } else {
                    allocatedTotal += Math.max(0, line.getQuantity() != null ? line.getQuantity() : 0);
                }
            }
        }

        if (!orderedSizes.isEmpty()) {
            allocatedTotal = allocatedSizes.values().stream().mapToInt(Integer::intValue).sum();
        }

        Map<String, Integer> pendingSizes = subtractMaps(orderedSizes, allocatedSizes);
        int pendingTotal = orderedSizes.isEmpty()
                ? Math.max(0, orderedTotal - allocatedTotal)
                : pendingSizes.values().stream().mapToInt(Integer::intValue).sum();

        return PartialReleaseLineResponse.builder()
                .productionOrderItemId(item.getId())
                .productId(item.getProductId())
                .productCode(resolveProductCode(item))
                .productName(resolveProductName(item))
                .colorId(item.getColorId())
                .colorName(resolveColorName(item))
                .orderedTotal(orderedTotal)
                .orderedSizes(orderedSizes.isEmpty() ? null : orderedSizes)
                .allocatedInOtherReleases(allocatedTotal)
                .allocatedSizesInOtherReleases(allocatedSizes.isEmpty() ? null : allocatedSizes)
                .pendingTotal(pendingTotal)
                .pendingSizes(pendingSizes.isEmpty() ? null : pendingSizes)
                .build();
    }

    private PartialReleaseLineResponse toLineResponse(
            ProductionOrderPartialReleaseLineEntity line,
            ProductionOrderItemEntity item,
            List<ProductionOrderItemEntity> orderItems,
            List<ProductionOrderPartialReleaseEntity> releases,
            Long releaseId) {
        PartialReleaseLineResponse avail = item != null
                ? toAvailabilityRow(item, releases, releaseId)
                : PartialReleaseLineResponse.builder().productionOrderItemId(line.getProductionOrderItemId()).build();

        Map<String, Integer> sizes = parseSizesMap(line.getSizesData());
        sizes.entrySet().removeIf(e -> e.getValue() == null || e.getValue() <= 0);
        int qty = sizes.isEmpty()
                ? Math.max(0, line.getQuantity() != null ? line.getQuantity() : 0)
                : sizes.values().stream().mapToInt(Integer::intValue).sum();

        return PartialReleaseLineResponse.builder()
                .id(line.getId())
                .productionOrderItemId(line.getProductionOrderItemId())
                .productId(avail.getProductId())
                .productCode(avail.getProductCode())
                .productName(avail.getProductName())
                .colorId(avail.getColorId())
                .colorName(avail.getColorName())
                .quantity(qty)
                .sizes(sizes.isEmpty() ? null : sizes)
                .orderedTotal(avail.getOrderedTotal())
                .orderedSizes(avail.getOrderedSizes())
                .allocatedInOtherReleases(avail.getAllocatedInOtherReleases())
                .allocatedSizesInOtherReleases(avail.getAllocatedSizesInOtherReleases())
                .pendingTotal(avail.getPendingTotal())
                .pendingSizes(avail.getPendingSizes())
                .build();
    }

    /**
     * Actualiza líneas del parcial sin borrar todo primero (evita dejar el parcial vacío si falla la transacción).
     */
    private void replaceReleaseLines(
            ProductionOrderPartialReleaseEntity release,
            List<PartialReleaseLineRequest> lineRequests) throws BusinessException {
        List<ProductionOrderPartialReleaseLineEntity> entities =
                buildDedupedLineEntities(release.getId(), lineRequests);
        if (entities.isEmpty()) {
            throw new BusinessException(
                    "Ninguna línea tiene cantidad válida (use cantidad o tallas mayores a cero).");
        }
        validateLinesAgainstOrder(release, entities, release.getId());

        List<ProductionOrderPartialReleaseLineEntity> existing =
                releaseLineRepository.findByReleaseId(release.getId());
        Map<Long, ProductionOrderPartialReleaseLineEntity> existingByItem = existing.stream()
                .collect(Collectors.toMap(
                        ProductionOrderPartialReleaseLineEntity::getProductionOrderItemId,
                        e -> e,
                        (a, b) -> a));

        Set<Long> newItemIds = new HashSet<>();
        for (ProductionOrderPartialReleaseLineEntity entity : entities) {
            newItemIds.add(entity.getProductionOrderItemId());
            ProductionOrderPartialReleaseLineEntity row =
                    existingByItem.get(entity.getProductionOrderItemId());
            if (row != null) {
                row.setQuantity(entity.getQuantity());
                row.setSizesData(entity.getSizesData());
                releaseLineRepository.save(row);
            } else {
                releaseLineRepository.save(entity);
            }
        }

        for (ProductionOrderPartialReleaseLineEntity row : existing) {
            if (!newItemIds.contains(row.getProductionOrderItemId())) {
                releaseLineRepository.delete(row);
            }
        }
    }

    private void saveLines(
            ProductionOrderPartialReleaseEntity release,
            List<PartialReleaseLineRequest> lineRequests,
            String requestedStatus) throws BusinessException {
        List<ProductionOrderPartialReleaseLineEntity> entities = buildDedupedLineEntities(release.getId(), lineRequests);
        if (entities.isEmpty()) {
            if (lineRequests != null && !lineRequests.isEmpty()) {
                throw new BusinessException(
                        "Ninguna línea tiene cantidad válida (use cantidad o tallas mayores a cero).");
            }
            return;
        }
        validateLinesAgainstOrder(release, entities, release.getId());
        releaseLineRepository.saveAll(entities);
    }

    private List<ProductionOrderPartialReleaseLineEntity> buildDedupedLineEntities(
            Long releaseId, List<PartialReleaseLineRequest> lineRequests) throws BusinessException {
        if (lineRequests == null || lineRequests.isEmpty()) {
            return List.of();
        }
        Map<Long, PartialReleaseLineRequest> merged = new LinkedHashMap<>();
        for (PartialReleaseLineRequest req : lineRequests) {
            if (req == null || req.getProductionOrderItemId() == null) {
                continue;
            }
            merged.merge(req.getProductionOrderItemId(), req, this::mergePartialReleaseLineRequests);
        }
        List<ProductionOrderPartialReleaseLineEntity> entities = new ArrayList<>();
        for (PartialReleaseLineRequest req : merged.values()) {
            ProductionOrderPartialReleaseLineEntity line = mapLineRequest(releaseId, req);
            if (line.getQuantity() == null && (line.getSizesData() == null || line.getSizesData().isBlank())) {
                continue;
            }
            entities.add(line);
        }
        return entities;
    }

    private PartialReleaseLineRequest mergePartialReleaseLineRequests(
            PartialReleaseLineRequest left, PartialReleaseLineRequest right) {
        Map<String, Integer> sizes = new LinkedHashMap<>();
        if (left.getSizes() != null) {
            left.getSizes().forEach((k, v) -> {
                if (k != null && v != null && v > 0) {
                    sizes.put(ProductInventorySizesJson.normalizeKey(k), v);
                }
            });
        }
        if (right.getSizes() != null) {
            right.getSizes().forEach((k, v) -> {
                if (k != null && v != null && v > 0) {
                    String key = ProductInventorySizesJson.normalizeKey(k);
                    sizes.merge(key, v, Integer::sum);
                }
            });
        }
        int qty = Math.max(0, left.getQuantity() != null ? left.getQuantity() : 0)
                + Math.max(0, right.getQuantity() != null ? right.getQuantity() : 0);
        return PartialReleaseLineRequest.builder()
                .productionOrderItemId(left.getProductionOrderItemId())
                .quantity(sizes.isEmpty() && qty > 0 ? qty : null)
                .sizes(sizes.isEmpty() ? null : sizes)
                .build();
    }

    private ProductionOrderPartialReleaseLineEntity mapLineRequest(Long releaseId, PartialReleaseLineRequest req)
            throws BusinessException {
        Map<String, Integer> sizes = req.getSizes();
        String sizesJson = null;
        Integer qty = req.getQuantity();
        if (sizes != null && !sizes.isEmpty()) {
            Map<String, Integer> normalized = new LinkedHashMap<>();
            sizes.forEach((k, v) -> {
                if (k == null || v == null || v <= 0) {
                    return;
                }
                normalized.put(ProductInventorySizesJson.normalizeKey(k), v);
            });
            if (!normalized.isEmpty()) {
                try {
                    sizesJson = objectMapper.writeValueAsString(normalized);
                    qty = normalized.values().stream().mapToInt(Integer::intValue).sum();
                } catch (Exception e) {
                    throw new BusinessException("Formato de tallas inválido.");
                }
            }
        }
        return ProductionOrderPartialReleaseLineEntity.builder()
                .releaseId(releaseId)
                .productionOrderItemId(req.getProductionOrderItemId())
                .quantity(qty != null && qty > 0 ? qty : null)
                .sizesData(sizesJson)
                .build();
    }

    private void validateLinesAgainstOrder(
            ProductionOrderPartialReleaseEntity release,
            List<ProductionOrderPartialReleaseLineEntity> newLines,
            Long excludeReleaseId) throws BusinessException {
        List<ProductionOrderItemEntity> orderItems =
                productionOrderItemRepository.findByProductionOrderId(release.getProductionOrderId());
        Map<Long, ProductionOrderItemEntity> itemById = orderItems.stream()
                .collect(Collectors.toMap(ProductionOrderItemEntity::getId, i -> i));

        List<ProductionOrderPartialReleaseEntity> allReleases =
                releaseRepository.findByProductionOrderIdOrderBySequenceNumAsc(release.getProductionOrderId());

        for (ProductionOrderPartialReleaseLineEntity line : newLines) {
            ProductionOrderItemEntity item = itemById.get(line.getProductionOrderItemId());
            if (item == null) {
                throw new BusinessException("Línea no pertenece a la orden de producción.");
            }
            PartialReleaseLineResponse avail = toAvailabilityRow(item, allReleases, excludeReleaseId);
            Map<String, Integer> newSizes = parseSizesMap(line.getSizesData());

            if (avail.getOrderedSizes() != null && !avail.getOrderedSizes().isEmpty()) {
                Map<String, Integer> pendingSizes = avail.getPendingSizes() == null
                        ? Map.of()
                        : avail.getPendingSizes();
                for (Map.Entry<String, Integer> e : newSizes.entrySet()) {
                    int pending = pendingSizes.getOrDefault(e.getKey(), 0);
                    if (e.getValue() > pending) {
                        throw new BusinessException(
                                "En " + resolveProductCode(item) + ", talla " + e.getKey()
                                        + ": intentó liberar " + e.getValue()
                                        + " pero el pendiente es " + pending + ".");
                    }
                }
            } else {
                int newQty = line.getQuantity() != null ? line.getQuantity() : 0;
                int pending = avail.getPendingTotal() != null ? avail.getPendingTotal() : 0;
                if (newQty > pending) {
                    throw new BusinessException(
                            "Cantidad excede el pendiente en " + resolveProductCode(item)
                                    + " (pendiente " + pending + ").");
                }
            }
        }
    }

    private List<ProductShipmentRequest.ProductShipmentDetailRequest> buildShipmentProductsFromReleaseLines(
            List<ProductionOrderPartialReleaseLineEntity> lines) throws BusinessException {
        List<ProductShipmentRequest.ProductShipmentDetailRequest> products = new ArrayList<>();
        Map<Long, ProductionOrderItemEntity> itemCache = new HashMap<>();

        for (ProductionOrderPartialReleaseLineEntity line : lines) {
            if (!lineHasPositiveQuantity(line)) {
                continue;
            }
            ProductionOrderItemEntity item = itemCache.computeIfAbsent(
                    line.getProductionOrderItemId(),
                    id -> productionOrderItemRepository.findById(id).orElse(null));
            if (item == null || item.getProductId() == null) {
                continue;
            }
            Map<String, Integer> sizes = parseSizesMap(line.getSizesData());
            if (!sizes.isEmpty()) {
                for (Map.Entry<String, Integer> e : sizes.entrySet()) {
                    int qty = e.getValue() != null ? e.getValue() : 0;
                    if (qty <= 0) {
                        continue;
                    }
                    products.add(ProductShipmentRequest.ProductShipmentDetailRequest.builder()
                            .productId(item.getProductId())
                            .colorId(item.getColorId())
                            .size(e.getKey())
                            .quantity(BigDecimal.valueOf(qty))
                            .unitPrice(resolveReleaseLineUnitPrice(item, e.getKey()))
                            .build());
                }
            } else if (line.getQuantity() != null && line.getQuantity() > 0) {
                products.add(ProductShipmentRequest.ProductShipmentDetailRequest.builder()
                        .productId(item.getProductId())
                        .colorId(item.getColorId())
                        .size("")
                        .quantity(BigDecimal.valueOf(line.getQuantity()))
                        .unitPrice(resolveReleaseLineUnitPrice(item, null))
                        .build());
            }
        }
        if (products.isEmpty()) {
            throw new BusinessException("La liberación no tiene productos con cantidad.");
        }
        return products;
    }

    private BigDecimal resolveReleaseLineUnitPrice(ProductionOrderItemEntity item, String sizeLabel) {
        BigDecimal price = ProductionOrderItemPricing.resolveForSize(item, sizeLabel, null);
        return price.compareTo(BigDecimal.ZERO) > 0 ? price : null;
    }

    private ProductionOrderEntity loadOrderForPartialReleases(Long productionOrderId)
            throws ResourceNotFoundException, BusinessException {
        ProductionOrderEntity order = productionOrderRepository.findById(productionOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductionOrder", productionOrderId));
        if (!allowsPartialReleases(order)) {
            throw new BusinessException(
                    "Las liberaciones parciales aplican a órdenes OPV (Luis Felipe o cliente Entre Cueros), OPC (cinchos), OPCK (cliente kiosko) u OPK (kiosko).");
        }
        return order;
    }

    private static boolean allowsPartialReleases(ProductionOrderEntity order) {
        if (order == null) {
            return false;
        }
        if (isLuisFelipeVendorOrder(order)) {
            return true;
        }
        if (isEntreCuerosCustomerOpv(order)) {
            return true;
        }
        if (isCinchoOrderType(order.getOrderType())) {
            return true;
        }
        String orderType = order.getOrderType() == null ? "" : order.getOrderType().trim().toUpperCase(Locale.ROOT);
        if ("CLIENTE_KIOSKO".equals(orderType) || "NORMAL".equals(orderType)) {
            return true;
        }
        String code = order.getCode() == null ? "" : order.getCode().trim().toUpperCase(Locale.ROOT);
        return code.startsWith("OPK-");
    }

    private static String normalizeEntreCuerosToken(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String nfd = Normalizer.normalize(value.trim(), Normalizer.Form.NFD);
        String withoutMarks = nfd.replaceAll("\\p{M}+", "");
        return withoutMarks.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    /** OPV del cliente Entre Cueros (mismo criterio que prepare-shipments en front). */
    private static boolean isEntreCuerosCustomerOpv(ProductionOrderEntity order) {
        if (order == null) {
            return false;
        }
        String orderType = order.getOrderType() == null ? "" : order.getOrderType().trim().toUpperCase(Locale.ROOT);
        String code = order.getCode() == null ? "" : order.getCode().trim().toUpperCase(Locale.ROOT);
        if ("INTERNA".equals(orderType) || "CLIENTE_KIOSKO".equals(orderType) || isCinchoOrderType(orderType)) {
            return false;
        }
        boolean opvOrder = "MARCAS".equals(orderType) || "OPV".equals(orderType) || code.startsWith("OPV-");
        if (!opvOrder) {
            return false;
        }
        return normalizeEntreCuerosToken(order.getCustomerName()).contains("ENTRECUEROS");
    }

    private String resolveUserDisplayName(Long userId) {
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId)
                .map(user -> {
                    String first = user.getFirstName() != null ? user.getFirstName().trim() : "";
                    String last = user.getLastName() != null ? user.getLastName().trim() : "";
                    String full = (first + " " + last).trim();
                    return full.isEmpty() ? user.getUsername() : full;
                })
                .orElse(null);
    }

    private static boolean isLuisFelipeVendorOrder(ProductionOrderEntity order) {
        if (order == null || order.getSellerName() == null) {
            return false;
        }
        return order.getSellerName().toUpperCase(Locale.ROOT).contains("LUIS FELIPE");
    }

    private static boolean isCinchoOrderType(String orderType) {
        if (orderType == null) {
            return false;
        }
        String t = orderType.trim().toUpperCase(Locale.ROOT);
        return "CINCHOS".equals(t) || "CINCHOS_FOSSILES".equals(t) || "CINCHOS_MARCAS".equals(t);
    }

    private void assertEditable(ProductionOrderPartialReleaseEntity release) throws BusinessException {
        if ("SHIPPED".equalsIgnoreCase(release.getStatus())) {
            throw new BusinessException("No se puede modificar una liberación ya enviada.");
        }
    }

    private void validateReleaseHasLines(Long releaseId) throws BusinessException {
        List<ProductionOrderPartialReleaseLineEntity> lines = loadLines(releaseId);
        if (lines.isEmpty()) {
            throw new BusinessException(
                    "Este parcial no tiene productos guardados. Edítelo, asigne cantidades en «Incluir» "
                            + "y pulse «Confirmar parcial».");
        }
        boolean hasQuantity = lines.stream().anyMatch(this::lineHasPositiveQuantity);
        if (!hasQuantity) {
            throw new BusinessException(
                    "Este parcial no tiene cantidades mayores a cero. Edítelo y confirme de nuevo.");
        }
    }

    private boolean lineHasPositiveQuantity(ProductionOrderPartialReleaseLineEntity line) {
        if (line == null) {
            return false;
        }
        Map<String, Integer> sizes = parseSizesMap(line.getSizesData());
        if (!sizes.isEmpty()) {
            boolean anySize = sizes.values().stream().anyMatch(v -> v != null && v > 0);
            if (anySize) {
                return true;
            }
        }
        return line.getQuantity() != null && line.getQuantity() > 0;
    }

    private List<ProductionOrderPartialReleaseLineEntity> loadLines(Long releaseId) {
        return releaseLineRepository.findByReleaseId(releaseId);
    }

    private String resolveDefaultDestination(ProductionOrderEntity order) {
        if (order.getCustomerId() != null) {
            Optional<CustomerEntity> customer = customerRepository.findById(order.getCustomerId());
            if (customer.isPresent()) {
                String address = customer.get().getAddress();
                if (address != null && !address.isBlank()) {
                    return address.trim();
                }
            }
        }
        if (order.getCustomerName() != null && !order.getCustomerName().isBlank()) {
            return order.getCustomerName().trim();
        }
        return "";
    }

    private String resolveProductCode(ProductionOrderItemEntity item) {
        if (item.getProductId() == null) {
            return "-";
        }
        return productRepository.findById(item.getProductId()).map(ProductEntity::getCode).orElse("-");
    }

    private String resolveProductName(ProductionOrderItemEntity item) {
        if (item.getProductId() == null) {
            return "-";
        }
        return productRepository.findById(item.getProductId()).map(ProductEntity::getName).orElse("-");
    }

    private String resolveColorName(ProductionOrderItemEntity item) {
        if (item.getColorId() == null) {
            return "-";
        }
        return colorRepository.findById(item.getColorId()).map(ColorEntity::getName).orElse("-");
    }

    private ShippedAllocation loadShippedAllocation(Long productionOrderId) {
        if (productionOrderId == null) {
            return ShippedAllocation.empty();
        }
        List<ProductShipmentEntity> active = shipmentRepository.findByProductionOrderId(productionOrderId).stream()
                .filter(s -> !isVoidShipmentStatus(s.getStatus()))
                .collect(Collectors.toList());
        if (active.isEmpty()) {
            return ShippedAllocation.empty();
        }
        Set<Long> shipmentIds = active.stream()
                .map(ProductShipmentEntity::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, Integer> bySize = new HashMap<>();
        Map<String, Integer> byProductColor = new HashMap<>();
        for (ProductShipmentDetailEntity detail : shipmentDetailRepository.findByShipmentIdIn(shipmentIds)) {
            int qty = detail.getQuantity() == null ? 0 : detail.getQuantity().intValue();
            if (qty <= 0) {
                continue;
            }
            String sizeKey = shippedSizeKey(detail.getProductId(), detail.getColorId(), detail.getSizeLabel());
            String pcKey = productColorKey(detail.getProductId(), detail.getColorId());
            bySize.merge(sizeKey, qty, Integer::sum);
            byProductColor.merge(pcKey, qty, Integer::sum);
        }
        return new ShippedAllocation(bySize, byProductColor);
    }

    private static void mergeShippedIntoAllocated(
            ProductionOrderItemEntity item,
            Map<String, Integer> orderedSizes,
            ShippedAllocation shipped,
            Map<String, Integer> allocatedSizes) {
        if (orderedSizes.isEmpty() || shipped.isEmpty()) {
            return;
        }
        for (String size : orderedSizes.keySet()) {
            int qty = shipped.forSize(item.getProductId(), item.getColorId(), size);
            if (qty > 0) {
                allocatedSizes.merge(size, qty, Integer::sum);
            }
        }
    }

    private static boolean isVoidShipmentStatus(String status) {
        String st = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        return "CANCELLED".equals(st) || "VOID".equals(st) || "VOIDED".equals(st);
    }

    private static String shippedSizeKey(Long productId, Long colorId, String size) {
        String normalized = size == null ? "" : size.trim().toUpperCase(Locale.ROOT);
        return productColorKey(productId, colorId) + ":" + normalized;
    }

    private static String productColorKey(Long productId, Long colorId) {
        return (productId == null ? "null" : productId) + ":" + (colorId == null ? "null" : colorId);
    }

    private static final class ShippedAllocation {
        private final Map<String, Integer> bySize;
        private final Map<String, Integer> byProductColor;

        private ShippedAllocation(Map<String, Integer> bySize, Map<String, Integer> byProductColor) {
            this.bySize = bySize;
            this.byProductColor = byProductColor;
        }

        static ShippedAllocation empty() {
            return new ShippedAllocation(Map.of(), Map.of());
        }

        boolean isEmpty() {
            return bySize.isEmpty() && byProductColor.isEmpty();
        }

        int forSize(Long productId, Long colorId, String size) {
            return bySize.getOrDefault(shippedSizeKey(productId, colorId, size), 0);
        }

        int totalFor(Long productId, Long colorId) {
            return byProductColor.getOrDefault(productColorKey(productId, colorId), 0);
        }
    }

    private Map<String, Integer> parseSizesMap(String json) {
        Map<String, BigDecimal> raw = ProductInventorySizesJson.parse(json);
        Map<String, Integer> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> out.put(k, v != null ? v.intValue() : 0));
        return out;
    }

    private static void mergeAdd(Map<String, Integer> target, Map<String, Integer> add) {
        add.forEach((k, v) -> target.merge(k, v, Integer::sum));
    }

    private static Map<String, Integer> subtractMaps(Map<String, Integer> ordered, Map<String, Integer> allocated) {
        if (ordered == null || ordered.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        ordered.forEach((k, v) -> {
            int rem = v - allocated.getOrDefault(k, 0);
            if (rem > 0) {
                out.put(k, rem);
            }
        });
        return out;
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return "DRAFT";
        }
        return status.trim().toUpperCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }
}
