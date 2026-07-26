package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.KioskLedgerLabMovementUpsertRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskLedgerLabStockUpdateRequest;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoMovementResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskLedgerLabMovementResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskLedgerLabStockResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.util.KioskLedgerLabGuard;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoMovementEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoMovementType;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoStockEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductShipmentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoMovementRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoStockRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSaleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductShipmentRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductInventorySizesJson;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class KioskLedgerLabService {

    private final KioskLedgerLabGuard guard;
    private final SecurityUtil securityUtil;
    private final KioscoInventoryService kioscoInventoryService;
    private final KioscoStockRepository kioscoStockRepository;
    private final KioscoMovementRepository kioscoMovementRepository;
    private final LocationRepository locationRepository;
    private final ProductRepository productRepository;
    private final ColorRepository colorRepository;
    private final KioskSaleRepository kioskSaleRepository;
    private final ProductShipmentRepository productShipmentRepository;
    private final EntityManager entityManager;

    @Transactional(readOnly = true)
    public List<KioskLedgerLabStockResponse> listStocks(
            Long locationId,
            String productTerm,
            Long productId,
            Long colorId,
            Long stockId,
            String hardwareCondition
    ) throws BusinessException {
        guard.requireEramirez();
        if (locationId == null && stockId == null) {
            throw new BusinessException("Indica locationId o stockId.");
        }

        List<KioscoStockEntity> stocks;
        if (stockId != null) {
            stocks = kioscoStockRepository.findById(stockId).stream().collect(Collectors.toList());
        } else {
            stocks = kioscoStockRepository
                    .findByLocationIdOrderByProductIdAscColorIdAscHardwareConditionAsc(locationId);
        }

        String term = normalizeTerm(productTerm);
        String hw = hardwareCondition != null ? hardwareCondition.trim().toUpperCase(Locale.ROOT) : null;

        List<KioskLedgerLabStockResponse> out = new ArrayList<>();
        for (KioscoStockEntity stock : stocks) {
            if (productId != null && !Objects.equals(stock.getProductId(), productId)) {
                continue;
            }
            if (colorId != null && !Objects.equals(stock.getColorId(), colorId)) {
                continue;
            }
            if (hw != null && !hw.isEmpty()
                    && !hw.equalsIgnoreCase(String.valueOf(stock.getHardwareCondition()))) {
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
            out.add(toStockResponse(stock, product, color, location, null));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<KioskLedgerLabMovementResponse> listMovements(
            Long locationId,
            Long stockId,
            KioscoMovementType type,
            LocalDate from,
            LocalDate to,
            Long referenceId,
            String referenceTerm,
            String reasonContains,
            String sizeKey,
            Boolean affectsStockOnly,
            Long movementId
    ) throws BusinessException, ResourceNotFoundException {
        guard.requireEramirez();
        if (movementId != null) {
            KioscoMovementEntity one = kioscoMovementRepository.findById(movementId)
                    .orElseThrow(() -> new ResourceNotFoundException("KioscoMovement", movementId));
            return List.of(toLabMovement(one));
        }
        if (stockId == null && locationId == null) {
            throw new BusinessException("Indica locationId o stockId.");
        }

        List<KioscoMovementEntity> raw;
        if (stockId != null) {
            raw = kioscoMovementRepository.findByKioscoStockIdOrderByCreatedAtDescIdDesc(stockId);
        } else {
            raw = kioscoMovementRepository.findByLocationIdOrderByCreatedAtDesc(locationId);
        }

        LocalDateTime fromDt = from != null ? from.atStartOfDay() : null;
        LocalDateTime toDtExclusive = to != null ? to.plusDays(1).atStartOfDay() : null;
        String reasonTerm = normalizeTerm(reasonContains);
        String refTerm = normalizeTerm(referenceTerm);
        String sizeNorm = sizeKey != null ? ProductInventorySizesJson.normalizeKey(sizeKey) : null;

        List<KioskLedgerLabMovementResponse> out = new ArrayList<>();
        for (KioscoMovementEntity m : raw) {
            if (type != null && m.getMovementType() != type) {
                continue;
            }
            if (Boolean.TRUE.equals(affectsStockOnly) && !Boolean.TRUE.equals(m.getAffectsStock())) {
                continue;
            }
            if (referenceId != null && !Objects.equals(m.getReferenceId(), referenceId)) {
                continue;
            }
            if (sizeNorm != null && !sizeNorm.isEmpty()) {
                String mk = ProductInventorySizesJson.normalizeKey(m.getSizeKey());
                if (!sizeNorm.equals(mk)) {
                    continue;
                }
            }
            if (fromDt != null && (m.getCreatedAt() == null || m.getCreatedAt().isBefore(fromDt))) {
                continue;
            }
            if (toDtExclusive != null && (m.getCreatedAt() == null || !m.getCreatedAt().isBefore(toDtExclusive))) {
                continue;
            }
            if (reasonTerm != null) {
                String reason = nullToEmpty(m.getReason()).toLowerCase(Locale.ROOT);
                if (!reason.contains(reasonTerm)) {
                    continue;
                }
            }
            KioskLedgerLabMovementResponse dto = toLabMovement(m);
            if (refTerm != null) {
                String haystack = (nullToEmpty(dto.getReferenceNumber())
                        + " "
                        + nullToEmpty(dto.getReferenceSummary())
                        + " "
                        + nullToEmpty(String.valueOf(dto.getReferenceId()))
                        + " "
                        + nullToEmpty(dto.getPhysicalSlipNumber())).toLowerCase(Locale.ROOT);
                if (!haystack.contains(refTerm)) {
                    continue;
                }
            }
            out.add(dto);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public KioskLedgerLabMovementResponse getMovement(Long id)
            throws ResourceNotFoundException {
        guard.requireEramirez();
        KioscoMovementEntity entity = kioscoMovementRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("KioscoMovement", id));
        return toLabMovement(entity);
    }

    @Transactional
    public KioskLedgerLabMovementResponse createMovement(KioskLedgerLabMovementUpsertRequest request)
            throws BusinessException, ResourceNotFoundException {
        String actor = guard.requireEramirezUsername();
        validateUpsert(request, true);
        KioscoStockEntity stock = kioscoStockRepository.findById(request.getKioscoStockId())
                .orElseThrow(() -> new ResourceNotFoundException("KioscoStock", request.getKioscoStockId()));

        Long userId = request.getUserId() != null ? request.getUserId() : securityUtil.getCurrentUserId();
        if (userId == null) {
            throw new BusinessException("userId es obligatorio.");
        }

        int qty = request.getQuantity();
        int before = request.getStockBefore() != null ? request.getStockBefore() : safeInt(stock.getCurrentStock());
        int after = request.getStockAfter() != null ? request.getStockAfter() : before;

        KioscoMovementEntity entity = KioscoMovementEntity.builder()
                .kioscoStockId(stock.getId())
                .movementType(request.getMovementType())
                .quantity(qty)
                .sizeKey(blankToNull(request.getSizeKey()))
                .stockBefore(before)
                .stockAfter(after)
                .referenceId(request.getReferenceId())
                .physicalCountId(request.getPhysicalCountId())
                .physicalSlipNumber(blankToNull(request.getPhysicalSlipNumber()))
                .reason(blankToNull(request.getReason()))
                .affectsStock(request.getAffectsStock() != null ? request.getAffectsStock() : Boolean.TRUE)
                .userId(userId)
                .originLocationId(request.getOriginLocationId())
                .destinationLocationId(request.getDestinationLocationId())
                .build();

        entity = kioscoMovementRepository.save(entity);
        entityManager.flush();

        if (request.getCreatedAt() != null) {
            kioscoInventoryService.enableAdminMovementMutation();
            try {
                entityManager.createNativeQuery(
                                "UPDATE kiosco_movement SET created_at = :createdAt WHERE id = :id")
                        .unwrap(org.hibernate.query.NativeQuery.class)
                        .setParameter("createdAt", request.getCreatedAt(), LocalDateTime.class)
                        .setParameter("id", entity.getId(), Long.class)
                        .executeUpdate();
                entityManager.flush();
                entity.setCreatedAt(request.getCreatedAt());
            } finally {
                kioscoInventoryService.disableAdminMovementMutation();
            }
        }

        log.warn("LEDGER_LAB_CREATE actor={} movementId={} stockId={} type={} qty={}",
                actor, entity.getId(), stock.getId(), entity.getMovementType(), qty);
        return toLabMovement(kioscoMovementRepository.findById(entity.getId()).orElse(entity));
    }

    @Transactional
    public KioskLedgerLabMovementResponse updateMovement(Long id, KioskLedgerLabMovementUpsertRequest request)
            throws BusinessException, ResourceNotFoundException {
        String actor = guard.requireEramirezUsername();
        KioscoMovementEntity existing = kioscoMovementRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("KioscoMovement", id));
        validateUpsert(request, false);

        Long stockId = request.getKioscoStockId() != null
                ? request.getKioscoStockId()
                : existing.getKioscoStockId();
        if (!kioscoStockRepository.existsById(stockId)) {
            throw new ResourceNotFoundException("KioscoStock", stockId);
        }

        KioscoMovementType type = request.getMovementType() != null
                ? request.getMovementType()
                : existing.getMovementType();
        int qty = request.getQuantity() != null ? request.getQuantity() : safeInt(existing.getQuantity());
        validateQuantityForType(type, qty);

        int before = request.getStockBefore() != null ? request.getStockBefore() : safeInt(existing.getStockBefore());
        int after = request.getStockAfter() != null ? request.getStockAfter() : safeInt(existing.getStockAfter());
        boolean affects = request.getAffectsStock() != null
                ? request.getAffectsStock()
                : Boolean.TRUE.equals(existing.getAffectsStock());
        Long userId = request.getUserId() != null ? request.getUserId() : existing.getUserId();
        String sizeKey = request.getSizeKey() != null ? blankToNull(request.getSizeKey()) : existing.getSizeKey();
        String reason = request.getReason() != null ? blankToNull(request.getReason()) : existing.getReason();
        String slip = request.getPhysicalSlipNumber() != null
                ? blankToNull(request.getPhysicalSlipNumber())
                : existing.getPhysicalSlipNumber();
        Long referenceId = existing.getReferenceId();
        if (request.getReferenceId() != null) {
            referenceId = request.getReferenceId();
        }
        Long physicalCountId = request.getPhysicalCountId() != null
                ? request.getPhysicalCountId()
                : existing.getPhysicalCountId();
        Long originId = request.getOriginLocationId() != null
                ? request.getOriginLocationId()
                : existing.getOriginLocationId();
        Long destId = request.getDestinationLocationId() != null
                ? request.getDestinationLocationId()
                : existing.getDestinationLocationId();
        LocalDateTime createdAt = request.getCreatedAt() != null
                ? request.getCreatedAt()
                : existing.getCreatedAt();

        kioscoInventoryService.enableAdminMovementMutation();
        try {
            var query = entityManager.createNativeQuery("""
                            UPDATE kiosco_movement SET
                              kiosco_stock_id = :stockId,
                              movement_type = :type,
                              quantity = :qty,
                              size_key = :sizeKey,
                              stock_before = :before,
                              stock_after = :after,
                              reference_id = :referenceId,
                              physical_count_id = :physicalCountId,
                              physical_slip_number = :slip,
                              reason = :reason,
                              affects_stock = :affects,
                              user_id = :userId,
                              origin_location_id = :originId,
                              destination_location_id = :destId,
                              created_at = :createdAt
                            WHERE id = :id
                            """)
                    .unwrap(org.hibernate.query.NativeQuery.class);
            query.setParameter("stockId", stockId, Long.class);
            query.setParameter("type", type.name(), String.class);
            query.setParameter("qty", qty, Integer.class);
            query.setParameter("sizeKey", sizeKey, String.class);
            query.setParameter("before", before, Integer.class);
            query.setParameter("after", after, Integer.class);
            query.setParameter("referenceId", referenceId, Long.class);
            query.setParameter("physicalCountId", physicalCountId, Long.class);
            query.setParameter("slip", slip, String.class);
            query.setParameter("reason", reason, String.class);
            query.setParameter("affects", affects, Boolean.class);
            query.setParameter("userId", userId, Long.class);
            query.setParameter("originId", originId, Long.class);
            query.setParameter("destId", destId, Long.class);
            query.setParameter("createdAt", createdAt, LocalDateTime.class);
            query.setParameter("id", id, Long.class);
            int updated = query.executeUpdate();
            entityManager.flush();
            entityManager.clear();
            if (updated <= 0) {
                throw new BusinessException("No se pudo actualizar el movimiento #" + id);
            }
        } finally {
            kioscoInventoryService.disableAdminMovementMutation();
        }

        log.warn("LEDGER_LAB_UPDATE actor={} movementId={} stockId={} type={} qty={}",
                actor, id, stockId, type, qty);
        return getMovement(id);
    }

    @Transactional
    public void deleteMovement(Long id) throws BusinessException, ResourceNotFoundException {
        String actor = guard.requireEramirezUsername();
        KioscoMovementEntity existing = kioscoMovementRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("KioscoMovement", id));
        Long stockId = existing.getKioscoStockId();
        try {
            kioscoInventoryService.deleteAdminMovement(existing);
        } finally {
            kioscoInventoryService.disableAdminMovementMutation();
        }
        log.warn("LEDGER_LAB_DELETE actor={} movementId={} stockId={}", actor, id, stockId);
    }

    @Transactional
    public KioskLedgerLabStockResponse updateStock(Long stockId, KioskLedgerLabStockUpdateRequest request)
            throws BusinessException, ResourceNotFoundException {
        String actor = guard.requireEramirezUsername();
        KioscoStockEntity stock = kioscoStockRepository.findById(stockId)
                .orElseThrow(() -> new ResourceNotFoundException("KioscoStock", stockId));
        if (request == null) {
            throw new BusinessException("Body requerido.");
        }
        if (request.getCurrentStock() != null) {
            if (request.getCurrentStock() < 0) {
                throw new BusinessException("currentStock no puede ser negativo.");
            }
            stock.setCurrentStock(request.getCurrentStock());
        }
        if (request.getMinimumStock() != null) {
            if (request.getMinimumStock() < 0) {
                throw new BusinessException("minimumStock no puede ser negativo.");
            }
            stock.setMinimumStock(request.getMinimumStock());
        }
        if (request.getSizesData() != null) {
            stock.setSizesData(request.getSizesData().isBlank() ? null : request.getSizesData().trim());
        }
        if (request.getHardwareCondition() != null && !request.getHardwareCondition().isBlank()) {
            stock.setHardwareCondition(request.getHardwareCondition().trim().toUpperCase(Locale.ROOT));
        }
        stock.setLastUpdatedAt(LocalDateTime.now());
        stock.setUpdatedBy(securityUtil.getCurrentUserId());
        stock = kioscoStockRepository.save(stock);
        log.warn("LEDGER_LAB_STOCK_UPDATE actor={} stockId={} currentStock={}",
                actor, stockId, stock.getCurrentStock());
        return toStockResponse(stock, resolveProduct(stock), resolveColor(stock), resolveLocation(stock), null);
    }

    @Transactional
    public KioskLedgerLabStockResponse replayStock(Long stockId)
            throws ResourceNotFoundException {
        String actor = guard.requireEramirezUsername();
        if (!kioscoStockRepository.existsById(stockId)) {
            throw new ResourceNotFoundException("KioscoStock", stockId);
        }
        try {
            kioscoInventoryService.replayMovementStockChain(stockId);
        } finally {
            kioscoInventoryService.disableAdminMovementMutation();
        }
        log.warn("LEDGER_LAB_REPLAY actor={} stockId={}", actor, stockId);
        KioscoStockEntity stock = kioscoStockRepository.findById(stockId).orElseThrow();
        return toStockResponse(stock, resolveProduct(stock), resolveColor(stock), resolveLocation(stock), null);
    }

    private void validateUpsert(KioskLedgerLabMovementUpsertRequest request, boolean creating)
            throws BusinessException {
        if (request == null) {
            throw new BusinessException("Body requerido.");
        }
        if (creating) {
            if (request.getKioscoStockId() == null) {
                throw new BusinessException("kioscoStockId es obligatorio.");
            }
            if (request.getMovementType() == null) {
                throw new BusinessException("movementType es obligatorio.");
            }
            if (request.getQuantity() == null) {
                throw new BusinessException("quantity es obligatorio.");
            }
            validateQuantityForType(request.getMovementType(), request.getQuantity());
        } else if (request.getMovementType() != null && request.getQuantity() != null) {
            validateQuantityForType(request.getMovementType(), request.getQuantity());
        } else if (request.getQuantity() != null && request.getMovementType() == null) {
            // type kept from existing; checked in update path
        }
    }

    private void validateQuantityForType(KioscoMovementType type, int qty) throws BusinessException {
        if (type == KioscoMovementType.AJUSTE) {
            if (qty < 0) {
                throw new BusinessException("AJUSTE requiere quantity >= 0.");
            }
        } else if (qty <= 0) {
            throw new BusinessException(type + " requiere quantity > 0.");
        }
    }

    private KioskLedgerLabMovementResponse toLabMovement(KioscoMovementEntity entity) {
        KioscoMovementResponse base = kioscoInventoryService.toMovementResponse(entity);
        KioscoStockEntity stock = entity.getKioscoStock();
        if (stock == null && entity.getKioscoStockId() != null) {
            stock = kioscoStockRepository.findById(entity.getKioscoStockId()).orElse(null);
        }
        return KioskLedgerLabMovementResponse.builder()
                .id(base.getId())
                .kioscoStockId(base.getKioscoStockId())
                .locationId(base.getLocationId())
                .locationName(base.getLocationName())
                .productId(base.getProductId())
                .productCode(base.getProductCode())
                .productName(base.getProductName())
                .colorId(base.getColorId())
                .colorName(base.getColorName())
                .hardwareCondition(stock != null ? stock.getHardwareCondition() : null)
                .movementType(base.getMovementType())
                .quantity(base.getQuantity())
                .sizeKey(base.getSizeKey())
                .stockBefore(base.getStockBefore())
                .stockAfter(base.getStockAfter())
                .referenceId(base.getReferenceId())
                .referenceType(base.getReferenceType())
                .referenceNumber(base.getReferenceNumber())
                .referenceSummary(buildReferenceSummary(entity, base))
                .physicalCountId(entity.getPhysicalCountId())
                .physicalSlipNumber(base.getPhysicalSlipNumber())
                .reason(base.getReason())
                .affectsStock(base.getAffectsStock())
                .userId(base.getUserId())
                .username(base.getUsername())
                .originLocationId(base.getOriginLocationId())
                .originLocationName(base.getOriginLocationName())
                .originLocationCode(base.getOriginLocationCode())
                .destinationLocationId(base.getDestinationLocationId())
                .destinationLocationName(base.getDestinationLocationName())
                .destinationLocationCode(base.getDestinationLocationCode())
                .createdAt(base.getCreatedAt())
                .build();
    }

    private String buildReferenceSummary(KioscoMovementEntity entity, KioscoMovementResponse base) {
        if (entity.getReferenceId() == null) {
            if (base.getReferenceNumber() != null) {
                return base.getReferenceNumber();
            }
            return null;
        }
        if ("INVOICE".equals(base.getReferenceType())
                || entity.getMovementType() == KioscoMovementType.VENTA
                || entity.getMovementType() == KioscoMovementType.ANULACION) {
            return kioskSaleRepository.findById(entity.getReferenceId())
                    .map(sale -> {
                        StringBuilder sb = new StringBuilder("Venta ");
                        sb.append(sale.getSaleNumber() != null ? sale.getSaleNumber() : "#" + sale.getId());
                        if (sale.getTotalAmount() != null) {
                            sb.append(" · Q").append(sale.getTotalAmount().toPlainString());
                        }
                        if (sale.getSaleDate() != null) {
                            sb.append(" · ").append(sale.getSaleDate());
                        }
                        if (sale.getStatus() != null) {
                            sb.append(" · ").append(sale.getStatus());
                        }
                        return sb.toString();
                    })
                    .orElse(base.getReferenceNumber() != null
                            ? base.getReferenceNumber()
                            : "Ref #" + entity.getReferenceId());
        }
        if ("SHIPMENT".equals(base.getReferenceType())) {
            return productShipmentRepository.findById(entity.getReferenceId())
                    .map(ProductShipmentEntity::getShipmentNumber)
                    .map(n -> "Envío " + n)
                    .orElse(base.getReferenceNumber() != null
                            ? "Envío " + base.getReferenceNumber()
                            : "Envío #" + entity.getReferenceId());
        }
        if (base.getReferenceNumber() != null) {
            return base.getReferenceType() != null
                    ? base.getReferenceType() + " " + base.getReferenceNumber()
                    : base.getReferenceNumber();
        }
        return "Ref #" + entity.getReferenceId();
    }

    private KioskLedgerLabStockResponse toStockResponse(
            KioscoStockEntity stock,
            ProductEntity product,
            ColorEntity color,
            LocationEntity location,
            Integer movementCount
    ) {
        Map<String, Integer> sizes = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> e : ProductInventorySizesJson.parse(stock.getSizesData()).entrySet()) {
            sizes.put(e.getKey(), e.getValue() != null ? e.getValue().intValue() : 0);
        }
        return KioskLedgerLabStockResponse.builder()
                .id(stock.getId())
                .locationId(stock.getLocationId())
                .locationCode(location != null ? location.getCode() : null)
                .locationName(location != null ? location.getName() : null)
                .productId(stock.getProductId())
                .productCode(product != null ? product.getCode() : null)
                .productName(product != null ? product.getName() : null)
                .colorId(stock.getColorId())
                .colorName(color != null ? color.getName() : null)
                .currentStock(safeInt(stock.getCurrentStock()))
                .minimumStock(safeInt(stock.getMinimumStock()))
                .hardwareCondition(stock.getHardwareCondition())
                .sizesData(stock.getSizesData())
                .sizes(sizes.isEmpty() ? null : sizes)
                .movementCount(movementCount)
                .lastUpdatedAt(stock.getLastUpdatedAt())
                .build();
    }

    private ProductEntity resolveProduct(KioscoStockEntity stock) {
        if (stock.getProduct() != null) {
            return stock.getProduct();
        }
        return stock.getProductId() != null
                ? productRepository.findById(stock.getProductId()).orElse(null)
                : null;
    }

    private ColorEntity resolveColor(KioscoStockEntity stock) {
        if (stock.getColor() != null) {
            return stock.getColor();
        }
        return stock.getColorId() != null
                ? colorRepository.findById(stock.getColorId()).orElse(null)
                : null;
    }

    private LocationEntity resolveLocation(KioscoStockEntity stock) {
        if (stock.getLocation() != null) {
            return stock.getLocation();
        }
        return stock.getLocationId() != null
                ? locationRepository.findById(stock.getLocationId()).orElse(null)
                : null;
    }

    private static String normalizeTerm(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static int safeInt(Integer value) {
        return value != null ? value : 0;
    }
}
