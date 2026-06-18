package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.ProductInventoryOutflowReportResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ProductInventoryOutflowReportRowResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductInventoryOutflowReportService {

    private static final int MAX_ROWS = 10_000;

    private static final Set<String> KNOWN_SOURCE_CATEGORIES = Set.of(
            "PRODUCTION_ORDER", "DISTRIBUTION", "ONLINE_SALE", "KIOSK", "TRANSFER", "ADJUSTMENT", "OTHER");

    private final ProductInventoryKardexRepository kardexRepository;
    private final ProductRepository productRepository;
    private final LocationRepository locationRepository;
    private final ColorRepository colorRepository;
    private final ProductShipmentRepository shipmentRepository;
    private final ProductDistributionRepository distributionRepository;
    private final ProductionOrderRepository productionOrderRepository;
    private final OnlineSaleRepository onlineSaleRepository;
    private final InventoryTransferRepository transferRepository;

    public ProductInventoryOutflowReportResponse buildReport(
            LocalDate startDate,
            LocalDate endDate,
            Long locationId,
            Long productId,
            List<String> sourceCategories,
            List<String> orderTypes) throws BusinessException {

        if (startDate == null || endDate == null) {
            throw new BusinessException("Las fechas de inicio y fin son obligatorias.");
        }
        if (endDate.isBefore(startDate)) {
            throw new BusinessException("La fecha fin no puede ser anterior a la fecha inicio.");
        }

        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = endDate.atTime(LocalTime.MAX);

        List<ProductInventoryKardex> raw = kardexRepository.findOutflowsByDateRange(start, end);

        if (locationId != null) {
            raw = raw.stream().filter(k -> locationId.equals(k.getLocationId())).collect(Collectors.toList());
        }
        if (productId != null) {
            raw = raw.stream().filter(k -> productId.equals(k.getProductId())).collect(Collectors.toList());
        }

        raw = deduplicateShipmentRows(raw);

        Set<String> categoryFilter = normalizeFilterSet(sourceCategories);
        Set<String> orderTypeFilter = normalizeFilterSet(orderTypes);

        Map<Long, ProductEntity> productCache = new HashMap<>();
        Map<Long, LocationEntity> locationCache = new HashMap<>();
        Map<Long, ColorEntity> colorCache = new HashMap<>();
        Map<Long, ProductShipmentEntity> shipmentCache = new HashMap<>();
        Map<Long, ProductDistributionEntity> distributionCache = new HashMap<>();
        Map<Long, ProductionOrderEntity> orderCache = new HashMap<>();
        Map<Long, OnlineSaleEntity> onlineSaleCache = new HashMap<>();
        Map<Long, InventoryTransfer> transferCache = new HashMap<>();

        List<ProductInventoryOutflowReportRowResponse> rows = new ArrayList<>();
        boolean truncated = false;

        for (ProductInventoryKardex k : raw) {
            if (rows.size() >= MAX_ROWS) {
                truncated = true;
                break;
            }

            Enrichment enr = enrich(k, shipmentCache, distributionCache, orderCache, onlineSaleCache, transferCache);

            if (!categoryFilter.isEmpty() && !categoryFilter.contains(enr.sourceCategory)) {
                continue;
            }
            if (!orderTypeFilter.isEmpty()) {
                String ot = enr.orderType == null ? "" : enr.orderType.trim().toUpperCase(Locale.ROOT);
                if (ot.isEmpty() || !orderTypeFilter.contains(ot)) {
                    continue;
                }
            }

            ProductEntity product = productCache.computeIfAbsent(k.getProductId(),
                    id -> productRepository.findById(id).orElse(null));
            LocationEntity location = locationCache.computeIfAbsent(k.getLocationId(),
                    id -> locationRepository.findById(id).orElse(null));
            ColorEntity color = k.getColorId() == null ? null
                    : colorCache.computeIfAbsent(k.getColorId(), id -> colorRepository.findById(id).orElse(null));

            rows.add(ProductInventoryOutflowReportRowResponse.builder()
                    .id(k.getId())
                    .movementDate(k.getMovementDate())
                    .movementType(k.getMovementType())
                    .quantity(k.getQuantity())
                    .quantityBefore(k.getQuantityBefore())
                    .quantityAfter(k.getQuantityAfter())
                    .productId(k.getProductId())
                    .productCode(product != null ? product.getCode() : null)
                    .productName(product != null ? product.getName() : null)
                    .colorId(k.getColorId())
                    .colorName(color != null ? color.getName() : null)
                    .locationId(k.getLocationId())
                    .locationCode(location != null ? location.getCode() : null)
                    .locationName(location != null ? location.getName() : null)
                    .destinationLocationName(enr.destinationLocationName)
                    .sourceCategory(enr.sourceCategory)
                    .sourceLabel(enr.sourceLabel)
                    .referenceType(k.getReferenceType())
                    .referenceId(k.getReferenceId())
                    .referenceNumber(k.getReferenceNumber())
                    .orderType(enr.orderType)
                    .orderCode(enr.orderCode)
                    .distributionCode(enr.distributionCode)
                    .description(k.getDescription())
                    .build());
        }

        String message = truncated
                ? "Se muestran las primeras " + MAX_ROWS + " filas. Acote el periodo o los filtros."
                : null;

        return ProductInventoryOutflowReportResponse.builder()
                .rows(rows)
                .totalCount(rows.size())
                .truncated(truncated)
                .message(message)
                .build();
    }

    private List<ProductInventoryKardex> deduplicateShipmentRows(List<ProductInventoryKardex> raw) {
        Set<String> transferOutKeys = raw.stream()
                .filter(k -> "SHIPMENT".equalsIgnoreCase(safe(k.getReferenceType()))
                        && "TRANSFER_OUT".equalsIgnoreCase(safe(k.getMovementType())))
                .map(this::shipmentDedupKey)
                .collect(Collectors.toSet());

        return raw.stream()
                .filter(k -> {
                    if (!"SHIPMENT".equalsIgnoreCase(safe(k.getReferenceType()))) {
                        return true;
                    }
                    String mt = safe(k.getMovementType());
                    if ("TRANSFER_OUT".equalsIgnoreCase(mt)) {
                        return true;
                    }
                    if ("SHIPMENT".equalsIgnoreCase(mt)) {
                        return !transferOutKeys.contains(shipmentDedupKey(k));
                    }
                    return true;
                })
                .collect(Collectors.toList());
    }

    private String shipmentDedupKey(ProductInventoryKardex k) {
        BigDecimal qty = k.getQuantity() == null ? BigDecimal.ZERO : k.getQuantity().abs()
                .setScale(3, RoundingMode.HALF_UP);
        return String.join("|",
                "SHIPMENT",
                String.valueOf(k.getReferenceId()),
                String.valueOf(k.getProductId()),
                String.valueOf(k.getLocationId()),
                String.valueOf(k.getColorId()),
                qty.toPlainString());
    }

    private Enrichment enrich(
            ProductInventoryKardex k,
            Map<Long, ProductShipmentEntity> shipmentCache,
            Map<Long, ProductDistributionEntity> distributionCache,
            Map<Long, ProductionOrderEntity> orderCache,
            Map<Long, OnlineSaleEntity> onlineSaleCache,
            Map<Long, InventoryTransfer> transferCache) {

        String refType = safe(k.getReferenceType());
        String movementType = safe(k.getMovementType());

        if ("SHIPMENT".equalsIgnoreCase(refType) && k.getReferenceId() != null) {
            ProductShipmentEntity shipment = shipmentCache.computeIfAbsent(k.getReferenceId(),
                    id -> shipmentRepository.findById(id).orElse(null));
            if (shipment == null) {
                return Enrichment.other();
            }

            String destName = resolveLocationName(shipment.getLocationId());

            if (shipment.getDistributionId() != null) {
                ProductDistributionEntity dist = distributionCache.computeIfAbsent(shipment.getDistributionId(),
                        id -> distributionRepository.findById(id).orElse(null));
                ProductionOrderEntity linkedOp = productionOrderRepository
                        .findByDistributionId(shipment.getDistributionId())
                        .orElse(null);
                if (linkedOp != null) {
                    orderCache.put(linkedOp.getId(), linkedOp);
                }

                return Enrichment.builder()
                        .sourceCategory("DISTRIBUTION")
                        .sourceLabel("Distribución")
                        .destinationLocationName(destName)
                        .distributionCode(dist != null ? dist.getDistributionNumber() : null)
                        .orderType(linkedOp != null ? linkedOp.getOrderType() : "DISTRIBUTION")
                        .orderCode(linkedOp != null ? linkedOp.getCode() : null)
                        .build();
            }

            if (shipment.getProductionOrderId() != null) {
                ProductionOrderEntity po = orderCache.computeIfAbsent(shipment.getProductionOrderId(),
                        id -> productionOrderRepository.findById(id).orElse(null));
                return Enrichment.builder()
                        .sourceCategory("PRODUCTION_ORDER")
                        .sourceLabel("Orden de producción")
                        .destinationLocationName(destName)
                        .orderType(po != null ? po.getOrderType() : null)
                        .orderCode(po != null ? po.getCode() : null)
                        .build();
            }

            return Enrichment.builder()
                    .sourceCategory("DISTRIBUTION")
                    .sourceLabel("Envío")
                    .destinationLocationName(destName)
                    .build();
        }

        if ("PRODUCTION_ORDER".equalsIgnoreCase(refType) && k.getReferenceId() != null) {
            ProductionOrderEntity po = orderCache.computeIfAbsent(k.getReferenceId(),
                    id -> productionOrderRepository.findById(id).orElse(null));
            return Enrichment.builder()
                    .sourceCategory("PRODUCTION_ORDER")
                    .sourceLabel("Orden de producción")
                    .orderType(po != null ? po.getOrderType() : null)
                    .orderCode(po != null ? po.getCode() : k.getReferenceNumber())
                    .build();
        }

        if ("DISTRIBUCION".equalsIgnoreCase(refType)) {
            return Enrichment.builder()
                    .sourceCategory("DISTRIBUTION")
                    .sourceLabel("Distribución (legacy)")
                    .referenceNumber(k.getReferenceNumber())
                    .build();
        }

        if (refType.contains("ONLINE_SALE") || movementType.contains("ONLINE_SALE")) {
            ProductionOrderEntity po = null;
            if (k.getReferenceId() != null) {
                OnlineSaleEntity sale = onlineSaleCache.computeIfAbsent(k.getReferenceId(),
                        id -> onlineSaleRepository.findById(id).orElse(null));
                if (sale != null && sale.getProductionOrderId() != null) {
                    po = orderCache.computeIfAbsent(sale.getProductionOrderId(),
                            id -> productionOrderRepository.findById(id).orElse(null));
                }
            }
            return Enrichment.builder()
                    .sourceCategory("ONLINE_SALE")
                    .sourceLabel("Venta en línea")
                    .orderType(po != null ? po.getOrderType() : null)
                    .orderCode(po != null ? po.getCode() : null)
                    .build();
        }

        if ("KIOSK_SALE".equalsIgnoreCase(refType) || "KIOSK_SALE".equalsIgnoreCase(movementType)) {
            return Enrichment.builder()
                    .sourceCategory("KIOSK")
                    .sourceLabel("Venta kiosko")
                    .build();
        }

        if ("TRANSFER".equalsIgnoreCase(refType) && k.getReferenceId() != null) {
            InventoryTransfer transfer = transferCache.computeIfAbsent(k.getReferenceId(),
                    id -> transferRepository.findById(id).orElse(null));
            String dest = transfer != null ? resolveLocationName(transfer.getToLocationId()) : null;
            return Enrichment.builder()
                    .sourceCategory("TRANSFER")
                    .sourceLabel("Traslado")
                    .destinationLocationName(dest)
                    .build();
        }

        if (movementType.startsWith("TRANSFER_") || "TRANSFER_OUT".equalsIgnoreCase(movementType)) {
            return Enrichment.builder()
                    .sourceCategory("TRANSFER")
                    .sourceLabel("Traslado")
                    .build();
        }

        if ("ADJUSTMENT".equalsIgnoreCase(refType) || movementType.startsWith("ADJUSTMENT")) {
            return Enrichment.builder()
                    .sourceCategory("ADJUSTMENT")
                    .sourceLabel("Ajuste")
                    .build();
        }

        return Enrichment.other();
    }

    private String resolveLocationName(Long locationId) {
        if (locationId == null) {
            return null;
        }
        return locationRepository.findById(locationId).map(LocationEntity::getName).orElse(null);
    }

    private static Set<String> normalizeFilterSet(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptySet();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(v -> v.trim().toUpperCase(Locale.ROOT))
                .filter(v -> !v.isEmpty() && KNOWN_SOURCE_CATEGORIES.contains(v))
                .collect(Collectors.toSet());
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    @lombok.Builder
    @lombok.Data
    private static class Enrichment {
        private String sourceCategory;
        private String sourceLabel;
        private String destinationLocationName;
        private String orderType;
        private String orderCode;
        private String distributionCode;
        private String referenceNumber;

        static Enrichment other() {
            return Enrichment.builder()
                    .sourceCategory("OTHER")
                    .sourceLabel("Otro")
                    .build();
        }
    }
}
