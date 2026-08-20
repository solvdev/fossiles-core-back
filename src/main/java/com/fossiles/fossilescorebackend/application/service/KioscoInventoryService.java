package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.ProductInventoryLocationRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioscoInventoryTrasladoRequest;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoConsolidatedReportResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoInventoryInitializeResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoKardexReportResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoMovementResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoStockResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoTrasladoBoletaResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.util.KioskAccessHelper;
import com.fossiles.fossilescorebackend.application.util.KioscoInventoryInitRules;
import com.fossiles.fossilescorebackend.application.util.ProductAudienceCategory;
import com.fossiles.fossilescorebackend.application.util.ProductCinchoType;
import com.fossiles.fossilescorebackend.application.util.ProductHardwareCondition;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.InventoryTransfer;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoMovementEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoMovementType;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoStockEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductInventoryLocation;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductInventoryKardex;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductShipmentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductShipmentDetailEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.InventoryTransferRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoMovementRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoStockRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductInventoryLocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductInventoryKardexRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductShipmentDetailRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductShipmentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.CinchoProductUtils;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductInventorySizesJson;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(rollbackFor = Exception.class)
@Slf4j
public class KioscoInventoryService {

    private static final String REASON_NON_RESELLABLE = "producto no apto para reventa";
    public static final String SHIPMENT_RECEIPT_LINE_PREFIX = "SHIPMENT_RCPT:";

    public static String shipmentReceiptLineReason(String lineRef) {
        if (lineRef == null || lineRef.isBlank()) {
            return SHIPMENT_RECEIPT_LINE_PREFIX + "UNKNOWN";
        }
        return SHIPMENT_RECEIPT_LINE_PREFIX + lineRef.trim();
    }

    /**
     * Match a shipment receipt line token without confusing L1 with L10/L12.
     * Accepts either the raw lineRef ({@code ENV#L1}) or the prefixed reason key.
     */
    public static boolean reasonContainsShipmentReceiptLine(String reason, String lineRef) {
        if (reason == null || reason.isBlank() || lineRef == null || lineRef.isBlank()) {
            return false;
        }
        String trimmed = lineRef.trim();
        return containsExactLineToken(reason, shipmentReceiptLineReason(trimmed))
                || containsExactLineToken(reason, trimmed);
    }

    private static boolean containsExactLineToken(String reason, String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        int from = 0;
        while (true) {
            int idx = reason.indexOf(token, from);
            if (idx < 0) {
                return false;
            }
            int end = idx + token.length();
            if (end >= reason.length() || !Character.isDigit(reason.charAt(end))) {
                return true;
            }
            from = idx + 1;
        }
    }
    private static final String REFERENCE_KIOSCO_INVENTORY = "KIOSCO_INVENTORY";
    private static final String ADMIN_MOVEMENT_MUTATION_KEY = "app.kiosco_movement_admin_mutation";

    private final KioscoStockRepository kioscoStockRepository;
    private final KioscoStockProvisioningService kioscoStockProvisioningService;
    private final KioscoMovementRepository kioscoMovementRepository;
    private final LocationRepository locationRepository;
    private final ProductRepository productRepository;
    private final ColorRepository colorRepository;
    private final UserRepository userRepository;
    private final SecurityUtil securityUtil;
    private final ProductInventoryService productInventoryService;
    private final ProductInventoryLocationRepository productInventoryLocationRepository;
    private final KioskInventoryGuard kioskInventoryGuard;
    private final ProductShipmentRepository productShipmentRepository;
    private final ProductShipmentDetailRepository productShipmentDetailRepository;
    private final ProductInventoryKardexRepository productInventoryKardexRepository;
    private final InventoryTransferRepository inventoryTransferRepository;
    private final EntityManager entityManager;

    public KioscoStockResponse registrarEntrada(
            Long locationId,
            Long productId,
            Long colorId,
            Integer quantity,
            Long referenceId,
            Long userId
    ) throws BusinessException, ResourceNotFoundException {
        return registrarEntrada(locationId, productId, colorId, quantity, referenceId, userId, null);
    }

    public KioscoStockResponse registrarEntrada(
            Long locationId,
            Long productId,
            Long colorId,
            Integer quantity,
            Long referenceId,
            Long userId,
            String sizeKey
    ) throws BusinessException, ResourceNotFoundException {
        return registrarEntrada(locationId, productId, colorId, quantity, referenceId, userId, sizeKey, null);
    }

    public KioscoStockResponse registrarEntrada(
            Long locationId,
            Long productId,
            Long colorId,
            Integer quantity,
            Long referenceId,
            Long userId,
            String sizeKey,
            String hardwareCondition
    ) throws BusinessException, ResourceNotFoundException {
        return registrarEntradaInternal(
                locationId, productId, colorId, quantity, referenceId, userId, true, sizeKey, null, hardwareCondition);
    }

    public KioscoStockResponse registrarVenta(
            Long locationId,
            Long productId,
            Long colorId,
            Integer quantity,
            Long invoiceId,
            Long userId
    ) throws BusinessException, ResourceNotFoundException {
        return registrarVenta(locationId, productId, colorId, quantity, invoiceId, userId, null, null);
    }

    public KioscoStockResponse registrarVenta(
            Long locationId,
            Long productId,
            Long colorId,
            Integer quantity,
            Long invoiceId,
            Long userId,
            String sizeKey
    ) throws BusinessException, ResourceNotFoundException {
        return registrarVenta(locationId, productId, colorId, quantity, invoiceId, userId, sizeKey, null);
    }

    public KioscoStockResponse registrarVenta(
            Long locationId,
            Long productId,
            Long colorId,
            Integer quantity,
            Long invoiceId,
            Long userId,
            String sizeKey,
            String hardwareCondition
    ) throws BusinessException, ResourceNotFoundException {
        return registrarVentaInternal(
                locationId, productId, colorId, quantity, invoiceId, userId, true, sizeKey,
                resolveStockHardware(hardwareCondition));
    }

    public KioscoStockResponse registrarDevolucionDeposito(
            Long locationId,
            Long productId,
            Long colorId,
            Integer quantity,
            Long referenceId,
            Long userId
    ) throws BusinessException, ResourceNotFoundException {
        return registrarDevolucionDeposito(locationId, productId, colorId, quantity, referenceId, userId, null, null, null);
    }

    public KioscoStockResponse registrarDevolucionDeposito(
            Long locationId,
            Long productId,
            Long colorId,
            Integer quantity,
            Long referenceId,
            Long userId,
            String sizeKey
    ) throws BusinessException, ResourceNotFoundException {
        return registrarDevolucionDeposito(locationId, productId, colorId, quantity, referenceId, userId, sizeKey, null, null);
    }

    public KioscoStockResponse registrarDevolucionDeposito(
            Long locationId,
            Long productId,
            Long colorId,
            Integer quantity,
            Long referenceId,
            Long userId,
            String sizeKey,
            String physicalSlipNumber,
            String reason
    ) throws BusinessException, ResourceNotFoundException {
        return registrarDevolucionDeposito(
                locationId, productId, colorId, quantity, referenceId, userId, sizeKey, physicalSlipNumber, reason, null);
    }

    public KioscoStockResponse registrarDevolucionDeposito(
            Long locationId,
            Long productId,
            Long colorId,
            Integer quantity,
            Long referenceId,
            Long userId,
            String sizeKey,
            String physicalSlipNumber,
            String reason,
            Long physicalCountId
    ) throws BusinessException, ResourceNotFoundException {
        return registrarDevolucionDeposito(
                locationId, productId, colorId, quantity, referenceId, userId, sizeKey, physicalSlipNumber, reason,
                physicalCountId, null);
    }

    public KioscoStockResponse registrarDevolucionDeposito(
            Long locationId,
            Long productId,
            Long colorId,
            Integer quantity,
            Long referenceId,
            Long userId,
            String sizeKey,
            String physicalSlipNumber,
            String reason,
            Long physicalCountId,
            String hardwareCondition
    ) throws BusinessException, ResourceNotFoundException {
        return registrarDevolucionDepositoWithMovement(
                locationId, productId, colorId, quantity, referenceId, userId, sizeKey, physicalSlipNumber, reason,
                physicalCountId, hardwareCondition
        ).stockResponse();
    }

    public KioscoMovementWithStock registrarDevolucionDepositoWithMovement(
            Long locationId,
            Long productId,
            Long colorId,
            Integer quantity,
            Long referenceId,
            Long userId,
            String sizeKey,
            String physicalSlipNumber,
            String reason,
            Long physicalCountId
    ) throws BusinessException, ResourceNotFoundException {
        return registrarDevolucionDepositoWithMovement(
                locationId, productId, colorId, quantity, referenceId, userId, sizeKey, physicalSlipNumber, reason,
                physicalCountId, null);
    }

    public KioscoMovementWithStock registrarDevolucionDepositoWithMovement(
            Long locationId,
            Long productId,
            Long colorId,
            Integer quantity,
            Long referenceId,
            Long userId,
            String sizeKey,
            String physicalSlipNumber,
            String reason,
            Long physicalCountId,
            String hardwareCondition
    ) throws BusinessException, ResourceNotFoundException {
        String trimmedReason = safeTrim(reason);
        return applyStockMovementWithMovement(
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
                trimmedReason.isEmpty() ? null : trimmedReason,
                sizeKey,
                true,
                physicalSlipNumber,
                physicalCountId,
                resolveStockHardware(hardwareCondition)
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
        return registrarDevolucionCliente(
                locationId, productId, colorId, quantity, originalInvoiceId, apto, userId, null);
    }

    public KioscoStockResponse registrarDevolucionCliente(
            Long locationId,
            Long productId,
            Long colorId,
            Integer quantity,
            Long originalInvoiceId,
            Boolean apto,
            Long userId,
            String sizeKey
    ) throws BusinessException, ResourceNotFoundException {
        return registrarDevolucionCliente(
                locationId, productId, colorId, quantity, originalInvoiceId, apto, userId, sizeKey, null);
    }

    public KioscoStockResponse registrarDevolucionCliente(
            Long locationId,
            Long productId,
            Long colorId,
            Integer quantity,
            Long originalInvoiceId,
            Boolean apto,
            Long userId,
            String sizeKey,
            String physicalSlipNumber
    ) throws BusinessException, ResourceNotFoundException {
        return registrarDevolucionCliente(
                locationId, productId, colorId, quantity, originalInvoiceId, apto, userId, sizeKey, physicalSlipNumber,
                null);
    }

    public KioscoStockResponse registrarDevolucionCliente(
            Long locationId,
            Long productId,
            Long colorId,
            Integer quantity,
            Long originalInvoiceId,
            Boolean apto,
            Long userId,
            String sizeKey,
            String physicalSlipNumber,
            Long physicalCountId
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
                    sizeKey,
                    true,
                    physicalSlipNumber,
                    physicalCountId
            );
        }

        KioscoStockEntity stock = getOrCreateLockedStock(locationId, productId, colorId, resolvedUserId);
        int before = safeInt(stock.getCurrentStock());
        int after = before;
        saveMovement(stock, KioscoMovementType.DEVOLUCION_CLIENTE, quantity, before, after,
                originalInvoiceId, null, false, resolvedUserId, null, null, physicalSlipNumber, null,
                physicalCountId);
        saveMovement(stock, KioscoMovementType.MERMA, quantity, before, after,
                originalInvoiceId, REASON_NON_RESELLABLE, false, resolvedUserId, null, null, physicalSlipNumber,
                null, physicalCountId);
        return toStockResponse(stock);
    }

    public TrasladoResult registrarTraslado(
            Long locationOriginId,
            Long locationDestinationId,
            Long productId,
            Long colorId,
            Integer quantity,
            Long userId,
            String physicalSlipNumber
    ) throws BusinessException, ResourceNotFoundException {
        return registrarTraslado(KioscoInventoryTrasladoRequest.builder()
                .locationOriginId(locationOriginId)
                .locationDestinationId(locationDestinationId)
                .productId(productId)
                .colorId(colorId)
                .quantity(quantity)
                .userId(userId)
                .physicalSlipNumber(physicalSlipNumber)
                .build());
    }

    public TrasladoResult registrarTraslado(
            Long locationOriginId,
            Long locationDestinationId,
            Long productId,
            Long colorId,
            Integer quantity,
            Long userId
    ) throws BusinessException, ResourceNotFoundException {
        return registrarTraslado(locationOriginId, locationDestinationId, productId, colorId, quantity, userId, null);
    }

    public TrasladoResult registrarTrasladoDesdeIntegracion(
            Long locationOriginId,
            Long locationDestinationId,
            Long productId,
            Long colorId,
            BigDecimal quantity,
            Long userId
    ) throws BusinessException, ResourceNotFoundException {
        return registrarTrasladoDesdeIntegracion(
                locationOriginId, locationDestinationId, productId, colorId, quantity, userId, null);
    }

    public TrasladoResult registrarTrasladoDesdeIntegracion(
            Long locationOriginId,
            Long locationDestinationId,
            Long productId,
            Long colorId,
            BigDecimal quantity,
            Long userId,
            Long transferId
    ) throws BusinessException, ResourceNotFoundException {
        int qty = normalizePositiveIntegerQuantity(quantity);
        return registrarTrasladoInternal(
                locationOriginId, locationDestinationId, productId, colorId, qty, userId, false, null, null, transferId);
    }

    public TrasladoResult registrarTraslado(KioscoInventoryTrasladoRequest request)
            throws BusinessException, ResourceNotFoundException {
        if (request == null) {
            throw new BusinessException("La solicitud de traslado es obligatoria.");
        }
        List<KioscoInventoryTrasladoRequest.Item> items = resolveTrasladoItems(request);
        if (items.isEmpty()) {
            throw new BusinessException("Agrega al menos un producto al traslado.");
        }
        Long originId = request.getLocationOriginId();
        Long destId = request.getLocationDestinationId();
        if (Objects.equals(originId, destId)) {
            throw new BusinessException("El origen y el destino deben ser distintos.");
        }
        Long resolvedUserId = resolveUserIdRequired(request.getUserId());
        validateLocationIsKiosk(originId);
        validateLocationIsKiosk(destId);

        String slip = normalizePhysicalSlipNumber(request.getPhysicalSlipNumber());
        if (slip == null) {
            throw new BusinessException("Debes indicar el número de boleta física.");
        }
        ExistingTrasladoBoleta existing = resolveExistingTrasladoBoleta(slip, originId, destId);
        rejectDuplicateTrasladoBoletaLines(slip, items);
        Long transferReferenceId = existing != null && existing.referenceId() != null
                ? existing.referenceId()
                : generateTransferReferenceId();

        LocationEntity fromLocation = locationRepository.findById(originId).orElse(null);
        LocationEntity toLocation = locationRepository.findById(destId).orElse(null);
        String trasladoReason = buildTransferReason(fromLocation, toLocation);

        KioscoStockResponse lastOrigin = null;
        KioscoStockResponse lastDestination = null;
        for (KioscoInventoryTrasladoRequest.Item item : items) {
            validateQuantity(item.getQuantity());
            validateProduct(item.getProductId());
            validateColor(item.getColorId());
            String sizeKey = ProductInventorySizesJson.normalizeKey(item.getSizeKey());
            String sizeKeyOrNull = sizeKey.isEmpty() ? null : sizeKey;

            String hardware = resolveStockHardware(item.getHardwareCondition());
            lastOrigin = applyStockMovement(
                    originId,
                    item.getProductId(),
                    item.getColorId(),
                    item.getQuantity(),
                    transferReferenceId,
                    originId,
                    destId,
                    resolvedUserId,
                    KioscoMovementType.TRASLADO_SALIDA,
                    -item.getQuantity(),
                    true,
                    trasladoReason,
                    sizeKeyOrNull,
                    true,
                    slip,
                    null,
                    hardware
            );
            lastDestination = applyStockMovement(
                    destId,
                    item.getProductId(),
                    item.getColorId(),
                    item.getQuantity(),
                    transferReferenceId,
                    originId,
                    destId,
                    resolvedUserId,
                    KioscoMovementType.TRASLADO_ENTRADA,
                    item.getQuantity(),
                    true,
                    trasladoReason,
                    sizeKeyOrNull,
                    true,
                    slip,
                    null,
                    hardware
            );
        }

        return TrasladoResult.builder()
                .referenceId(transferReferenceId)
                .originStock(lastOrigin)
                .destinationStock(lastDestination)
                .appended(existing != null)
                .lineCount(items.size())
                .physicalSlipNumber(slip)
                .build();
    }

    @Transactional(readOnly = true)
    public KioscoTrasladoBoletaResponse lookupTrasladoBoleta(String physicalSlipNumber)
            throws BusinessException {
        String slip = normalizePhysicalSlipNumber(physicalSlipNumber);
        if (slip == null) {
            throw new BusinessException("Indica el número de boleta física.");
        }
        List<KioscoMovementEntity> movements = kioscoMovementRepository.findByPhysicalSlipNumber(slip);
        if (movements == null || movements.isEmpty()) {
            return KioscoTrasladoBoletaResponse.builder()
                    .exists(false)
                    .physicalSlipNumber(slip)
                    .lines(List.of())
                    .build();
        }
        boolean onlyTraslado = movements.stream().allMatch(m ->
                m.getMovementType() == KioscoMovementType.TRASLADO_SALIDA
                        || m.getMovementType() == KioscoMovementType.TRASLADO_ENTRADA);
        if (!onlyTraslado) {
            throw new BusinessException(
                    "La boleta ya está registrada en otro tipo de movimiento (no es un traslado entre kioskos).");
        }
        KioscoMovementEntity sample = movements.stream()
                .filter(m -> m.getMovementType() == KioscoMovementType.TRASLADO_SALIDA)
                .findFirst()
                .orElse(movements.get(0));
        Long originId = sample.getOriginLocationId();
        Long destId = sample.getDestinationLocationId();
        LocationEntity origin = resolveLocation(originId);
        LocationEntity dest = resolveLocation(destId);

        List<KioscoTrasladoBoletaResponse.Line> lines = new ArrayList<>();
        for (KioscoMovementEntity m : movements) {
            if (m.getMovementType() != KioscoMovementType.TRASLADO_SALIDA) {
                continue;
            }
            KioscoStockEntity stock = m.getKioscoStock();
            ProductEntity product = stock != null ? stock.getProduct() : null;
            ColorEntity color = stock != null ? stock.getColor() : null;
            lines.add(KioscoTrasladoBoletaResponse.Line.builder()
                    .productId(stock != null ? stock.getProductId() : null)
                    .productCode(product != null ? product.getCode() : null)
                    .productName(product != null ? product.getName() : null)
                    .colorId(stock != null ? stock.getColorId() : null)
                    .colorName(color != null ? color.getName() : null)
                    .sizeKey(m.getSizeKey())
                    .quantity(m.getQuantity())
                    .movementType(String.valueOf(m.getMovementType()))
                    .build());
        }

        return KioscoTrasladoBoletaResponse.builder()
                .exists(true)
                .physicalSlipNumber(slip)
                .referenceId(sample.getReferenceId())
                .locationOriginId(originId)
                .locationDestinationId(destId)
                .locationOriginName(locationName(origin))
                .locationDestinationName(locationName(dest))
                .lines(lines)
                .build();
    }

    private List<KioscoInventoryTrasladoRequest.Item> resolveTrasladoItems(KioscoInventoryTrasladoRequest request)
            throws BusinessException {
        if (request.getItems() != null && !request.getItems().isEmpty()) {
            List<KioscoInventoryTrasladoRequest.Item> out = new ArrayList<>();
            for (KioscoInventoryTrasladoRequest.Item item : request.getItems()) {
                if (item == null || item.getProductId() == null) {
                    continue;
                }
                if (item.getQuantity() == null || item.getQuantity() < 1) {
                    throw new BusinessException("Cada línea del traslado debe tener cantidad mayor a cero.");
                }
                out.add(item);
            }
            return out;
        }
        if (request.getProductId() == null || request.getQuantity() == null) {
            return List.of();
        }
        return List.of(KioscoInventoryTrasladoRequest.Item.builder()
                .productId(request.getProductId())
                .colorId(request.getColorId())
                .quantity(request.getQuantity())
                .sizeKey(request.getSizeKey())
                .hardwareCondition(request.getHardwareCondition())
                .build());
    }

    private record ExistingTrasladoBoleta(Long referenceId, Long originId, Long destinationId) {}

    /**
     * Rechaza líneas duplicadas (mismo producto+color+talla+cantidad) en la solicitud
     * o ya registradas en la misma boleta física.
     */
    private void rejectDuplicateTrasladoBoletaLines(
            String slip,
            List<KioscoInventoryTrasladoRequest.Item> items
    ) throws BusinessException {
        Set<String> seenInRequest = new HashSet<>();
        for (KioscoInventoryTrasladoRequest.Item item : items) {
            String key = trasladoBoletaLineKey(item.getProductId(), item.getColorId(), item.getSizeKey(), item.getQuantity());
            if (!seenInRequest.add(key)) {
                throw new BusinessException(
                        "La boleta tiene una línea duplicada (mismo producto, color, talla y cantidad).");
            }
            String sizeKey = ProductInventorySizesJson.normalizeKey(item.getSizeKey());
            String sizeKeyOrNull = sizeKey.isEmpty() ? null : sizeKey;
            if (kioscoMovementRepository.existsTrasladoBoletaDuplicateLine(
                    slip, item.getProductId(), item.getColorId(), sizeKeyOrNull, item.getQuantity())) {
                throw new BusinessException(
                        "Esa línea ya está registrada en la boleta (mismo producto, color, talla y cantidad).");
            }
        }
    }

    private static String trasladoBoletaLineKey(Long productId, Long colorId, String sizeKey, Integer quantity) {
        String size = ProductInventorySizesJson.normalizeKey(sizeKey);
        return String.valueOf(productId) + '|'
                + (colorId == null ? "" : colorId) + '|'
                + size + '|'
                + (quantity == null ? "" : quantity);
    }

    /**
     * Si la boleta ya existe como traslado entre los mismos kioskos, permite anexar productos.
     * Si existe con otra ruta u otro tipo de movimiento, rechaza.
     */
    private ExistingTrasladoBoleta resolveExistingTrasladoBoleta(
            String slip, Long originId, Long destinationId
    ) throws BusinessException {
        List<KioscoMovementEntity> existing = kioscoMovementRepository.findByPhysicalSlipNumber(slip);
        if (existing == null || existing.isEmpty()) {
            return null;
        }
        boolean onlyTraslado = existing.stream().allMatch(m ->
                m.getMovementType() == KioscoMovementType.TRASLADO_SALIDA
                        || m.getMovementType() == KioscoMovementType.TRASLADO_ENTRADA);
        if (!onlyTraslado) {
            throw new BusinessException(
                    "El número de boleta física ya fue registrado en otro tipo de movimiento de inventario kiosko.");
        }
        KioscoMovementEntity sample = existing.stream()
                .filter(m -> m.getMovementType() == KioscoMovementType.TRASLADO_SALIDA)
                .findFirst()
                .orElse(existing.get(0));
        Long existingOrigin = sample.getOriginLocationId();
        Long existingDest = sample.getDestinationLocationId();
        if (!Objects.equals(existingOrigin, originId) || !Objects.equals(existingDest, destinationId)) {
            throw new BusinessException(
                    "La boleta ya pertenece a un traslado entre otros kioskos. Usa el mismo origen y destino para añadir productos.");
        }
        return new ExistingTrasladoBoleta(sample.getReferenceId(), existingOrigin, existingDest);
    }

    private TrasladoResult registrarTrasladoInternal(
            Long locationOriginId,
            Long locationDestinationId,
            Long productId,
            Long colorId,
            Integer quantity,
            Long userId,
            boolean syncLegacy,
            String physicalSlipNumber,
            String sizeKey,
            Long reuseReferenceId
    ) throws BusinessException, ResourceNotFoundException {
        if (!syncLegacy) {
            // Integración: sin boleta física, un solo ítem.
            validateQuantity(quantity);
            if (Objects.equals(locationOriginId, locationDestinationId)) {
                throw new BusinessException("El origen y el destino deben ser distintos.");
            }
            Long resolvedUserId = resolveUserIdRequired(userId);
            validateLocationIsKiosk(locationOriginId);
            validateLocationIsKiosk(locationDestinationId);
            validateProduct(productId);
            validateColor(colorId);
            Long transferReferenceId = reuseReferenceId != null ? reuseReferenceId : generateTransferReferenceId();
            String sizeKeyOrNull = ProductInventorySizesJson.normalizeKey(sizeKey);
            sizeKeyOrNull = sizeKeyOrNull.isEmpty() ? null : sizeKeyOrNull;

            boolean salidaDone = reuseReferenceId != null
                    && kioscoMovementRepository.existsInventoryTransferMovement(
                    locationOriginId, productId, colorId, reuseReferenceId, KioscoMovementType.TRASLADO_SALIDA);
            boolean entradaDone = reuseReferenceId != null
                    && kioscoMovementRepository.existsInventoryTransferMovement(
                    locationDestinationId, productId, colorId, reuseReferenceId, KioscoMovementType.TRASLADO_ENTRADA);
            if (salidaDone && entradaDone) {
                log.info(
                        "KIOSCO_TRASLADO_INTEGRACION_SKIPPED origin={} dest={} productId={} colorId={} transferId={} (already applied)",
                        locationOriginId, locationDestinationId, productId, colorId, reuseReferenceId);
                return TrasladoResult.builder()
                        .referenceId(transferReferenceId)
                        .originStock(resolveStockResponseForNoOp(locationOriginId, productId, colorId, null))
                        .destinationStock(resolveStockResponseForNoOp(locationDestinationId, productId, colorId, null))
                        .build();
            }

            LocationEntity fromLocation = locationRepository.findById(locationOriginId).orElse(null);
            LocationEntity toLocation = locationRepository.findById(locationDestinationId).orElse(null);
            String trasladoReason = buildTransferReason(fromLocation, toLocation);
            KioscoStockResponse origin = salidaDone
                    ? resolveStockResponseForNoOp(locationOriginId, productId, colorId, null)
                    : applyStockMovement(
                    locationOriginId, productId, colorId, quantity, transferReferenceId,
                    locationOriginId, locationDestinationId, resolvedUserId,
                    KioscoMovementType.TRASLADO_SALIDA, -quantity, true, trasladoReason,
                    sizeKeyOrNull, false, physicalSlipNumber);
            KioscoStockResponse destination = entradaDone
                    ? resolveStockResponseForNoOp(locationDestinationId, productId, colorId, null)
                    : applyStockMovement(
                    locationDestinationId, productId, colorId, quantity, transferReferenceId,
                    locationOriginId, locationDestinationId, resolvedUserId,
                    KioscoMovementType.TRASLADO_ENTRADA, quantity, true, trasladoReason,
                    sizeKeyOrNull, false, physicalSlipNumber);
            return TrasladoResult.builder()
                    .referenceId(transferReferenceId)
                    .originStock(origin)
                    .destinationStock(destination)
                    .build();
        }
        return registrarTraslado(KioscoInventoryTrasladoRequest.builder()
                .locationOriginId(locationOriginId)
                .locationDestinationId(locationDestinationId)
                .productId(productId)
                .colorId(colorId)
                .quantity(quantity)
                .userId(userId)
                .physicalSlipNumber(physicalSlipNumber)
                .sizeKey(sizeKey)
                .build());
    }

    public KioscoStockResponse registrarEntradaPorTransferenciaInventario(
            Long kioskLocationId,
            Long fromLocationId,
            Long productId,
            Long colorId,
            BigDecimal quantity,
            Long transferId,
            Long userId
    ) throws BusinessException, ResourceNotFoundException {
        if (transferId == null) {
            throw new BusinessException("La transferencia de inventario requiere referencia.");
        }
        validateLocationIsKiosk(kioskLocationId);
        if (kioscoMovementRepository.existsInventoryTransferMovement(
                kioskLocationId, productId, colorId, transferId, KioscoMovementType.ENTRADA)) {
            return kioscoStockRepository.findByLocationIdAndProductIdAndColorId(kioskLocationId, productId, colorId)
                    .map(this::toStockResponse)
                    .orElseThrow(() -> new ResourceNotFoundException("KioscoStock", kioskLocationId));
        }
        int qty = normalizePositiveIntegerQuantity(quantity);
        LocationEntity fromLocation = fromLocationId != null
                ? locationRepository.findById(fromLocationId).orElse(null)
                : null;
        LocationEntity toLocation = locationRepository.findById(kioskLocationId).orElse(null);
        String reason = buildTransferReason(fromLocation, toLocation);
        return applyStockMovement(
                kioskLocationId,
                productId,
                colorId,
                qty,
                transferId,
                fromLocationId,
                kioskLocationId,
                resolveUserIdRequired(userId),
                KioscoMovementType.ENTRADA,
                qty,
                true,
                reason,
                null,
                false
        );
    }

    public KioscoStockResponse registrarSalidaPorTransferenciaInventario(
            Long kioskLocationId,
            Long toLocationId,
            Long productId,
            Long colorId,
            BigDecimal quantity,
            Long transferId,
            Long userId
    ) throws BusinessException, ResourceNotFoundException {
        if (transferId == null) {
            throw new BusinessException("La transferencia de inventario requiere referencia.");
        }
        validateLocationIsKiosk(kioskLocationId);
        if (kioscoMovementRepository.existsInventoryTransferMovement(
                kioskLocationId, productId, colorId, transferId, KioscoMovementType.DEVOLUCION_DEPOSITO)) {
            return kioscoStockRepository.findByLocationIdAndProductIdAndColorId(kioskLocationId, productId, colorId)
                    .map(this::toStockResponse)
                    .orElseThrow(() -> new ResourceNotFoundException("KioscoStock", kioskLocationId));
        }
        int qty = normalizePositiveIntegerQuantity(quantity);
        LocationEntity fromLocation = locationRepository.findById(kioskLocationId).orElse(null);
        LocationEntity toLocation = toLocationId != null
                ? locationRepository.findById(toLocationId).orElse(null)
                : null;
        String reason = buildTransferReason(fromLocation, toLocation);
        return applyStockMovement(
                kioskLocationId,
                productId,
                colorId,
                qty,
                transferId,
                kioskLocationId,
                toLocationId,
                resolveUserIdRequired(userId),
                KioscoMovementType.DEVOLUCION_DEPOSITO,
                -qty,
                true,
                reason,
                null,
                false
        );
    }

    private static String buildTransferReason(LocationEntity fromLocation, LocationEntity toLocation) {
        String from = fromLocation != null ? locationLabel(fromLocation) : "origen";
        String to = toLocation != null ? locationLabel(toLocation) : "destino";
        return "Transferencia de " + from + " a " + to;
    }

    private static String locationLabel(LocationEntity location) {
        if (location == null) {
            return "—";
        }
        if (location.getName() != null && !location.getName().isBlank()) {
            return location.getName().trim();
        }
        if (location.getCode() != null && !location.getCode().isBlank()) {
            return location.getCode().trim();
        }
        return String.valueOf(location.getId());
    }

    public CambioResult registrarCambio(
            Long locationId,
            Long returnedProductId,
            Long returnedColorId,
            Long givenProductId,
            Long givenColorId,
            Integer quantity,
            Long referenceId,
            String reason,
            Long userId,
            String physicalSlipNumber,
            String returnedSize,
            String givenSize
    ) throws BusinessException, ResourceNotFoundException {
        return registrarCambio(
                locationId,
                returnedProductId,
                returnedColorId,
                givenProductId,
                givenColorId,
                quantity,
                quantity,
                referenceId,
                reason,
                userId,
                physicalSlipNumber,
                returnedSize,
                givenSize
        );
    }

    /**
     * Cambio: ingreso del producto devuelto ({@code CAMBIO +}) y egreso del entregado
     * ({@code DEVOLUCION_A_CLIENTE −} → Sal. en kardex).
     * Stock fuente de verdad: módulo kiosco (no legacy). Herraje del egreso = el indicado o el que tenga
     * disponibilidad (NUEVO → VIEJO), igual que ventas POS.
     */
    public CambioResult registrarCambio(
            Long locationId,
            Long returnedProductId,
            Long returnedColorId,
            Long givenProductId,
            Long givenColorId,
            Integer returnedQuantity,
            Integer givenQuantity,
            Long referenceId,
            String reason,
            Long userId,
            String physicalSlipNumber,
            String returnedSize,
            String givenSize
    ) throws BusinessException, ResourceNotFoundException {
        return registrarCambio(
                locationId,
                returnedProductId,
                returnedColorId,
                givenProductId,
                givenColorId,
                returnedQuantity,
                givenQuantity,
                referenceId,
                reason,
                userId,
                physicalSlipNumber,
                returnedSize,
                givenSize,
                null,
                null
        );
    }

    public CambioResult registrarCambio(
            Long locationId,
            Long returnedProductId,
            Long returnedColorId,
            Long givenProductId,
            Long givenColorId,
            Integer returnedQuantity,
            Integer givenQuantity,
            Long referenceId,
            String reason,
            Long userId,
            String physicalSlipNumber,
            String returnedSize,
            String givenSize,
            String returnedHardwareCondition,
            String givenHardwareCondition
    ) throws BusinessException, ResourceNotFoundException {
        Long resolvedUserId = resolveUserIdRequired(userId);
        validateLocationIsKiosk(locationId);
        validateProduct(returnedProductId);
        validateColor(returnedColorId);
        validateProduct(givenProductId);
        validateColor(givenColorId);
        validateQuantity(returnedQuantity);
        validateQuantity(givenQuantity);

        String trimmedReason = safeTrim(reason);
        String reasonOrNull = trimmedReason.isEmpty() ? null : trimmedReason;
        String returnedHardware = resolveStockHardware(returnedHardwareCondition);
        String givenHardware = resolveHardwareForKioskEgress(
                locationId, givenProductId, givenColorId, givenSize, givenQuantity, givenHardwareCondition);

        // syncLegacy=false: el cambio no debe fallar por inventario legacy desfasado.
        KioscoMovementWithStock returnedMovement = applyStockMovementWithMovement(
                locationId,
                returnedProductId,
                returnedColorId,
                returnedQuantity,
                referenceId,
                null,
                null,
                resolvedUserId,
                KioscoMovementType.CAMBIO,
                returnedQuantity,
                true,
                reasonOrNull,
                returnedSize,
                false,
                physicalSlipNumber,
                null,
                returnedHardware
        );

        KioscoMovementWithStock givenMovement = applyStockMovementWithMovement(
                locationId,
                givenProductId,
                givenColorId,
                givenQuantity,
                referenceId,
                null,
                null,
                resolvedUserId,
                KioscoMovementType.DEVOLUCION_A_CLIENTE,
                -givenQuantity,
                true,
                reasonOrNull,
                givenSize,
                false,
                physicalSlipNumber,
                null,
                givenHardware
        );

        verificarStockMinimo(locationId, givenProductId, givenColorId);

        return CambioResult.builder()
                .returnedStock(returnedMovement.stockResponse())
                .givenStock(givenMovement.stockResponse())
                .returnedMovementId(returnedMovement.movement().getId())
                .givenMovementId(givenMovement.movement().getId())
                .build();
    }

    /**
     * Elige herraje del egreso en stock kiosco: preferido si alcanza; si no, NUEVO luego VIEJO.
     */
    private String resolveHardwareForKioskEgress(
            Long locationId,
            Long productId,
            Long colorId,
            String sizeKey,
            int quantity,
            String preferredHardware
    ) {
        String preferred = ProductHardwareCondition.normalize(preferredHardware);
        if (preferred != null && kioskStockCovers(locationId, productId, colorId, preferred, sizeKey, quantity)) {
            return preferred;
        }
        for (String hardware : List.of(ProductHardwareCondition.NUEVO, ProductHardwareCondition.VIEJO)) {
            if (preferred != null && preferred.equals(hardware)) {
                continue;
            }
            if (kioskStockCovers(locationId, productId, colorId, hardware, sizeKey, quantity)) {
                return hardware;
            }
        }
        return preferred != null ? preferred : ProductHardwareCondition.NUEVO;
    }

    private boolean kioskStockCovers(
            Long locationId,
            Long productId,
            Long colorId,
            String hardware,
            String sizeKey,
            int quantity
    ) {
        return kioscoStockRepository
                .findByLocationIdAndProductIdAndColorIdAndHardwareCondition(
                        locationId, productId, colorId, resolveStockHardware(hardware))
                .map(stock -> {
                    syncFossCurrentStockFromSizes(stock);
                    Map<String, BigDecimal> sizes = ProductInventorySizesJson.parse(stock.getSizesData());
                    if (!sizes.isEmpty()) {
                        String normalized = ProductInventorySizesJson.normalizeKey(sizeKey);
                        if (normalized.isEmpty()) {
                            return false;
                        }
                        return sizes.getOrDefault(normalized, BigDecimal.ZERO)
                                .compareTo(BigDecimal.valueOf(quantity)) >= 0;
                    }
                    return safeInt(stock.getCurrentStock()) >= quantity;
                })
                .orElse(false);
    }

    public CambioResult registrarCambio(
            Long locationId,
            Long returnedProductId,
            Long returnedColorId,
            Long givenProductId,
            Long givenColorId,
            Integer quantity,
            Long referenceId,
            String reason,
            Long userId
    ) throws BusinessException, ResourceNotFoundException {
        return registrarCambio(
                locationId,
                returnedProductId,
                returnedColorId,
                givenProductId,
                givenColorId,
                quantity,
                referenceId,
                reason,
                userId,
                null,
                null,
                null
        );
    }

    public KioscoStockResponse registrarMerma(
            Long locationId,
            Long productId,
            Long colorId,
            Integer quantity,
            String reason,
            Long userId
    ) throws BusinessException, ResourceNotFoundException {
        return registrarMerma(locationId, productId, colorId, quantity, reason, userId, null);
    }

    public KioscoStockResponse registrarMerma(
            Long locationId,
            Long productId,
            Long colorId,
            Integer quantity,
            String reason,
            Long userId,
            String sizeKey
    ) throws BusinessException, ResourceNotFoundException {
        return registrarMerma(locationId, productId, colorId, quantity, reason, userId, sizeKey, null);
    }

    public KioscoStockResponse registrarMerma(
            Long locationId,
            Long productId,
            Long colorId,
            Integer quantity,
            String reason,
            Long userId,
            String sizeKey,
            String hardwareCondition
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
                sizeKey,
                true,
                null,
                null,
                resolveStockHardware(hardwareCondition)
        );
    }

    /**
     * Ajuste relativo: suma (INGRESO) o resta (EGRESO) {@code quantity} sobre el stock actual.
     * Genera movimiento {@link KioscoMovementType#AJUSTE} (Comp. / Sal. según el signo).
     */
    public KioscoStockResponse registrarAjustePorDelta(
            Long locationId,
            Long productId,
            Long colorId,
            Integer quantity,
            String direction,
            String reason,
            Long userId,
            String sizeKey,
            String hardwareCondition
    ) throws BusinessException, ResourceNotFoundException {
        if (quantity == null || quantity <= 0) {
            throw new BusinessException("La cantidad del ajuste debe ser un entero mayor a cero.");
        }
        if (safeTrim(reason).isEmpty()) {
            throw new BusinessException("El motivo del ajuste es obligatorio.");
        }
        String dir = safeTrim(direction).toUpperCase();
        if (!"INGRESO".equals(dir) && !"EGRESO".equals(dir)) {
            throw new BusinessException("Indica si el ajuste es INGRESO o EGRESO.");
        }
        int delta = "INGRESO".equals(dir) ? quantity : -quantity;
        return applyStockMovement(
                locationId,
                productId,
                colorId,
                quantity,
                null,
                null,
                null,
                resolveUserIdRequired(userId),
                KioscoMovementType.AJUSTE,
                delta,
                true,
                reason.trim(),
                sizeKey,
                true,
                null,
                null,
                resolveStockHardware(hardwareCondition)
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
        return registrarAjuste(locationId, productId, colorId, realQuantity, null, reason, userId);
    }

    public KioscoStockResponse registrarAjuste(
            Long locationId,
            Long productId,
            Long colorId,
            Integer realQuantity,
            Map<String, Integer> realSizes,
            String reason,
            Long userId
    ) throws BusinessException, ResourceNotFoundException {
        return registrarAjuste(locationId, productId, colorId, realQuantity, realSizes, reason, userId, null);
    }

    public KioscoStockResponse registrarAjuste(
            Long locationId,
            Long productId,
            Long colorId,
            Integer realQuantity,
            Map<String, Integer> realSizes,
            String reason,
            Long userId,
            String hardwareCondition
    ) throws BusinessException, ResourceNotFoundException {
        if (realQuantity == null || realQuantity < 0) {
            throw new BusinessException(
                    "Indica quantity+direction (ingreso/egreso) o realQuantity (stock objetivo) para el ajuste.");
        }
        if (safeTrim(reason).isEmpty()) {
            throw new BusinessException("El motivo del ajuste es obligatorio.");
        }

        Long resolvedUserId = resolveUserIdRequired(userId);
        validateLocationIsKiosk(locationId);
        validateProduct(productId);
        validateColor(colorId);
        ProductEntity product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));

        String hardware = resolveStockHardware(hardwareCondition);
        KioscoStockEntity stock = getOrCreateLockedStock(locationId, productId, colorId, resolvedUserId, hardware);
        syncFossCurrentStockFromSizes(stock);
        stock = kioscoStockRepository.findForUpdateByHardware(locationId, productId, colorId, hardware).orElse(stock);

        Map<String, BigDecimal> targetSizes = normalizeRealSizesMap(realSizes);
        if (CinchoProductUtils.isFossCinchoProduct(product)) {
            if (targetSizes == null) {
                throw new BusinessException(
                        "Los cinchos FOSS requieren desglose por talla: envíe realSizes con la cantidad contada por talla.");
            }
            int targetTotal = ProductInventorySizesJson.sum(targetSizes).setScale(0, RoundingMode.HALF_UP).intValue();
            if (realQuantity != targetTotal) {
                throw new BusinessException("realQuantity debe coincidir con la suma de realSizes (" + targetTotal + ").");
            }
            int before = safeInt(stock.getCurrentStock());
            int delta = targetTotal - before;
            stock.setSizesData(ProductInventorySizesJson.serializeIncludingZeros(targetSizes));
            stock.setCurrentStock(targetTotal);
            stock.setUpdatedBy(resolvedUserId);
            stock.setLastUpdatedAt(GuatemalaDateTime.now());
            KioscoStockEntity savedStock = kioscoStockRepository.save(stock);

            saveMovement(savedStock, KioscoMovementType.AJUSTE, Math.abs(delta), before, targetTotal,
                    null, reason.trim(), true, resolvedUserId, null, null);

            syncLegacyInventoryToTargetSizes(locationId, productId, colorId, targetSizes);
            if (delta < 0) {
                verificarStockMinimo(locationId, productId, colorId);
            }
            return toStockResponse(savedStock);
        }

        int before = safeInt(stock.getCurrentStock());
        int delta = realQuantity - before;
        int after = before + delta;
        if (after < 0) {
            throw new BusinessException("El ajuste resultaría en stock negativo.");
        }

        stock.setCurrentStock(after);
        stock.setUpdatedBy(resolvedUserId);
        stock.setLastUpdatedAt(GuatemalaDateTime.now());
        KioscoStockEntity savedStock = kioscoStockRepository.save(stock);

        saveMovement(savedStock, KioscoMovementType.AJUSTE, Math.abs(delta), before, after,
                null, reason.trim(), true, resolvedUserId, null, null);

        syncLegacyInventory(locationId, productId, colorId, delta, null);
        if (delta < 0) {
            verificarStockMinimo(locationId, productId, colorId);
        }

        return toStockResponse(savedStock);
    }

    /**
     * Inventario inicial (migración): lleva el stock al objetivo con ENTRADA (y MERMA si sobra).
     * Cinchos FOSS: un movimiento por talla con {@code size_key} para que el conteo físico cuadre por talla.
     */
    public KioscoStockResponse registrarInventarioInicial(
            Long locationId,
            Long productId,
            Long colorId,
            Integer targetQuantity,
            Map<String, Integer> targetSizes,
            String reason,
            Long userId,
            String hardwareCondition
    ) throws BusinessException, ResourceNotFoundException {
        if (targetQuantity == null || targetQuantity < 0) {
            throw new BusinessException("La cantidad objetivo no puede ser negativa.");
        }
        if (safeTrim(reason).isEmpty()) {
            throw new BusinessException("El motivo del inventario inicial es obligatorio.");
        }

        Long resolvedUserId = resolveUserIdRequired(userId);
        validateLocationIsKiosk(locationId);
        validateProduct(productId);
        validateColor(colorId);
        ProductEntity product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));

        String hardware = resolveStockHardware(hardwareCondition);
        String trimmedReason = reason.trim();
        Map<String, BigDecimal> normalizedTargetSizes = normalizeRealSizesMap(targetSizes);

        if (CinchoProductUtils.isFossCinchoProduct(product)) {
            if (normalizedTargetSizes == null) {
                throw new BusinessException(
                        "Los cinchos FOSS requieren desglose por talla: envíe realSizes con la cantidad por talla.");
            }
            int targetTotal = ProductInventorySizesJson.sum(normalizedTargetSizes)
                    .setScale(0, RoundingMode.HALF_UP).intValue();
            if (targetQuantity != targetTotal) {
                throw new BusinessException(
                        "realQuantity debe coincidir con la suma de realSizes (" + targetTotal + ").");
            }

            KioscoStockEntity stock = getOrCreateLockedStock(
                    locationId, productId, colorId, resolvedUserId, hardware);
            syncFossCurrentStockFromSizes(stock);
            stock = kioscoStockRepository.findForUpdateByHardware(locationId, productId, colorId, hardware)
                    .orElse(stock);

            Map<String, BigDecimal> currentSizes = ProductInventorySizesJson.parse(stock.getSizesData());
            Set<String> allKeys = new LinkedHashSet<>();
            allKeys.addAll(currentSizes.keySet());
            allKeys.addAll(normalizedTargetSizes.keySet());

            Map<String, Integer> deltasBySize = new LinkedHashMap<>();
            for (String sizeKey : allKeys) {
                int current = currentSizes.getOrDefault(sizeKey, BigDecimal.ZERO)
                        .setScale(0, RoundingMode.HALF_UP).intValue();
                int target = normalizedTargetSizes.getOrDefault(sizeKey, BigDecimal.ZERO)
                        .setScale(0, RoundingMode.HALF_UP).intValue();
                int delta = target - current;
                if (delta != 0) {
                    deltasBySize.put(sizeKey, delta);
                }
            }

            KioscoStockResponse last = toStockResponse(stock);
            boolean decreased = false;
            for (Map.Entry<String, Integer> entry : deltasBySize.entrySet()) {
                int delta = entry.getValue();
                if (delta <= 0) {
                    continue;
                }
                last = applyStockMovement(
                        locationId, productId, colorId, delta, null, null, null,
                        resolvedUserId, KioscoMovementType.ENTRADA, delta, true,
                        trimmedReason, entry.getKey(), true, null, null, hardware);
            }
            for (Map.Entry<String, Integer> entry : deltasBySize.entrySet()) {
                int delta = entry.getValue();
                if (delta >= 0) {
                    continue;
                }
                int qty = -delta;
                last = applyStockMovement(
                        locationId, productId, colorId, qty, null, null, null,
                        resolvedUserId, KioscoMovementType.MERMA, -qty, true,
                        trimmedReason, entry.getKey(), true, null, null, hardware);
                decreased = true;
            }

            syncLegacyInventoryToTargetSizes(locationId, productId, colorId, normalizedTargetSizes);
            if (decreased) {
                verificarStockMinimo(locationId, productId, colorId);
            }
            return last;
        }

        KioscoStockEntity stock = getOrCreateLockedStock(
                locationId, productId, colorId, resolvedUserId, hardware);
        int before = safeInt(stock.getCurrentStock());
        int delta = targetQuantity - before;
        if (delta == 0) {
            return toStockResponse(stock);
        }
        if (delta > 0) {
            return applyStockMovement(
                    locationId, productId, colorId, delta, null, null, null,
                    resolvedUserId, KioscoMovementType.ENTRADA, delta, true,
                    trimmedReason, null, true, null, null, hardware);
        }
        int qty = -delta;
        KioscoStockResponse response = applyStockMovement(
                locationId, productId, colorId, qty, null, null, null,
                resolvedUserId, KioscoMovementType.MERMA, -qty, true,
                trimmedReason, null, true, null, null, hardware);
        verificarStockMinimo(locationId, productId, colorId);
        return response;
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
        return anularFactura(invoiceId, locationId, productId, colorId, quantity, reason, productLeftKiosk, userId, null);
    }

    public KioscoStockResponse anularFactura(
            Long invoiceId,
            Long locationId,
            Long productId,
            Long colorId,
            Integer quantity,
            String reason,
            Boolean productLeftKiosk,
            Long userId,
            String sizeKey
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
        if (hasPosReferenceMovementApplied(
                locationId, productId, colorId, invoiceId, KioscoMovementType.ANULACION, sizeKey)) {
            log.info(
                    "KIOSCO_ANULACION_SKIPPED locationId={} productId={} colorId={} invoiceId={} (already applied)",
                    locationId, productId, colorId, invoiceId);
            return resolveStockResponseForNoOp(locationId, productId, colorId, null);
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
                sizeKey,
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
        return registrarEntradaDesdeIntegracion(locationId, productId, colorId, quantity, referenceId, userId, null);
    }

    public KioscoStockResponse registrarEntradaDesdeIntegracion(
            Long locationId,
            Long productId,
            Long colorId,
            BigDecimal quantity,
            Long referenceId,
            Long userId,
            String sizeKey
    ) throws BusinessException, ResourceNotFoundException {
        return registrarEntradaDesdeIntegracion(locationId, productId, colorId, quantity, referenceId, userId, sizeKey, null);
    }

    public KioscoStockResponse registrarEntradaDesdeIntegracion(
            Long locationId,
            Long productId,
            Long colorId,
            BigDecimal quantity,
            Long referenceId,
            Long userId,
            String sizeKey,
            String receiptLineRef
    ) throws BusinessException, ResourceNotFoundException {
        return registrarEntradaDesdeIntegracion(
                locationId, productId, colorId, quantity, referenceId, userId, sizeKey, receiptLineRef, null);
    }

    public KioscoStockResponse registrarEntradaDesdeIntegracion(
            Long locationId,
            Long productId,
            Long colorId,
            BigDecimal quantity,
            Long referenceId,
            Long userId,
            String sizeKey,
            String receiptLineRef,
            String hardwareCondition
    ) throws BusinessException, ResourceNotFoundException {
        int qty = normalizePositiveIntegerQuantity(quantity);
        Long originLocationId = null;
        Long destinationLocationId = locationId;
        String reason = receiptLineRef != null && !receiptLineRef.isBlank()
                ? shipmentReceiptLineReason(receiptLineRef)
                : null;

        if (referenceId != null) {
            Optional<ProductShipmentEntity> shipmentOpt = productShipmentRepository.findById(referenceId);
            if (shipmentOpt.isPresent()) {
                ProductShipmentEntity shipment = shipmentOpt.get();
                if (shipment.getLocationId() != null) {
                    destinationLocationId = shipment.getLocationId();
                }
                originLocationId = resolveShipmentOriginLocationId(shipment, productId, colorId);
                reason = buildShipmentEntradaReason(shipment, receiptLineRef);
            }
        }

        if (referenceId != null && receiptLineRef != null && !receiptLineRef.isBlank()
                && hasShipmentReceiptLineApplied(destinationLocationId, referenceId, receiptLineRef)) {
            log.info(
                    "KIOSCO_ENTRADA_SHIPMENT_LINE_SKIPPED locationId={} shipmentId={} lineRef={} (already applied)",
                    destinationLocationId, referenceId, receiptLineRef.trim());
            return resolveStockResponseForNoOp(destinationLocationId, productId, colorId, hardwareCondition);
        }

        KioscoStockResponse response = applyStockMovement(
                locationId,
                productId,
                colorId,
                qty,
                referenceId,
                originLocationId,
                destinationLocationId,
                resolveUserIdRequired(userId),
                KioscoMovementType.ENTRADA,
                qty,
                true,
                reason,
                sizeKey,
                false,
                null,
                null,
                ProductHardwareCondition.normalize(hardwareCondition)
        );
        return response;
    }

    private KioscoStockResponse resolveStockResponseForNoOp(
            Long locationId,
            Long productId,
            Long colorId,
            String hardwareCondition
    ) {
        String hardware = resolveStockHardware(hardwareCondition);
        Optional<KioscoStockEntity> byHardware = kioscoStockRepository
                .findByLocationIdAndProductIdAndColorIdAndHardwareCondition(
                        locationId, productId, colorId, hardware);
        if (byHardware.isPresent()) {
            return toStockResponse(byHardware.get());
        }
        return kioscoStockRepository.findByLocationIdAndProductIdAndColorId(locationId, productId, colorId)
                .map(this::toStockResponse)
                .orElseGet(() -> KioscoStockResponse.builder()
                        .locationId(locationId)
                        .productId(productId)
                        .colorId(colorId)
                        .hardwareCondition(hardware)
                        .currentStock(0)
                        .minimumStock(0)
                        .lowStock(true)
                        .build());
    }

    private void applyHardwareConditionToStock(
            Long locationId,
            Long productId,
            Long colorId,
            String hardwareCondition
    ) {
        // Legacy no-op: el herraje es dimensión de stock, no un flag que se pisa.
    }

    public boolean hasShipmentReceiptLineApplied(Long locationId, Long shipmentId, String lineRef) {
        if (locationId == null || shipmentId == null || lineRef == null || lineRef.isBlank()) {
            return false;
        }
        String trimmed = lineRef.trim();
        String reasonKey = shipmentReceiptLineReason(trimmed);
        return kioscoMovementRepository.existsShipmentReceiptLine(locationId, shipmentId, reasonKey)
                || kioscoMovementRepository.existsShipmentReceiptLine(locationId, shipmentId, trimmed);
    }

    /**
     * Reduce stock por exceso de ENTRADAs de envío (legacy; preferir pruneExcessShipmentEntradas).
     */
    public KioscoStockResponse registrarCompensacionRecepcionEnvio(
            Long locationId,
            Long productId,
            Long colorId,
            int excessQuantity,
            Long shipmentId,
            String lineRef,
            Long userId
    ) throws BusinessException, ResourceNotFoundException {
        if (excessQuantity <= 0) {
            throw new BusinessException("La cantidad a compensar debe ser positiva.");
        }
        String reason = buildShipmentReconcileReason(shipmentId, lineRef);
        return applyStockMovement(
                locationId,
                productId,
                colorId,
                excessQuantity,
                shipmentId,
                null,
                locationId,
                resolveUserIdRequired(userId),
                KioscoMovementType.MERMA,
                -excessQuantity,
                true,
                reason,
                null,
                false
        );
    }

    public KioscoStockResponse registrarVentaDesdeIntegracion(
            Long locationId,
            Long productId,
            Long colorId,
            BigDecimal quantity,
            Long invoiceId,
            Long userId
    ) throws BusinessException, ResourceNotFoundException {
        return registrarVentaDesdeIntegracion(locationId, productId, colorId, quantity, invoiceId, userId, null);
    }

    public KioscoStockResponse registrarVentaDesdeIntegracion(
            Long locationId,
            Long productId,
            Long colorId,
            BigDecimal quantity,
            Long invoiceId,
            Long userId,
            String sizeKey
    ) throws BusinessException, ResourceNotFoundException {
        return registrarVentaDesdeIntegracion(
                locationId, productId, colorId, quantity, invoiceId, userId, sizeKey, null);
    }

    public KioscoStockResponse registrarVentaDesdeIntegracion(
            Long locationId,
            Long productId,
            Long colorId,
            BigDecimal quantity,
            Long invoiceId,
            Long userId,
            String sizeKey,
            String hardwareCondition
    ) throws BusinessException, ResourceNotFoundException {
        int qty = normalizePositiveIntegerQuantity(quantity);
        String hardware = ProductHardwareCondition.normalize(hardwareCondition);
        if (hardware != null) {
            return registrarVentaInternal(
                    locationId, productId, colorId, qty, invoiceId, userId, false, sizeKey, hardware);
        }
        if (shouldSplitVentaByHardware(productId, null)) {
            return registrarVentaFifoByHardware(
                    locationId, productId, colorId, qty, invoiceId, userId, sizeKey, false);
        }
        return registrarVentaInternal(
                locationId, productId, colorId, qty, invoiceId, userId, false, sizeKey,
                ProductHardwareCondition.NUEVO);
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
        return anularFacturaDesdeIntegracion(invoiceId, locationId, productId, colorId, quantity, reason, userId, null);
    }

    public KioscoStockResponse anularFacturaDesdeIntegracion(
            Long invoiceId,
            Long locationId,
            Long productId,
            Long colorId,
            BigDecimal quantity,
            String reason,
            Long userId,
            String sizeKey
    ) throws BusinessException, ResourceNotFoundException {
        int qty = normalizePositiveIntegerQuantity(quantity);
        return anularFactura(invoiceId, locationId, productId, colorId, qty, reason, false, userId, sizeKey);
    }

    @Transactional(readOnly = true)
    public List<KioscoStockResponse> getStockByLocation(Long locationId) throws BusinessException, ResourceNotFoundException {
        validateLocationIsKiosk(locationId);
        return kioscoStockRepository.findByLocationIdOrderByProductIdAscColorIdAscHardwareConditionAsc(locationId).stream()
                .map(this::toStockResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<KioscoStockResponse> getStockReportByLocations(List<Long> locationIds) {
        if (locationIds == null || locationIds.isEmpty()) {
            return List.of();
        }
        Set<Long> kioskIds = locationIds.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (kioskIds.isEmpty()) {
            return List.of();
        }
        Set<Long> validKioskIds = locationRepository.findAllById(kioskIds).stream()
                .filter(kioskInventoryGuard::isKioskLocation)
                .map(LocationEntity::getId)
                .collect(Collectors.toSet());
        if (validKioskIds.isEmpty()) {
            return List.of();
        }
        List<KioscoStockEntity> stocks = kioscoStockRepository.findByLocationIdIn(new ArrayList<>(validKioskIds));
        if (stocks.isEmpty()) {
            return List.of();
        }
        List<Long> stockIds = stocks.stream()
                .map(KioscoStockEntity::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        Set<Long> stockIdsWithMovements = stockIds.isEmpty()
                ? Set.of()
                : new LinkedHashSet<>(kioscoMovementRepository.findDistinctKioscoStockIdsHavingMovements(stockIds));

        return stocks.stream()
                // Incluye stock > 0 y ceros con historial. Excluye fantasmas: stock 0 y nunca movimiento.
                .filter(stock -> hasPositiveStockForReport(stock)
                        || (stock.getId() != null && stockIdsWithMovements.contains(stock.getId())))
                .map(this::toStockResponse)
                .sorted(Comparator.comparing(KioscoStockResponse::getLocationName,
                                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                        .thenComparing(KioscoStockResponse::getProductCode,
                                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                        .thenComparing(KioscoStockResponse::getColorName,
                                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                        .thenComparing(KioscoStockResponse::getHardwareCondition,
                                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .collect(Collectors.toList());
    }

    /**
     * Existencias de un producto en todos los kioskos.
     * ADMIN / LOGISTICA / SUPERVISORA_KIOSKO / VENTA EN LÍNEA.
     */
    @Transactional(readOnly = true)
    public List<KioscoStockResponse> getStockByProductAcrossKiosks(Long productId, Long colorId)
            throws BusinessException, ResourceNotFoundException {
        if (productId == null) {
            throw new BusinessException("Debes indicar el producto.");
        }
        UserEntity user = securityUtil.getCurrentUser()
                .orElseThrow(() -> new BusinessException("No se pudo determinar el usuario autenticado."));
        if (!KioskAccessHelper.hasAllKiosksAccess(user)) {
            throw new BusinessException(
                    "Solo administración, logística y venta en línea pueden consultar existencias en todos los kioskos.");
        }
        productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));

        List<KioscoStockEntity> stocks = colorId != null
                ? kioscoStockRepository.findByProductIdAndColorId(productId, colorId)
                : kioscoStockRepository.findByProductId(productId);
        if (stocks.isEmpty()) {
            return List.of();
        }
        Set<Long> kioskLocationIds = locationRepository.findAllById(
                        stocks.stream().map(KioscoStockEntity::getLocationId).filter(Objects::nonNull).distinct().toList()
                ).stream()
                .filter(kioskInventoryGuard::isKioskLocation)
                .map(LocationEntity::getId)
                .collect(Collectors.toSet());
        List<KioscoStockEntity> kioskStocks = stocks.stream()
                .filter(stock -> stock.getLocationId() != null && kioskLocationIds.contains(stock.getLocationId()))
                .collect(Collectors.toList());
        if (kioskStocks.isEmpty()) {
            return List.of();
        }
        List<Long> stockIds = kioskStocks.stream()
                .map(KioscoStockEntity::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        Set<Long> stockIdsWithMovements = stockIds.isEmpty()
                ? Set.of()
                : new LinkedHashSet<>(kioscoMovementRepository.findDistinctKioscoStockIdsHavingMovements(stockIds));
        return kioskStocks.stream()
                .filter(stock -> hasPositiveStockForReport(stock)
                        || (stock.getId() != null && stockIdsWithMovements.contains(stock.getId())))
                .map(this::toStockResponse)
                .sorted(Comparator.comparing(KioscoStockResponse::getLocationName,
                                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                        .thenComparing(KioscoStockResponse::getColorName,
                                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                        .thenComparing(KioscoStockResponse::getHardwareCondition,
                                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .collect(Collectors.toList());
    }

    /** true si current_stock &gt; 0 o la suma de sizes_data &gt; 0. */
    private boolean hasPositiveStockForReport(KioscoStockEntity stock) {
        if (stock == null) {
            return false;
        }
        if (safeInt(stock.getCurrentStock()) > 0) {
            return true;
        }
        return ProductInventorySizesJson.sum(ProductInventorySizesJson.parse(stock.getSizesData()))
                .compareTo(BigDecimal.ZERO) > 0;
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
    public List<KioscoMovementResponse> getKardexMovements(
            Long locationId,
            Long productId,
            Long colorId,
            LocalDate from,
            LocalDate to
    ) throws BusinessException, ResourceNotFoundException {
        if (from == null || to == null) {
            throw new BusinessException("Debes indicar el rango de fechas (from y to).");
        }
        if (from.isAfter(to)) {
            throw new BusinessException("La fecha inicial no puede ser posterior a la fecha final.");
        }
        validateLocationIsKiosk(locationId);
        LocalDateTime fromDt = from.atStartOfDay();
        LocalDateTime toDtExclusive = to.plusDays(1).atStartOfDay();
        return kioscoMovementRepository
                .findByLocationAndFiltersAndCreatedAtBetween(locationId, productId, colorId, fromDt, toDtExclusive)
                .stream()
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
            List<KioscoStockEntity> stocks = kioscoStockRepository
                    .findByLocationIdOrderByProductIdAscColorIdAscHardwareConditionAsc(kiosk.getId());
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
                .generatedAt(GuatemalaDateTime.now())
                .totalKiosks(kiosks.size())
                .totalStockRows(totalRows)
                .totalUnits(totalUnits)
                .totalLowStockRows(totalLowStockRows)
                .kiosks(summaries)
                .build();
    }

    /**
     * Kardex de inventario kiosco por periodo: inventario inicial (corte anterior) + movimientos
     * del rango clasificados en compras/ajustes, anulacion de compras, entradas, ventas,
     * anulacion de venta y salida, hasta llegar al inventario final.
     */
    @Transactional(readOnly = true)
    public KioscoKardexReportResponse getKardexReport(Long locationId, LocalDate from, LocalDate to)
            throws BusinessException, ResourceNotFoundException {
        List<KioscoKardexReportResponse.KioscoKardexRow> rows = buildKardexRows(locationId, from, to, false);
        LocationEntity location = locationRepository.findById(locationId)
                .orElseThrow(() -> new ResourceNotFoundException("Location", locationId));

        KioscoKardexReportResponse.KioscoKardexRow totals = KioscoKardexReportResponse.KioscoKardexRow.builder()
                .inventarioInicial(rows.stream().mapToInt(KioscoKardexReportResponse.KioscoKardexRow::getInventarioInicial).sum())
                .comprasAjustes(rows.stream().mapToInt(KioscoKardexReportResponse.KioscoKardexRow::getComprasAjustes).sum())
                .anulacionCompras(rows.stream().mapToInt(KioscoKardexReportResponse.KioscoKardexRow::getAnulacionCompras).sum())
                .entradas(rows.stream().mapToInt(KioscoKardexReportResponse.KioscoKardexRow::getEntradas).sum())
                .ventas(rows.stream().mapToInt(KioscoKardexReportResponse.KioscoKardexRow::getVentas).sum())
                .anulacionVenta(rows.stream().mapToInt(KioscoKardexReportResponse.KioscoKardexRow::getAnulacionVenta).sum())
                .salida(rows.stream().mapToInt(KioscoKardexReportResponse.KioscoKardexRow::getSalida).sum())
                .inventarioFinal(rows.stream().mapToInt(KioscoKardexReportResponse.KioscoKardexRow::getInventarioFinal).sum())
                .build();

        return KioscoKardexReportResponse.builder()
                .locationId(location.getId())
                .locationCode(location.getCode())
                .locationName(location.getName())
                .from(from)
                .to(to)
                .rows(rows)
                .totals(totals)
                .build();
    }

    /**
     * Calcula las filas de kardex (inventario inicial..final por producto/color) para un kiosko
     * y periodo. Con {@code includeZeroRows=true} se conservan filas sin movimiento ni saldo,
     * necesario para auditorias de conteo fisico que deben cubrir todo el inventario del kiosko.
     */
    @Transactional(readOnly = true)
    List<KioscoKardexReportResponse.KioscoKardexRow> buildKardexRows(
            Long locationId, LocalDate from, LocalDate to, boolean includeZeroRows
    ) throws BusinessException, ResourceNotFoundException {
        return buildKardexRows(locationId, from, to, includeZeroRows, null);
    }

    /**
     * Filas de kardex por producto/color. Con {@code balanceAsOf}, el inventario final es el saldo
     * replay de movimientos hasta esa fecha (no el stock vivo).
     */
    @Transactional(readOnly = true)
    List<KioscoKardexReportResponse.KioscoKardexRow> buildKardexRows(
            Long locationId, LocalDate from, LocalDate to, boolean includeZeroRows, LocalDate balanceAsOf
    ) throws BusinessException, ResourceNotFoundException {
        return buildKardexRows(locationId, from, to, includeZeroRows, balanceAsOf, null);
    }

    @Transactional(readOnly = true)
    List<KioscoKardexReportResponse.KioscoKardexRow> buildKardexRows(
            Long locationId, LocalDate from, LocalDate to, boolean includeZeroRows,
            LocalDate balanceAsOf, Long physicalCountId
    ) throws BusinessException, ResourceNotFoundException {
        if (from == null || to == null) {
            throw new BusinessException("Debes indicar el rango de fechas (from y to).");
        }
        if (from.isAfter(to)) {
            throw new BusinessException("La fecha inicial no puede ser posterior a la fecha final.");
        }
        LocalDateTime fromDt = from.atStartOfDay();
        LocalDateTime toDtExclusive = to.plusDays(1).atStartOfDay();
        LocalDateTime endCutoffExclusive = balanceAsOf != null
                ? balanceAsOf.plusDays(1).atStartOfDay()
                : toDtExclusive;
        return buildKardexRows(locationId, fromDt, toDtExclusive, includeZeroRows, endCutoffExclusive, physicalCountId);
    }

    /**
     * Kardex con límites en {@link LocalDateTime} (wall-clock Guatemala).
     * Intervalo de movimientos: [{@code fromInclusive}, {@code toExclusive}).
     * Inventario final: saldo hasta {@code balanceCutoffExclusive} (exclusivo).
     */
    @Transactional(readOnly = true)
    List<KioscoKardexReportResponse.KioscoKardexRow> buildKardexRows(
            Long locationId,
            LocalDateTime fromInclusive,
            LocalDateTime toExclusive,
            boolean includeZeroRows,
            LocalDateTime balanceCutoffExclusive,
            Long physicalCountId
    ) throws BusinessException, ResourceNotFoundException {
        if (fromInclusive == null || toExclusive == null) {
            throw new BusinessException("Debes indicar el rango de fechas (from y to).");
        }
        if (fromInclusive.isAfter(toExclusive)) {
            throw new BusinessException("La fecha/hora inicial no puede ser posterior a la final.");
        }
        validateLocationIsKiosk(locationId);

        LocalDateTime endCutoffExclusive = balanceCutoffExclusive != null ? balanceCutoffExclusive : toExclusive;
        Map<Long, Integer> initialBalanceByStockId = computeBalanceByStockId(locationId, fromInclusive);
        Map<Long, Integer> endBalanceByStockId = computeBalanceByStockId(locationId, endCutoffExclusive);

        Map<Long, KardexAccumulator> accByStockId = new LinkedHashMap<>();
        for (KioscoMovementEntity m : collectPeriodMovements(locationId, fromInclusive, toExclusive, physicalCountId)) {
            if (m.getKioscoStockId() == null || !Boolean.TRUE.equals(m.getAffectsStock())) {
                continue;
            }
            int delta = movementSignedDelta(m);
            if (delta == 0) {
                continue;
            }
            accByStockId.computeIfAbsent(m.getKioscoStockId(), k -> new KardexAccumulator())
                    .apply(m.getMovementType(), delta);
        }

        List<KioscoKardexReportResponse.KioscoKardexRow> rows = new ArrayList<>();
        for (KioscoStockEntity stock : kioscoStockRepository
                .findByLocationIdOrderByProductIdAscColorIdAscHardwareConditionAsc(locationId)) {
            int initial = initialBalanceByStockId.getOrDefault(stock.getId(), 0);
            KardexAccumulator acc = accByStockId.getOrDefault(stock.getId(), new KardexAccumulator());
            ProductEntity product = stock.getProduct();
            syncFossCurrentStockFromSizes(stock);
            int finalBalance = endBalanceByStockId.getOrDefault(stock.getId(), 0);
            if (!includeZeroRows && initial == 0 && finalBalance == 0 && acc.isEmpty()) {
                continue;
            }
            ColorEntity color = stock.getColor();
            rows.add(KioscoKardexReportResponse.KioscoKardexRow.builder()
                    .productId(stock.getProductId())
                    .productCode(product != null ? product.getCode() : null)
                    .productName(product != null ? product.getName() : null)
                    .colorId(stock.getColorId())
                    .colorName(color != null ? color.getName() : null)
                    .audienceCategory(product != null
                            ? ProductAudienceCategory.normalizeProductAudience(product.getAudienceCategory())
                            : ProductAudienceCategory.UNISEX)
                    .cinchoType(product != null
                            ? ProductCinchoType.normalizeCinchoType(product.getCinchoType())
                            : null)
                    .inventarioInicial(initial)
                    .comprasAjustes(acc.comprasAjustes)
                    .anulacionCompras(acc.anulacionCompras)
                    .entradas(acc.entradas)
                    .ventas(acc.ventas)
                    .anulacionVenta(acc.anulacionVenta)
                    .salida(acc.salida)
                    .salidaDevolucion(acc.salidaDevolucion)
                    .inventarioFinal(finalBalance)
                    .hardwareCondition(stock.getHardwareCondition())
                    .build());
        }

        rows.sort(Comparator
                .comparing((KioscoKardexReportResponse.KioscoKardexRow r) -> String.valueOf(r.getProductCode()),
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                .thenComparing(r -> String.valueOf(r.getColorName()), Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        return rows;
    }

    /**
     * Totales de kardex del periodo por {@code kiosco_stock_id} y talla ({@code size_key}).
     * La clave de talla vacía ({@code ""}) agrupa movimientos históricos sin talla.
     */
    @Transactional(readOnly = true)
    public Map<Long, Map<String, SizeKardexBucket>> buildKardexByStockAndSize(
            Long locationId, LocalDate from, LocalDate to
    ) throws BusinessException, ResourceNotFoundException {
        return buildKardexByStockAndSize(locationId, from, to, null);
    }

    @Transactional(readOnly = true)
    public Map<Long, Map<String, SizeKardexBucket>> buildKardexByStockAndSize(
            Long locationId, LocalDate from, LocalDate to, Long physicalCountId
    ) throws BusinessException, ResourceNotFoundException {
        if (from == null || to == null) {
            throw new BusinessException("Debes indicar el rango de fechas (from y to).");
        }
        if (from.isAfter(to)) {
            throw new BusinessException("La fecha inicial no puede ser posterior a la fecha final.");
        }
        return buildKardexByStockAndSize(
                locationId,
                from.atStartOfDay(),
                to.plusDays(1).atStartOfDay(),
                physicalCountId
        );
    }

    @Transactional(readOnly = true)
    public Map<Long, Map<String, SizeKardexBucket>> buildKardexByStockAndSize(
            Long locationId,
            LocalDateTime fromInclusive,
            LocalDateTime toExclusive,
            Long physicalCountId
    ) throws BusinessException, ResourceNotFoundException {
        if (fromInclusive == null || toExclusive == null) {
            throw new BusinessException("Debes indicar el rango de fechas (from y to).");
        }
        if (fromInclusive.isAfter(toExclusive)) {
            throw new BusinessException("La fecha/hora inicial no puede ser posterior a la final.");
        }
        validateLocationIsKiosk(locationId);

        Map<Long, Map<String, KardexAccumulator>> accByStockAndSize = new LinkedHashMap<>();
        // stockId -> shipmentIds relacionados a ENTRADAs (con o sin talla) para desglosar desde el envío.
        Map<Long, Set<Long>> shipmentIdsByStock = new LinkedHashMap<>();
        Map<String, Long> shipmentIdByNumberCache = new HashMap<>();
        for (KioscoMovementEntity m : collectPeriodMovements(locationId, fromInclusive, toExclusive, physicalCountId)) {
            if (m.getKioscoStockId() == null || !Boolean.TRUE.equals(m.getAffectsStock())) {
                continue;
            }
            int delta = movementSignedDelta(m);
            if (delta == 0) {
                continue;
            }
            String sizeKey = ProductInventorySizesJson.normalizeKey(m.getSizeKey());
            accByStockAndSize
                    .computeIfAbsent(m.getKioscoStockId(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(sizeKey, k -> new KardexAccumulator())
                    .apply(m.getMovementType(), delta);

            // Cualquier ENTRADA ligada a envío: el reporte puede usar product_shipment_detail.
            if (m.getMovementType() == KioscoMovementType.ENTRADA && delta > 0) {
                Long shipmentId = resolveShipmentIdForEntradaMovement(m, shipmentIdByNumberCache);
                if (shipmentId != null) {
                    shipmentIdsByStock
                            .computeIfAbsent(m.getKioscoStockId(), k -> new LinkedHashSet<>())
                            .add(shipmentId);
                }
            }
        }

        applyEntradasFromRelatedShipmentDetails(accByStockAndSize, shipmentIdsByStock);

        Map<Long, Map<String, SizeKardexBucket>> out = new LinkedHashMap<>();
        for (Map.Entry<Long, Map<String, KardexAccumulator>> stockEntry : accByStockAndSize.entrySet()) {
            Map<String, SizeKardexBucket> bySize = new LinkedHashMap<>();
            for (Map.Entry<String, KardexAccumulator> sizeEntry : stockEntry.getValue().entrySet()) {
                bySize.put(sizeEntry.getKey(), SizeKardexBucket.from(sizeEntry.getValue()));
            }
            out.put(stockEntry.getKey(), bySize);
        }
        return out;
    }

    /**
     * Movimientos del periodo [{@code fromDt}, {@code toDtExclusive}).
     * Si hay {@code physicalCountId}, también incluye movimientos ligados a ese conteo,
     * pero solo si su {@code createdAt} cae en el mismo rango (evita que una Dev. bodega
     * del día siguiente al corte aparezca en el conteo anterior).
     */
    private List<KioscoMovementEntity> collectPeriodMovements(
            Long locationId,
            LocalDateTime fromDt,
            LocalDateTime toDtExclusive,
            Long physicalCountId
    ) {
        Map<Long, KioscoMovementEntity> merged = new LinkedHashMap<>();
        for (KioscoMovementEntity movement : kioscoMovementRepository.findByLocationAndCreatedAtBetween(
                locationId, fromDt, toDtExclusive)) {
            if (movement.getId() != null) {
                merged.put(movement.getId(), movement);
            }
        }
        if (physicalCountId != null) {
            for (KioscoMovementEntity movement : kioscoMovementRepository.findByLocationAndPhysicalCountId(
                    locationId, physicalCountId)) {
                if (movement.getId() == null || !isMovementCreatedInRange(movement, fromDt, toDtExclusive)) {
                    continue;
                }
                merged.putIfAbsent(movement.getId(), movement);
            }
        }
        return merged.values().stream()
                .sorted(Comparator
                        .comparing(KioscoMovementEntity::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(KioscoMovementEntity::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private static boolean isMovementCreatedInRange(
            KioscoMovementEntity movement,
            LocalDateTime fromDt,
            LocalDateTime toDtExclusive
    ) {
        LocalDateTime createdAt = movement.getCreatedAt();
        if (createdAt == null || toDtExclusive == null) {
            return false;
        }
        if (fromDt != null && createdAt.isBefore(fromDt)) {
            return false;
        }
        return createdAt.isBefore(toDtExclusive);
    }

    /**
     * Solo cinchos: si la ENTRADA del envío quedó sin size_key (agregada), desglosa Ent. por talla
     * desde product_shipment_detail. No toca otros productos: ahí Entradas salen solo de movimientos.
     */
    private void applyEntradasFromRelatedShipmentDetails(
            Map<Long, Map<String, KardexAccumulator>> accByStockAndSize,
            Map<Long, Set<Long>> shipmentIdsByStock
    ) {
        if (shipmentIdsByStock == null || shipmentIdsByStock.isEmpty()) {
            return;
        }

        Set<Long> allShipmentIds = shipmentIdsByStock.values().stream()
                .flatMap(Set::stream)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (allShipmentIds.isEmpty()) {
            return;
        }

        List<ProductShipmentDetailEntity> details = productShipmentDetailRepository.findByShipmentIdIn(allShipmentIds);
        if (details == null || details.isEmpty()) {
            return;
        }

        Map<Long, List<ProductShipmentDetailEntity>> detailsByShipment = details.stream()
                .collect(Collectors.groupingBy(ProductShipmentDetailEntity::getShipmentId, LinkedHashMap::new, Collectors.toList()));

        Map<Long, KioscoStockEntity> stocksById = kioscoStockRepository.findAllById(shipmentIdsByStock.keySet())
                .stream()
                .collect(Collectors.toMap(KioscoStockEntity::getId, s -> s, (a, b) -> a, LinkedHashMap::new));

        Set<Long> productIds = stocksById.values().stream()
                .map(KioscoStockEntity::getProductId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, ProductEntity> productsById = productIds.isEmpty()
                ? Map.of()
                : productRepository.findAllById(productIds).stream()
                        .collect(Collectors.toMap(ProductEntity::getId, p -> p, (a, b) -> a));

        for (Map.Entry<Long, Set<Long>> stockEntry : shipmentIdsByStock.entrySet()) {
            Long stockId = stockEntry.getKey();
            KioscoStockEntity stock = stocksById.get(stockId);
            Map<String, KardexAccumulator> bySize = accByStockAndSize.get(stockId);
            if (stock == null || bySize == null) {
                continue;
            }

            ProductEntity product = stock.getProduct() != null
                    ? stock.getProduct()
                    : productsById.get(stock.getProductId());
            // Solo el caso cinchos sin ENTRADA por talla; no redistribuir el resto del catálogo.
            if (!isCinchoNeedingShipmentEntradaBySize(product)) {
                continue;
            }

            KardexAccumulator unallocated = bySize.get("");
            int unsizedEntradas = unallocated != null ? unallocated.entradas : 0;
            int sizedEntradasBefore = bySize.entrySet().stream()
                    .filter(e -> e.getKey() != null && !e.getKey().isBlank())
                    .mapToInt(e -> e.getValue().entradas)
                    .sum();
            // Si ya hay Ent. con size_key, respetar movimientos; no inventar desde el envío.
            if (unsizedEntradas <= 0 || sizedEntradasBefore > 0) {
                continue;
            }

            Map<String, Integer> entradasBySizeFromShipment = new LinkedHashMap<>();
            for (Long shipmentId : stockEntry.getValue()) {
                for (ProductShipmentDetailEntity detail : detailsByShipment.getOrDefault(shipmentId, List.of())) {
                    if (detail == null || detail.getProductId() == null) {
                        continue;
                    }
                    if (!Objects.equals(detail.getProductId(), stock.getProductId())) {
                        continue;
                    }
                    if (!Objects.equals(detail.getColorId(), stock.getColorId())) {
                        continue;
                    }
                    String size = ProductInventorySizesJson.normalizeKey(detail.getSizeLabel());
                    if (size.isEmpty()) {
                        continue;
                    }
                    int qty = resolveShipmentDetailReceivedQty(detail);
                    if (qty <= 0) {
                        continue;
                    }
                    entradasBySizeFromShipment.merge(size, qty, Integer::sum);
                }
            }

            if (entradasBySizeFromShipment.isEmpty()) {
                continue;
            }

            int shipmentTotal = 0;
            for (Map.Entry<String, Integer> e : entradasBySizeFromShipment.entrySet()) {
                int qty = e.getValue();
                if (qty <= 0) {
                    continue;
                }
                KardexAccumulator sizeAcc = bySize.computeIfAbsent(e.getKey(), k -> new KardexAccumulator());
                sizeAcc.entradas += qty;
                shipmentTotal += qty;
            }

            unallocated.entradas = Math.max(0, unsizedEntradas - shipmentTotal);
            if (unallocated.isEmpty()) {
                bySize.remove("");
            }
        }
    }

    /** Cinchos FOSS/mesa: suelen tener ENTRADA agregada y tallas solo en el detalle del envío. */
    private boolean isCinchoNeedingShipmentEntradaBySize(ProductEntity product) {
        if (product == null) {
            return false;
        }
        if (CinchoProductUtils.isFossCinchoProduct(product) || CinchoProductUtils.isMesaCinchosProduct(product)) {
            return true;
        }
        return ProductCinchoType.normalizeCinchoType(product.getCinchoType()) != null;
    }

    private Long resolveShipmentIdForEntradaMovement(
            KioscoMovementEntity movement,
            Map<String, Long> shipmentIdByNumberCache
    ) {
        if (movement == null) {
            return null;
        }
        if (movement.getReferenceId() != null) {
            return movement.getReferenceId();
        }
        if (!isShipmentReceiptReason(movement.getReason())) {
            return null;
        }
        String shipmentNumber = extractShipmentNumberFromReason(movement.getReason());
        if (shipmentNumber == null || shipmentNumber.isBlank()) {
            return null;
        }
        String cacheKey = shipmentNumber.trim().toUpperCase(Locale.ROOT);
        if (shipmentIdByNumberCache.containsKey(cacheKey)) {
            return shipmentIdByNumberCache.get(cacheKey);
        }
        Long id = productShipmentRepository.findByShipmentNumber(shipmentNumber.trim())
                .map(ProductShipmentEntity::getId)
                .orElse(null);
        shipmentIdByNumberCache.put(cacheKey, id);
        return id;
    }

    private static int resolveShipmentDetailReceivedQty(ProductShipmentDetailEntity detail) {
        BigDecimal qty = detail.getQuantityReceived() != null
                ? detail.getQuantityReceived()
                : detail.getQuantity();
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }
        return qty.setScale(0, RoundingMode.HALF_UP).intValue();
    }

    /** Totales de kardex por talla (columnas del conteo/kardex). */
    public static final class SizeKardexBucket {
        public final int comprasAjustes;
        public final int anulacionCompras;
        public final int entradas;
        public final int ventas;
        public final int anulacionVenta;
        public final int salida;
        /** Solo devoluciones a bodega / reintegros (para cuadrar conteo si aún están en piso). */
        public final int salidaDevolucion;

        private SizeKardexBucket(
                int comprasAjustes, int anulacionCompras, int entradas,
                int ventas, int anulacionVenta, int salida, int salidaDevolucion
        ) {
            this.comprasAjustes = comprasAjustes;
            this.anulacionCompras = anulacionCompras;
            this.entradas = entradas;
            this.ventas = ventas;
            this.anulacionVenta = anulacionVenta;
            this.salida = salida;
            this.salidaDevolucion = salidaDevolucion;
        }

        public static SizeKardexBucket of(
                int comprasAjustes, int anulacionCompras, int entradas,
                int ventas, int anulacionVenta, int salida
        ) {
            return of(comprasAjustes, anulacionCompras, entradas, ventas, anulacionVenta, salida, 0);
        }

        public static SizeKardexBucket of(
                int comprasAjustes, int anulacionCompras, int entradas,
                int ventas, int anulacionVenta, int salida, int salidaDevolucion
        ) {
            return new SizeKardexBucket(
                    comprasAjustes, anulacionCompras, entradas, ventas, anulacionVenta, salida, salidaDevolucion);
        }

        static SizeKardexBucket from(KardexAccumulator acc) {
            return new SizeKardexBucket(
                    acc.comprasAjustes, acc.anulacionCompras, acc.entradas,
                    acc.ventas, acc.anulacionVenta, acc.salida, acc.salidaDevolucion);
        }

        public static SizeKardexBucket empty() {
            return new SizeKardexBucket(0, 0, 0, 0, 0, 0, 0);
        }

        public boolean isEmpty() {
            return comprasAjustes == 0 && anulacionCompras == 0 && entradas == 0
                    && ventas == 0 && anulacionVenta == 0 && salida == 0 && salidaDevolucion == 0;
        }

        /** Neto del periodo (suma algebraica de columnas kardex). */
        public int netDelta() {
            return comprasAjustes - anulacionCompras + entradas - ventas + anulacionVenta - salida;
        }

        public SizeKardexBucket plus(int comprasDelta, int anulacionComprasDelta, int entradasDelta,
                int ventasDelta, int anulacionVentaDelta, int salidaDelta) {
            return plus(comprasDelta, anulacionComprasDelta, entradasDelta, ventasDelta, anulacionVentaDelta,
                    salidaDelta, 0);
        }

        public SizeKardexBucket plus(int comprasDelta, int anulacionComprasDelta, int entradasDelta,
                int ventasDelta, int anulacionVentaDelta, int salidaDelta, int salidaDevolucionDelta) {
            return new SizeKardexBucket(
                    comprasAjustes + comprasDelta,
                    anulacionCompras + anulacionComprasDelta,
                    entradas + entradasDelta,
                    ventas + ventasDelta,
                    anulacionVenta + anulacionVentaDelta,
                    salida + salidaDelta,
                    salidaDevolucion + salidaDevolucionDelta
            );
        }
    }

    private Map<Long, Integer> computeBalanceByStockId(Long locationId, LocalDateTime cutoffExclusive) {
        Map<Long, Integer> balanceByStockId = new LinkedHashMap<>();
        for (KioscoMovementEntity m : kioscoMovementRepository.findByLocationAndCreatedAtBeforeAsc(
                locationId, cutoffExclusive)) {
            if (m.getKioscoStockId() == null || !Boolean.TRUE.equals(m.getAffectsStock())) {
                continue;
            }
            int running = balanceByStockId.getOrDefault(m.getKioscoStockId(), 0);
            running += movementSignedDelta(m);
            if (running < 0) {
                running = 0;
            }
            balanceByStockId.put(m.getKioscoStockId(), running);
        }
        return balanceByStockId;
    }

    /**
     * Saldo replay por {@code kiosco_stock_id} y talla ({@code size_key}) antes de {@code cutoffExclusive}.
     * Movimientos sin talla se imputan contra tallas con saldo positivo (FIFO) para que Ini. por talla
     * no quede huérfano cuando una venta histórica no trajo {@code size_key}.
     */
    @Transactional(readOnly = true)
    public Map<Long, Map<String, Integer>> computeSizeBalanceByStockAndSize(
            Long locationId,
            LocalDateTime cutoffExclusive
    ) throws BusinessException, ResourceNotFoundException {
        validateLocationIsKiosk(locationId);
        Map<Long, Map<String, Integer>> balanceByStockAndSize = new LinkedHashMap<>();
        for (KioscoMovementEntity m : kioscoMovementRepository.findByLocationAndCreatedAtBeforeAsc(
                locationId, cutoffExclusive)) {
            if (m.getKioscoStockId() == null || !Boolean.TRUE.equals(m.getAffectsStock())) {
                continue;
            }
            int delta = movementSignedDelta(m);
            if (delta == 0) {
                continue;
            }
            String sizeKey = ProductInventorySizesJson.normalizeKey(m.getSizeKey());
            Map<String, Integer> bySize = balanceByStockAndSize.computeIfAbsent(
                    m.getKioscoStockId(), k -> new LinkedHashMap<>());
            if (sizeKey.isEmpty()) {
                applyUnsizedDeltaToSizeBalances(bySize, delta);
            } else {
                int running = bySize.getOrDefault(sizeKey, 0) + delta;
                bySize.put(sizeKey, Math.max(0, running));
            }
        }
        return balanceByStockAndSize;
    }

    /**
     * Imputa un delta sin talla: positivos van a la clave vacía; negativos descuentan FIFO de tallas
     * con saldo &gt; 0 (y el remanente a la clave vacía, floored en 0).
     */
    static void applyUnsizedDeltaToSizeBalances(Map<String, Integer> bySize, int delta) {
        if (bySize == null || delta == 0) {
            return;
        }
        if (delta > 0) {
            bySize.merge("", delta, Integer::sum);
            return;
        }
        int remaining = -delta;
        List<String> sizedKeys = bySize.keySet().stream()
                .filter(k -> k != null && !k.isBlank())
                .sorted()
                .collect(Collectors.toList());
        for (String key : sizedKeys) {
            if (remaining <= 0) {
                break;
            }
            int available = Math.max(0, bySize.getOrDefault(key, 0));
            if (available <= 0) {
                continue;
            }
            int take = Math.min(available, remaining);
            bySize.put(key, available - take);
            remaining -= take;
        }
        if (remaining > 0) {
            int unsized = Math.max(0, bySize.getOrDefault("", 0) - remaining);
            bySize.put("", unsized);
        }
    }

    /** Saldo agregado por stock al cierre de un corte (movimientos antes de {@code cutoffExclusive}). */
    @Transactional(readOnly = true)
    public Map<Long, Integer> computeStockBalanceByStockId(
            Long locationId,
            LocalDateTime cutoffExclusive
    ) throws BusinessException, ResourceNotFoundException {
        validateLocationIsKiosk(locationId);
        return computeBalanceByStockId(locationId, cutoffExclusive);
    }

    /**
     * Fecha del movimiento más antiguo del kiosko. Usado en el primer conteo físico para
     * ampliar el kardex desde el origen (Ini.=0; historial en Ent./Vtas./etc.).
     */
    @Transactional(readOnly = true)
    public Optional<LocalDate> findEarliestMovementDate(Long locationId)
            throws BusinessException, ResourceNotFoundException {
        validateLocationIsKiosk(locationId);
        return kioscoMovementRepository.findEarliestCreatedAtByLocationId(locationId)
                .map(LocalDateTime::toLocalDate);
    }

    /**
     * ENTRADAs en el hueco entre el cierre del conteo físico anterior y el inicio del periodo actual.
     * En conteo físico se suman a Ent. sin restar del Ini. (Ini. = cierre del conteo anterior).
     */
    @Transactional(readOnly = true)
    public Map<Long, Integer> computePrePeriodEntradasByStockId(
            Long locationId,
            LocalDateTime fromInclusive,
            LocalDateTime toExclusive
    ) throws BusinessException, ResourceNotFoundException {
        validateLocationIsKiosk(locationId);
        if (toExclusive == null) {
            return Map.of();
        }
        Map<Long, Integer> entradasByStockId = new LinkedHashMap<>();
        for (KioscoMovementEntity movement : loadPrePeriodEntradaMovements(locationId, fromInclusive, toExclusive)) {
            accumulatePrePeriodEntrada(entradasByStockId, null, movement);
        }
        return entradasByStockId;
    }

    /** ENTRADAs pre-periodo desglosadas por talla ({@code size_key} vacío = sin talla). */
    @Transactional(readOnly = true)
    public Map<Long, Map<String, Integer>> computePrePeriodEntradasByStockAndSize(
            Long locationId,
            LocalDateTime fromInclusive,
            LocalDateTime toExclusive
    ) throws BusinessException, ResourceNotFoundException {
        validateLocationIsKiosk(locationId);
        if (toExclusive == null) {
            return Map.of();
        }
        Map<Long, Map<String, Integer>> entradasByStockAndSize = new LinkedHashMap<>();
        for (KioscoMovementEntity movement : loadPrePeriodEntradaMovements(locationId, fromInclusive, toExclusive)) {
            accumulatePrePeriodEntrada(null, entradasByStockAndSize, movement);
        }
        return entradasByStockAndSize;
    }

    private List<KioscoMovementEntity> loadPrePeriodEntradaMovements(
            Long locationId,
            LocalDateTime fromInclusive,
            LocalDateTime toExclusive
    ) {
        if (fromInclusive == null) {
            return kioscoMovementRepository.findByLocationAndCreatedAtBeforeAsc(locationId, toExclusive);
        }
        if (!fromInclusive.isBefore(toExclusive)) {
            return List.of();
        }
        return kioscoMovementRepository.findByLocationAndCreatedAtBetween(locationId, fromInclusive, toExclusive);
    }

    private void accumulatePrePeriodEntrada(
            Map<Long, Integer> entradasByStockId,
            Map<Long, Map<String, Integer>> entradasByStockAndSize,
            KioscoMovementEntity movement
    ) {
        if (movement.getKioscoStockId() == null || !Boolean.TRUE.equals(movement.getAffectsStock())) {
            return;
        }
        KioscoMovementType type = movement.getMovementType();
        if (type != KioscoMovementType.ENTRADA && type != KioscoMovementType.TRASLADO_ENTRADA) {
            return;
        }
        int delta = movementSignedDelta(movement);
        if (delta <= 0) {
            return;
        }
        if (entradasByStockId != null) {
            entradasByStockId.merge(movement.getKioscoStockId(), delta, Integer::sum);
        }
        if (entradasByStockAndSize != null) {
            String sizeKey = ProductInventorySizesJson.normalizeKey(movement.getSizeKey());
            entradasByStockAndSize
                    .computeIfAbsent(movement.getKioscoStockId(), k -> new LinkedHashMap<>())
                    .merge(sizeKey, delta, Integer::sum);
        }
    }

    /** Acumula deltas de movimientos del periodo por categoria de kardex kiosco. */
    private static final class KardexAccumulator {
        private int comprasAjustes;
        private int anulacionCompras;
        private int entradas;
        private int ventas;
        private int anulacionVenta;
        private int salida;
        private int salidaDevolucion;

        void apply(KioscoMovementType type, int delta) {
            switch (type) {
                case AJUSTE -> {
                    if (delta > 0) {
                        comprasAjustes += delta;
                    } else {
                        salida += -delta;
                    }
                }
                case DEVOLUCION_CLIENTE -> {
                    if (delta > 0) {
                        comprasAjustes += delta;
                    }
                }
                case ENTRADA, TRASLADO_ENTRADA -> {
                    if (delta > 0) {
                        entradas += delta;
                    }
                }
                case VENTA -> {
                    if (delta < 0) {
                        ventas += -delta;
                    }
                }
                case ANULACION -> {
                    if (delta > 0) {
                        anulacionVenta += delta;
                    }
                }
                case DEVOLUCION_DEPOSITO -> {
                    if (delta < 0) {
                        int qty = -delta;
                        salida += qty;
                        salidaDevolucion += qty;
                    }
                }
                case TRASLADO_SALIDA, MERMA, DEVOLUCION_A_CLIENTE -> {
                    if (delta < 0) {
                        salida += -delta;
                    }
                }
                case CAMBIO -> {
                    if (delta > 0) {
                        comprasAjustes += delta;
                    } else {
                        salida += -delta;
                    }
                }
            }
        }

        int applyTo(int initial) {
            return initial + comprasAjustes - anulacionCompras + entradas - ventas + anulacionVenta - salida;
        }

        boolean isEmpty() {
            return comprasAjustes == 0 && anulacionCompras == 0 && entradas == 0
                    && ventas == 0 && anulacionVenta == 0 && salida == 0;
        }
    }

    public KioscoInventoryInitializeResponse initializeMissingStock(Long locationId, Long userId)
            throws BusinessException, ResourceNotFoundException {
        Long resolvedUserId = resolveUserIdRequired(userId);
        List<LocationEntity> kiosks = resolveKioskLocations(locationId);
        List<ProductEntity> products = productRepository.findAll();
        List<Long> catalogColorIds = colorRepository.findAll().stream()
                .map(c -> c.getId())
                .sorted()
                .collect(Collectors.toList());

        List<Long> kioskIds = kiosks.stream().map(LocationEntity::getId).collect(Collectors.toList());
        if (kioskIds.isEmpty()) {
            return KioscoInventoryInitializeResponse.builder()
                    .message("No hay kioskos configurados para inicializar.")
                    .kiosksProcessed(0)
                    .productsProcessed(products.size())
                    .createdCount(0)
                    .existingCount(0)
                    .locationId(locationId)
                    .build();
        }

        Set<String> existingColorKeys = kioscoStockRepository.findByLocationIdIn(kioskIds).stream()
                .map(s -> KioscoInventoryInitRules.stockColorKey(
                        s.getLocationId(), s.getProductId(), s.getColorId()))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<KioscoStockEntity> toCreate = new ArrayList<>();
        int existingCount = 0;

        for (LocationEntity kiosk : kiosks) {
            for (ProductEntity product : products) {
                if (product == null || product.getId() == null) {
                    continue;
                }
                if (KioscoInventoryInitRules.isPackagingProduct(product)) {
                    // Empaques SUM-: fuera del criterio de colores/tallas; una fila sin variante.
                    existingCount += appendInitStockIfMissing(
                            kiosk.getId(), product.getId(), null, null,
                            existingColorKeys, toCreate, resolvedUserId);
                    continue;
                }
                List<Long> colorIds = KioscoInventoryInitRules.resolveColorIds(product, catalogColorIds);
                String sizesData = KioscoInventoryInitRules.buildZeroSizesData(product);
                for (Long colorId : colorIds) {
                    existingCount += appendInitStockIfMissing(
                            kiosk.getId(), product.getId(), colorId, sizesData,
                            existingColorKeys, toCreate, resolvedUserId);
                }
            }
        }

        if (!toCreate.isEmpty()) {
            try {
                saveInitStockInBatches(toCreate);
            } catch (DataIntegrityViolationException ex) {
                log.warn("Init inventario kiosko: conflicto parcial al insertar — {}", ex.getMessage());
                int inserted = saveInitStockSkippingDuplicates(toCreate);
                if (inserted == 0) {
                    throw new BusinessException(
                            "No se pudo crear inventario: ya existen registros para este kiosko "
                                    + "o falta ejecutar la migración de herraje (migration-kiosco-stock-hardware-split.sql).");
                }
            } catch (Exception ex) {
                String msg = ex.getMessage() != null ? ex.getMessage().toLowerCase(Locale.ROOT) : "";
                if (msg.contains("hardware_condition")) {
                    throw new BusinessException(
                            "Falta la columna hardware_condition en kiosco_stock. "
                                    + "Ejecute scripts/migration-kiosco-stock-hardware-split.sql en la base de datos.");
                }
                throw ex;
            }
        }

        String scopeLabel = locationId != null ? "kiosko seleccionado" : "todos los kioskos";
        return KioscoInventoryInitializeResponse.builder()
                .message("Inventario kiosko inicializado para " + scopeLabel
                        + " (variantes por color; cinchos con tallas en cero; empaques SUM- sin color ni tallas).")
                .kiosksProcessed(kiosks.size())
                .productsProcessed(products.size())
                .createdCount(toCreate.size())
                .existingCount(existingCount)
                .locationId(locationId)
                .build();
    }

    private static final int INIT_STOCK_BATCH_SIZE = 250;

    /**
     * @return 1 si ya existía la fila, 0 si se agregó a {@code toCreate}.
     */
    private static int appendInitStockIfMissing(
            Long locationId,
            Long productId,
            Long colorId,
            String sizesData,
            Set<String> existingColorKeys,
            List<KioscoStockEntity> toCreate,
            Long userId) {
        String colorKey = KioscoInventoryInitRules.stockColorKey(locationId, productId, colorId);
        if (existingColorKeys.contains(colorKey)) {
            return 1;
        }
        toCreate.add(KioscoStockEntity.builder()
                .locationId(locationId)
                .productId(productId)
                .colorId(colorId)
                .currentStock(0)
                .minimumStock(0)
                .sizesData(sizesData)
                .hardwareCondition(ProductHardwareCondition.NUEVO)
                .createdBy(userId)
                .updatedBy(userId)
                .build());
        existingColorKeys.add(colorKey);
        return 0;
    }

    private void saveInitStockInBatches(List<KioscoStockEntity> entities) {
        for (int i = 0; i < entities.size(); i += INIT_STOCK_BATCH_SIZE) {
            int end = Math.min(i + INIT_STOCK_BATCH_SIZE, entities.size());
            kioscoStockRepository.saveAll(entities.subList(i, end));
            kioscoStockRepository.flush();
        }
    }

    private int saveInitStockSkippingDuplicates(List<KioscoStockEntity> entities) {
        int inserted = 0;
        for (KioscoStockEntity entity : entities) {
            try {
                kioscoStockRepository.save(entity);
                inserted++;
            } catch (DataIntegrityViolationException ignored) {
                // Fila ya existía (legacy o carrera concurrente).
            }
        }
        return inserted;
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
            boolean syncLegacy,
            String sizeKey
    ) throws BusinessException, ResourceNotFoundException {
        return registrarEntradaInternal(
                locationId, productId, colorId, quantity, referenceId, userId, syncLegacy, sizeKey, null, null);
    }

    private KioscoStockResponse registrarEntradaInternal(
            Long locationId,
            Long productId,
            Long colorId,
            Integer quantity,
            Long referenceId,
            Long userId,
            boolean syncLegacy,
            String sizeKey,
            String reason
    ) throws BusinessException, ResourceNotFoundException {
        return registrarEntradaInternal(
                locationId, productId, colorId, quantity, referenceId, userId, syncLegacy, sizeKey, reason, null);
    }

    private KioscoStockResponse registrarEntradaInternal(
            Long locationId,
            Long productId,
            Long colorId,
            Integer quantity,
            Long referenceId,
            Long userId,
            boolean syncLegacy,
            String sizeKey,
            String reason,
            String hardwareCondition
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
                reason,
                sizeKey,
                syncLegacy,
                null,
                null,
                resolveStockHardware(hardwareCondition)
        );
    }

    private KioscoStockResponse registrarVentaInternal(
            Long locationId,
            Long productId,
            Long colorId,
            Integer quantity,
            Long invoiceId,
            Long userId,
            boolean syncLegacy,
            String sizeKey,
            String hardwareCondition
    ) throws BusinessException, ResourceNotFoundException {
        if (hasPosReferenceMovementApplied(
                locationId, productId, colorId, invoiceId, KioscoMovementType.VENTA, sizeKey)) {
            log.info(
                    "KIOSCO_VENTA_SKIPPED locationId={} productId={} colorId={} invoiceId={} (already applied)",
                    locationId, productId, colorId, invoiceId);
            return resolveStockResponseForNoOp(locationId, productId, colorId, hardwareCondition);
        }
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
                sizeKey,
                syncLegacy,
                null,
                null,
                hardwareCondition
        );
        verificarStockMinimo(locationId, productId, colorId);
        return response;
    }

    private boolean hasPosReferenceMovementApplied(
            Long locationId,
            Long productId,
            Long colorId,
            Long referenceId,
            KioscoMovementType movementType,
            String sizeKey
    ) {
        if (referenceId == null || locationId == null || productId == null || movementType == null) {
            return false;
        }
        String normalizedSize = ProductInventorySizesJson.normalizeKey(sizeKey);
        String sizeKeyOrNull = normalizedSize.isEmpty() ? null : normalizedSize;
        return kioscoMovementRepository.existsPosReferenceMovement(
                locationId, productId, colorId, referenceId, movementType, sizeKeyOrNull);
    }

    private boolean shouldSplitVentaByHardware(Long productId, String hardwareCondition) {
        if (ProductHardwareCondition.normalize(hardwareCondition) != null) {
            return false;
        }
        if (productId == null) {
            return false;
        }
        return productRepository.findById(productId)
                .map(p -> CinchoProductUtils.isFossCinchoProduct(p)
                        || CinchoProductUtils.isMesaCinchosProduct(p))
                .orElse(false);
    }

    private KioscoStockResponse registrarVentaFifoByHardware(
            Long locationId,
            Long productId,
            Long colorId,
            int quantity,
            Long invoiceId,
            Long userId,
            String sizeKey,
            boolean syncLegacy
    ) throws BusinessException, ResourceNotFoundException {
        if (hasPosReferenceMovementApplied(
                locationId, productId, colorId, invoiceId, KioscoMovementType.VENTA, sizeKey)) {
            log.info(
                    "KIOSCO_VENTA_FIFO_SKIPPED locationId={} productId={} colorId={} invoiceId={} (already applied)",
                    locationId, productId, colorId, invoiceId);
            return resolveStockResponseForNoOp(locationId, productId, colorId, null);
        }
        int remaining = quantity;
        KioscoStockResponse last = null;
        for (String hardware : List.of(ProductHardwareCondition.NUEVO, ProductHardwareCondition.VIEJO)) {
            if (remaining <= 0) {
                break;
            }
            int available = kioscoStockRepository
                    .findByLocationIdAndProductIdAndColorIdAndHardwareCondition(
                            locationId, productId, colorId, hardware)
                    .map(s -> safeInt(s.getCurrentStock()))
                    .orElse(0);
            int take = remaining;
            if (available > 0 && available < remaining) {
                take = available;
            } else if (available <= 0 && !ProductHardwareCondition.VIEJO.equals(hardware)) {
                continue;
            }
            last = applyStockMovement(
                    locationId,
                    productId,
                    colorId,
                    take,
                    invoiceId,
                    null,
                    null,
                    resolveUserIdRequired(userId),
                    KioscoMovementType.VENTA,
                    -take,
                    true,
                    null,
                    sizeKey,
                    syncLegacy,
                    null,
                    null,
                    hardware
            );
            remaining -= take;
        }
        if (remaining > 0) {
            last = applyStockMovement(
                    locationId,
                    productId,
                    colorId,
                    remaining,
                    invoiceId,
                    null,
                    null,
                    resolveUserIdRequired(userId),
                    KioscoMovementType.VENTA,
                    -remaining,
                    true,
                    null,
                    sizeKey,
                    syncLegacy,
                    null,
                    null,
                    ProductHardwareCondition.VIEJO
            );
        }
        verificarStockMinimo(locationId, productId, colorId);
        return last;
    }

    private String resolveStockHardware(String hardwareCondition) {
        String normalized = ProductHardwareCondition.normalize(hardwareCondition);
        return normalized != null ? normalized : ProductHardwareCondition.NUEVO;
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
            String sizeKey,
            boolean syncLegacy
    ) throws BusinessException, ResourceNotFoundException {
        return applyStockMovement(
                locationId,
                productId,
                colorId,
                quantity,
                referenceId,
                originLocationId,
                destinationLocationId,
                userId,
                movementType,
                delta,
                affectsStock,
                reason,
                sizeKey,
                syncLegacy,
                null,
                null,
                null
        );
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
            String sizeKey,
            boolean syncLegacy,
            String physicalSlipNumber
    ) throws BusinessException, ResourceNotFoundException {
        return applyStockMovement(
                locationId,
                productId,
                colorId,
                quantity,
                referenceId,
                originLocationId,
                destinationLocationId,
                userId,
                movementType,
                delta,
                affectsStock,
                reason,
                sizeKey,
                syncLegacy,
                physicalSlipNumber,
                null,
                null
        );
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
            String sizeKey,
            boolean syncLegacy,
            String physicalSlipNumber,
            Long physicalCountId
    ) throws BusinessException, ResourceNotFoundException {
        return applyStockMovement(
                locationId,
                productId,
                colorId,
                quantity,
                referenceId,
                originLocationId,
                destinationLocationId,
                userId,
                movementType,
                delta,
                affectsStock,
                reason,
                sizeKey,
                syncLegacy,
                physicalSlipNumber,
                physicalCountId,
                null
        );
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
            String sizeKey,
            boolean syncLegacy,
            String physicalSlipNumber,
            Long physicalCountId,
            String hardwareCondition
    ) throws BusinessException, ResourceNotFoundException {
        return applyStockMovementWithMovement(
                locationId,
                productId,
                colorId,
                quantity,
                referenceId,
                originLocationId,
                destinationLocationId,
                userId,
                movementType,
                delta,
                affectsStock,
                reason,
                sizeKey,
                syncLegacy,
                physicalSlipNumber,
                physicalCountId,
                hardwareCondition
        ).stockResponse();
    }

    public record KioscoMovementWithStock(KioscoStockResponse stockResponse, KioscoMovementEntity movement) {}

    private KioscoMovementWithStock applyStockMovementWithMovement(
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
            String sizeKey,
            boolean syncLegacy,
            String physicalSlipNumber
    ) throws BusinessException, ResourceNotFoundException {
        return applyStockMovementWithMovement(
                locationId, productId, colorId, quantity, referenceId, originLocationId, destinationLocationId,
                userId, movementType, delta, affectsStock, reason, sizeKey, syncLegacy, physicalSlipNumber, null, null);
    }

    private KioscoMovementWithStock applyStockMovementWithMovement(
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
            String sizeKey,
            boolean syncLegacy,
            String physicalSlipNumber,
            Long physicalCountId
    ) throws BusinessException, ResourceNotFoundException {
        return applyStockMovementWithMovement(
                locationId, productId, colorId, quantity, referenceId, originLocationId, destinationLocationId,
                userId, movementType, delta, affectsStock, reason, sizeKey, syncLegacy, physicalSlipNumber,
                physicalCountId, null);
    }

    private KioscoMovementWithStock applyStockMovementWithMovement(
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
            String sizeKey,
            boolean syncLegacy,
            String physicalSlipNumber,
            Long physicalCountId,
            String hardwareCondition
    ) throws BusinessException, ResourceNotFoundException {
        validateQuantity(quantity);
        validateLocationIsKiosk(locationId);
        validateProduct(productId);
        validateColor(colorId);
        validateUser(userId);

        KioscoStockEntity stock = getOrCreateLockedStock(
                locationId, productId, colorId, userId, resolveStockHardware(hardwareCondition));
        syncFossCurrentStockFromSizes(stock);
        stock = kioscoStockRepository
                .findForUpdateByHardware(locationId, productId, colorId, resolveStockHardware(hardwareCondition))
                .orElse(stock);
        validateSizeKeyRequired(locationId, productId, colorId, stock, sizeKey);
        int before = safeInt(stock.getCurrentStock());
        int after = before;

        if (affectsStock) {
            after = applyStockDelta(stock, quantity, delta, sizeKey);
            stock.setUpdatedBy(userId);
            stock.setLastUpdatedAt(GuatemalaDateTime.now());
            stock = kioscoStockRepository.save(stock);
        }

        KioscoMovementEntity movement = saveMovement(
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
                destinationLocationId,
                physicalSlipNumber,
                sizeKey,
                physicalCountId
        );

        if (syncLegacy && affectsStock) {
            try {
                syncLegacyInventory(locationId, productId, colorId, delta, sizeKey);
            } catch (Exception ex) {
                // Kiosco es la fuente de verdad: legacy desfasado no debe tumbar el movimiento.
                log.warn(
                        "KIOSCO_LEGACY_SYNC_FAIL locationId={} productId={} colorId={} delta={} size={} msg={}. Aligning to kiosco.",
                        locationId, productId, colorId, delta, sizeKey, ex.getMessage());
                try {
                    alignLegacyInventoryToKioscoStock(stock);
                } catch (Exception alignEx) {
                    log.warn(
                            "KIOSCO_LEGACY_ALIGN_FAIL locationId={} productId={} colorId={} msg={}",
                            locationId, productId, colorId, alignEx.getMessage());
                }
            }
        }

        return new KioscoMovementWithStock(toStockResponse(stock), movement);
    }

    private int applyStockDelta(KioscoStockEntity stock, int quantity, int delta, String sizeKey) throws BusinessException {
        Map<String, BigDecimal> sizesMap = ProductInventorySizesJson.parse(stock.getSizesData());
        boolean breakdown = !sizesMap.isEmpty();
        String normalizedSize = ProductInventorySizesJson.normalizeKey(sizeKey);
        BigDecimal qtyBd = BigDecimal.valueOf(quantity);

        if (breakdown) {
            if (normalizedSize.isEmpty()) {
                throw new BusinessException("Indique la talla para esta operación de inventario kiosko.");
            }
            if (delta > 0) {
                sizesMap.merge(normalizedSize, qtyBd, BigDecimal::add);
            } else if (delta < 0) {
                BigDecimal available = sizesMap.getOrDefault(normalizedSize, BigDecimal.ZERO);
                if (available.compareTo(qtyBd) < 0) {
                    throw new BusinessException("Stock insuficiente de talla " + normalizedSize + " en kiosko. Disponible: "
                            + available.stripTrailingZeros().toPlainString() + ", solicitado: "
                            + qtyBd.stripTrailingZeros().toPlainString());
                }
                sizesMap.put(normalizedSize, available.subtract(qtyBd));
                ProductInventorySizesJson.removeZeroEntries(sizesMap);
            }
            stock.setSizesData(ProductInventorySizesJson.serialize(sizesMap));
            int total = ProductInventorySizesJson.sum(sizesMap).setScale(0, RoundingMode.HALF_UP).intValue();
            stock.setCurrentStock(total);
            return total;
        }

        // Sin sizes_data: NO inventar un mapa de una sola talla (eso ponía current=qty y borraba el resto).
        // Solo mover el total agregado. El desglose se recupera con replay desde movimientos.
        int next = safeInt(stock.getCurrentStock()) + delta;
        if (next < 0) {
            throw new BusinessException("Stock insuficiente en kiosko. Disponible: " + safeInt(stock.getCurrentStock())
                    + ", solicitado: " + quantity);
        }
        stock.setCurrentStock(next);
        return next;
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
        saveMovement(
                stock,
                movementType,
                quantity,
                stockBefore,
                stockAfter,
                referenceId,
                reason,
                affectsStock,
                userId,
                originLocationId,
                destinationLocationId,
                null,
                null
        );
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
            Long destinationLocationId,
            String physicalSlipNumber
    ) {
        saveMovement(
                stock,
                movementType,
                quantity,
                stockBefore,
                stockAfter,
                referenceId,
                reason,
                affectsStock,
                userId,
                originLocationId,
                destinationLocationId,
                physicalSlipNumber,
                null,
                null
        );
    }

    private KioscoMovementEntity saveMovement(
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
            Long destinationLocationId,
            String physicalSlipNumber,
            String sizeKey
    ) {
        return saveMovement(
                stock, movementType, quantity, stockBefore, stockAfter, referenceId, reason, affectsStock, userId,
                originLocationId, destinationLocationId, physicalSlipNumber, sizeKey, null);
    }

    private KioscoMovementEntity saveMovement(
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
            Long destinationLocationId,
            String physicalSlipNumber,
            String sizeKey,
            Long physicalCountId
    ) {
        String normalizedSlip = normalizePhysicalSlipNumber(physicalSlipNumber);
        String normalizedSize = ProductInventorySizesJson.normalizeKey(sizeKey);
        KioscoMovementEntity movement = KioscoMovementEntity.builder()
                .kioscoStockId(stock.getId())
                .movementType(movementType)
                .quantity(quantity)
                .sizeKey(normalizedSize.isEmpty() ? null : normalizedSize)
                .stockBefore(stockBefore)
                .stockAfter(stockAfter)
                .referenceId(referenceId)
                .physicalCountId(physicalCountId)
                .physicalSlipNumber(normalizedSlip)
                .reason(safeTrim(reason))
                .affectsStock(affectsStock)
                .userId(userId)
                .originLocationId(originLocationId)
                .destinationLocationId(destinationLocationId)
                .build();
        return kioscoMovementRepository.save(movement);
    }

    private KioscoStockEntity getOrCreateLockedStock(Long locationId, Long productId, Long colorId, Long userId)
            throws BusinessException {
        return getOrCreateLockedStock(locationId, productId, colorId, userId, ProductHardwareCondition.NUEVO);
    }

    private KioscoStockEntity getOrCreateLockedStock(
            Long locationId,
            Long productId,
            Long colorId,
            Long userId,
            String hardwareCondition
    ) throws BusinessException {
        String hardware = resolveStockHardware(hardwareCondition);
        return kioscoStockProvisioningService.ensureStockRow(
                locationId, productId, colorId, userId, hardware);
    }

    private void syncLegacyInventory(Long locationId, Long productId, Long colorId, int delta, String sizeKey)
            throws BusinessException, ResourceNotFoundException {
        if (delta == 0) {
            return;
        }
        BigDecimal qty = BigDecimal.valueOf(Math.abs(delta));
        String normalizedSize = ProductInventorySizesJson.normalizeKey(sizeKey);
        String sizeForLegacy = normalizedSize.isEmpty() ? null : normalizedSize;
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
                    "Sincronización desde módulo kiosco",
                    sizeForLegacy
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
                "Sincronización desde módulo kiosco",
                sizeForLegacy
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

    private String normalizePhysicalSlipNumber(String value) {
        String trimmed = safeTrim(value);
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void validatePhysicalSlipNumber(String physicalSlipNumber, boolean required) throws BusinessException {
        String normalized = normalizePhysicalSlipNumber(physicalSlipNumber);
        if (required && normalized == null) {
            throw new BusinessException("Debes indicar el número de boleta física.");
        }
        if (normalized != null && kioscoMovementRepository.existsByPhysicalSlipNumber(normalized)) {
            throw new BusinessException("El número de boleta física ya fue registrado en inventario kiosko.");
        }
    }

    private Map<String, BigDecimal> positiveSizesMap(String sizesDataJson) {
        Map<String, BigDecimal> parsed = ProductInventorySizesJson.parse(sizesDataJson);
        parsed.entrySet().removeIf(e -> e.getValue() == null || e.getValue().compareTo(BigDecimal.ZERO) <= 0);
        return parsed.isEmpty() ? null : parsed;
    }

    private String resolveLegacySizesDataJson(Long locationId, Long productId, Long colorId) {
        if (locationId == null || productId == null) {
            return null;
        }
        return productInventoryLocationRepository
                .findByProductIdAndLocationIdAndColorId(productId, locationId, colorId)
                .map(ProductInventoryLocation::getSizesData)
                .orElse(null);
    }

    private KioscoStockResponse toStockResponse(KioscoStockEntity entity) {
        LocationEntity location = entity.getLocation();
        ProductEntity product = entity.getProduct();
        ColorEntity color = entity.getColor();
        int current = resolveInventarioFinal(entity, product);
        int minimum = safeInt(entity.getMinimumStock());
        Map<String, BigDecimal> sizes = positiveSizesMap(entity.getSizesData());
        if ((sizes == null || sizes.isEmpty()) && CinchoProductUtils.isFossCinchoProduct(product)) {
            sizes = positiveSizesMap(resolveLegacySizesDataJson(
                    entity.getLocationId(), entity.getProductId(), entity.getColorId()));
        }
        return KioscoStockResponse.builder()
                .id(entity.getId())
                .locationId(entity.getLocationId())
                .locationCode(location != null ? location.getCode() : null)
                .locationName(location != null ? location.getName() : null)
                .productId(entity.getProductId())
                .productCode(product != null ? product.getCode() : null)
                .productName(product != null ? product.getName() : null)
                .cinchoType(product != null ? ProductCinchoType.normalizeCinchoType(product.getCinchoType()) : null)
                .cinchoForKids(product != null && Boolean.TRUE.equals(product.getCinchoForKids()))
                .colorId(entity.getColorId())
                .colorName(color != null ? color.getName() : null)
                .currentStock(current)
                .sizes(sizes)
                .minimumStock(minimum)
                .hardwareCondition(entity.getHardwareCondition())
                .lowStock(current <= minimum)
                .lastUpdatedAt(entity.getLastUpdatedAt())
                .build();
    }

    public KioscoMovementResponse toMovementResponse(KioscoMovementEntity entity) {
        KioscoStockEntity stock = entity.getKioscoStock();
        LocationEntity location = stock != null ? stock.getLocation() : null;
        ProductEntity product = stock != null ? stock.getProduct() : null;
        ColorEntity color = stock != null ? stock.getColor() : null;
        UserEntity user = entity.getUser();
        Long productId = stock != null ? stock.getProductId() : null;
        Long colorId = stock != null ? stock.getColorId() : null;

        RouteContext route = resolveMovementRoute(entity, productId, colorId);
        LocationEntity originLocation = resolveLocation(route.originLocationId());
        LocationEntity destinationLocation = resolveLocation(route.destinationLocationId());

        return KioscoMovementResponse.builder()
                .id(entity.getId())
                .kioscoStockId(entity.getKioscoStockId())
                .locationId(stock != null ? stock.getLocationId() : null)
                .locationName(location != null ? location.getName() : null)
                .productId(productId)
                .productCode(product != null ? product.getCode() : null)
                .productName(product != null ? product.getName() : null)
                .colorId(colorId)
                .colorName(color != null ? color.getName() : null)
                .movementType(entity.getMovementType())
                .quantity(entity.getQuantity())
                .sizeKey(entity.getSizeKey())
                .stockBefore(entity.getStockBefore())
                .stockAfter(entity.getStockAfter())
                .referenceId(entity.getReferenceId())
                .referenceType(route.referenceType())
                .referenceNumber(route.referenceNumber())
                .physicalSlipNumber(entity.getPhysicalSlipNumber())
                .reason(entity.getReason())
                .affectsStock(entity.getAffectsStock())
                .userId(entity.getUserId())
                .username(user != null ? user.getUsername() : null)
                .originLocationId(route.originLocationId())
                .originLocationName(locationName(originLocation))
                .originLocationCode(locationCode(originLocation))
                .destinationLocationId(route.destinationLocationId())
                .destinationLocationName(locationName(destinationLocation))
                .destinationLocationCode(locationCode(destinationLocation))
                .createdAt(entity.getCreatedAt())
                .build();
    }

    private record RouteContext(Long originLocationId, Long destinationLocationId, String referenceType, String referenceNumber) {}

    private RouteContext resolveMovementRoute(KioscoMovementEntity entity, Long productId, Long colorId) {
        Long originId = entity.getOriginLocationId();
        Long destId = entity.getDestinationLocationId();
        String refType = null;
        String refNumber = null;

        if (entity.getReferenceId() != null) {
            Optional<ProductShipmentEntity> shipmentOpt = productShipmentRepository.findById(entity.getReferenceId());
            if (shipmentOpt.isPresent()) {
                ProductShipmentEntity shipment = shipmentOpt.get();
                refType = "SHIPMENT";
                refNumber = shipment.getShipmentNumber();
                if (destId == null && shipment.getLocationId() != null) {
                    destId = shipment.getLocationId();
                }
                if (originId == null) {
                    originId = resolveShipmentOriginLocationId(shipment, productId, colorId);
                }
            } else if (entity.getMovementType() == KioscoMovementType.ENTRADA
                    || entity.getMovementType() == KioscoMovementType.DEVOLUCION_DEPOSITO) {
                Optional<InventoryTransfer> transferOpt = inventoryTransferRepository.findById(entity.getReferenceId());
                if (transferOpt.isPresent()) {
                    InventoryTransfer transfer = transferOpt.get();
                    refType = "TRANSFER";
                    refNumber = "TRF-" + transfer.getId();
                    if (originId == null) {
                        originId = transfer.getFromLocationId();
                    }
                    if (destId == null) {
                        destId = transfer.getToLocationId();
                    }
                }
            } else if (entity.getMovementType() == KioscoMovementType.TRASLADO_ENTRADA
                    || entity.getMovementType() == KioscoMovementType.TRASLADO_SALIDA) {
                refType = "TRASLADO";
                refNumber = "Traslado #" + entity.getReferenceId();
            } else if (entity.getMovementType() == KioscoMovementType.VENTA
                    || entity.getMovementType() == KioscoMovementType.ANULACION) {
                refType = "INVOICE";
                refNumber = "Factura #" + entity.getReferenceId();
            }
        }

        if (entity.getMovementType() == KioscoMovementType.ENTRADA
                && isShipmentReceiptReason(entity.getReason())
                && entity.getReferenceId() != null
                && refType == null) {
            refType = "SHIPMENT";
            refNumber = extractShipmentNumberFromReason(entity.getReason());
        }

        return new RouteContext(originId, destId, refType, refNumber);
    }

    private Long resolveShipmentOriginLocationId(ProductShipmentEntity shipment, Long productId, Long colorId) {
        if (shipment == null || shipment.getId() == null) {
            return defaultDispatchOriginLocationId();
        }
        List<ProductInventoryKardex> rows = productInventoryKardexRepository
                .findByReferenceTypeAndReferenceId("SHIPMENT", shipment.getId());
        for (ProductInventoryKardex row : rows) {
            if (productId != null && !productId.equals(row.getProductId())) {
                continue;
            }
            if (!Objects.equals(colorId, row.getColorId())) {
                continue;
            }
            if (row.getQuantity() != null && row.getQuantity().compareTo(BigDecimal.ZERO) < 0) {
                return row.getLocationId();
            }
        }
        for (ProductInventoryKardex row : rows) {
            if (productId != null && !productId.equals(row.getProductId())) {
                continue;
            }
            if (row.getQuantity() != null && row.getQuantity().compareTo(BigDecimal.ZERO) < 0) {
                return row.getLocationId();
            }
        }
        return defaultDispatchOriginLocationId();
    }

    private Long defaultDispatchOriginLocationId() {
        try {
            List<LocationEntity> warehouses = productInventoryService.getDispatchSourceWarehouses();
            for (int i = warehouses.size() - 1; i >= 0; i--) {
                LocationEntity loc = warehouses.get(i);
                if (loc != null && loc.getId() != null) {
                    return loc.getId();
                }
            }
        } catch (BusinessException ex) {
            log.debug("No se pudo resolver bodega de despacho para ruta de envío: {}", ex.getMessage());
        }
        return null;
    }

    private String buildShipmentEntradaReason(ProductShipmentEntity shipment, String lineRef) {
        String shipmentNumber = shipment.getShipmentNumber() != null && !shipment.getShipmentNumber().isBlank()
                ? shipment.getShipmentNumber().trim()
                : ("#" + shipment.getId());
        if (lineRef != null && !lineRef.isBlank()) {
            return "Recepción envío " + shipmentNumber + " · " + shipmentReceiptLineReason(lineRef);
        }
        return "Recepción envío " + shipmentNumber;
    }

    private String buildShipmentReconcileReason(Long shipmentId, String lineRef) {
        if (shipmentId != null) {
            Optional<ProductShipmentEntity> shipmentOpt = productShipmentRepository.findById(shipmentId);
            if (shipmentOpt.isPresent()) {
                return "Cuadre recepción envío " + buildShipmentEntradaReason(shipmentOpt.get(), lineRef);
            }
        }
        if (lineRef != null && !lineRef.isBlank()) {
            return "Cuadre recepción envío · " + shipmentReceiptLineReason(lineRef);
        }
        return "Cuadre recepción envío";
    }

    private boolean isShipmentReceiptReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return false;
        }
        return reason.contains(SHIPMENT_RECEIPT_LINE_PREFIX) || reason.toLowerCase(Locale.ROOT).contains("recepción envío")
                || reason.toLowerCase(Locale.ROOT).contains("recepcion envio");
    }

    private String extractShipmentNumberFromReason(String reason) {
        if (reason == null) {
            return null;
        }
        String marker = "Recepción envío ";
        int idx = reason.indexOf(marker);
        if (idx < 0) {
            marker = "Recepcion envio ";
            idx = reason.indexOf(marker);
        }
        if (idx < 0) {
            return null;
        }
        String tail = reason.substring(idx + marker.length()).trim();
        int sep = tail.indexOf('·');
        if (sep < 0) {
            sep = tail.indexOf(" · ");
        }
        return sep > 0 ? tail.substring(0, sep).trim() : tail.trim();
    }

    private LocationEntity resolveLocation(Long locationId) {
        if (locationId == null) {
            return null;
        }
        return locationRepository.findById(locationId).orElse(null);
    }

    private static String locationName(LocationEntity location) {
        return location != null ? location.getName() : null;
    }

    private static String locationCode(LocationEntity location) {
        return location != null ? location.getCode() : null;
    }

    /**
     * Habilita DELETE/UPDATE en kiosco_movement en la conexion JDBC actual
     * (requiere migration-kiosco-movement-admin-delete.sql).
     * Usa alcance de sesion (no solo la sentencia) para que el flag no se pierda entre queries.
     */
    public void enableAdminMovementMutation() {
        entityManager.createNativeQuery(
                        "SELECT set_config(:key, 'true', false)")
                .setParameter("key", ADMIN_MOVEMENT_MUTATION_KEY)
                .getSingleResult();
    }

    /** Restablece append-only al devolver la conexion al pool. */
    public void disableAdminMovementMutation() {
        entityManager.createNativeQuery(
                        "SELECT set_config(:key, 'false', false)")
                .setParameter("key", ADMIN_MOVEMENT_MUTATION_KEY)
                .getSingleResult();
    }

    public void deleteAdminMovement(KioscoMovementEntity movement) throws BusinessException {
        if (movement == null || movement.getId() == null) {
            return;
        }
        enableAdminMovementMutation();
        int deleted = entityManager.createNativeQuery("DELETE FROM kiosco_movement WHERE id = :id")
                .setParameter("id", movement.getId())
                .executeUpdate();
        entityManager.flush();
        if (deleted <= 0) {
            throw new BusinessException(
                    "No se pudo eliminar el movimiento #" + movement.getId()
                            + ". Verifique migration-kiosco-movement-admin-delete.sql y el trigger en kiosco_movement.");
        }
    }

    public void trimAdminEntradaQuantity(KioscoMovementEntity movement, int newQuantity) throws BusinessException {
        if (movement == null || movement.getId() == null || newQuantity <= 0) {
            return;
        }
        enableAdminMovementMutation();
        int updated = entityManager.createNativeQuery(
                        "UPDATE kiosco_movement SET quantity = :qty, stock_after = stock_before + :qty WHERE id = :id")
                .setParameter("qty", newQuantity)
                .setParameter("id", movement.getId())
                .executeUpdate();
        entityManager.flush();
        if (updated <= 0) {
            throw new BusinessException(
                    "No se pudo ajustar el movimiento #" + movement.getId()
                            + ". Verifique migration-kiosco-movement-admin-delete.sql y el trigger en kiosco_movement.");
        }
        movement.setQuantity(newQuantity);
        movement.setStockAfter(safeInt(movement.getStockBefore()) + newQuantity);
    }

    /**
     * Elimina MERMA de cuadre previo por linea de envio (artefacto de reconciliacion antigua).
     */
    public int deleteShipmentReconcileMermaMovements(
            Long locationId,
            Long shipmentId,
            String lineRef,
            Long productId,
            Long colorId
    ) throws BusinessException {
        if (locationId == null || shipmentId == null || lineRef == null || lineRef.isBlank()) {
            return 0;
        }
        List<KioscoMovementEntity> mermaRows = kioscoMovementRepository.findShipmentReconcileMermaMovements(
                locationId, shipmentId, lineRef, productId, colorId);
        for (KioscoMovementEntity movement : mermaRows) {
            deleteAdminMovement(movement);
        }
        return mermaRows.size();
    }

    /**
     * Conserva ENTRADAs mas antiguas hasta expected y elimina sobrantes (duplicados de envio).
     */
    public int pruneExcessShipmentEntradas(List<KioscoMovementEntity> movementsOrderedAsc, int expected)
            throws BusinessException {
        EntradaPrunePlan plan = planPruneExcessShipmentEntradas(movementsOrderedAsc, expected);
        int applied = 0;
        for (PlannedEntradaAction action : plan.actions()) {
            KioscoMovementEntity movement = movementsOrderedAsc.stream()
                    .filter(m -> Objects.equals(m.getId(), action.movementId()))
                    .findFirst()
                    .orElse(null);
            if (movement == null) {
                continue;
            }
            if ("TRIM_ENTRADA".equals(action.type())) {
                trimAdminEntradaQuantity(movement, action.quantity() != null ? action.quantity() : 0);
                applied++;
            } else if ("DELETE_ENTRADA".equals(action.type())) {
                deleteAdminMovement(movement);
                applied++;
            }
        }
        return applied;
    }

    public EntradaPrunePlan planPruneExcessShipmentEntradas(
            List<KioscoMovementEntity> movementsOrderedAsc,
            int expected
    ) {
        if (movementsOrderedAsc == null || movementsOrderedAsc.isEmpty() || expected < 0) {
            return new EntradaPrunePlan(0, List.of());
        }
        List<PlannedEntradaAction> actions = new ArrayList<>();
        int removed = 0;
        int keptQty = 0;
        for (KioscoMovementEntity movement : movementsOrderedAsc) {
            int qty = safeInt(movement.getQuantity());
            if (qty <= 0) {
                actions.add(new PlannedEntradaAction(
                        "DELETE_ENTRADA",
                        movement.getId(),
                        qty,
                        "Eliminar ENTRADA vacía #" + movement.getId()));
                removed++;
                continue;
            }
            if (keptQty >= expected) {
                actions.add(new PlannedEntradaAction(
                        "DELETE_ENTRADA",
                        movement.getId(),
                        qty,
                        "Eliminar ENTRADA duplicada #" + movement.getId() + " (" + qty + " u.)"));
                removed++;
                continue;
            }
            if (keptQty + qty <= expected) {
                keptQty += qty;
                continue;
            }
            int allowed = expected - keptQty;
            if (allowed <= 0) {
                actions.add(new PlannedEntradaAction(
                        "DELETE_ENTRADA",
                        movement.getId(),
                        qty,
                        "Eliminar ENTRADA duplicada #" + movement.getId() + " (" + qty + " u.)"));
                removed++;
            } else {
                actions.add(new PlannedEntradaAction(
                        "TRIM_ENTRADA",
                        movement.getId(),
                        allowed,
                        "Recortar ENTRADA #" + movement.getId() + " de " + qty + " a " + allowed + " u."));
                keptQty = expected;
            }
        }
        return new EntradaPrunePlan(removed, actions);
    }

    public record PlannedEntradaAction(String type, Long movementId, Integer quantity, String label) {}

    public record EntradaPrunePlan(int removedCount, List<PlannedEntradaAction> actions) {}

    /** Marker appended to movement.reason when replay clamps a negative running stock to 0. */
    static final String REPLAY_CLAMP_REASON_MARKER = "REPLAY_CLAMP";

    /**
     * Recalcula stock_before/stock_after y current_stock (requiere flag admin en la transaccion).
     * Clamps negativos a 0 for safety, but logs and annotates each clamp on the movement reason.
     */
    public int replayMovementStockChain(Long kioscoStockId) {
        if (kioscoStockId == null) {
            return 0;
        }
        KioscoStockEntity stock = kioscoStockRepository.findById(kioscoStockId).orElse(null);
        if (stock == null) {
            return 0;
        }
        enableAdminMovementMutation();
        List<KioscoMovementEntity> movements = kioscoMovementRepository
                .findByKioscoStockIdOrderByCreatedAtAscIdAsc(kioscoStockId);
        int running = 0;
        for (KioscoMovementEntity movement : movements) {
            if (!Boolean.TRUE.equals(movement.getAffectsStock())) {
                continue;
            }
            int delta = movementSignedDelta(movement);
            movement.setStockBefore(running);
            int rawAfter = running + delta;
            running = applyReplayClamp(stock.getId(), movement, rawAfter);
            movement.setStockAfter(running);
            enableAdminMovementMutation();
            boolean reasonTouched = movement.getReason() != null
                    && movement.getReason().contains(REPLAY_CLAMP_REASON_MARKER);
            if (reasonTouched) {
                entityManager.createNativeQuery(
                                "UPDATE kiosco_movement SET stock_before = :before, stock_after = :after, reason = :reason WHERE id = :id")
                        .setParameter("before", movement.getStockBefore())
                        .setParameter("after", movement.getStockAfter())
                        .setParameter("reason", movement.getReason())
                        .setParameter("id", movement.getId())
                        .executeUpdate();
            } else {
                entityManager.createNativeQuery(
                                "UPDATE kiosco_movement SET stock_before = :before, stock_after = :after WHERE id = :id")
                        .setParameter("before", movement.getStockBefore())
                        .setParameter("after", movement.getStockAfter())
                        .setParameter("id", movement.getId())
                        .executeUpdate();
            }
        }
        entityManager.flush();
        // Full ledger total (sized + unsized). rebuildSizes may refine when history is sized-only.
        stock.setCurrentStock(running);
        rebuildSizesDataFromMovements(stock, movements);
        kioscoStockRepository.save(stock);
        return 1;
    }

    /**
     * Recalcula current_stock a partir del ledger de movimientos.
     */
    public int replayStockLedger(Long kioscoStockId) {
        if (kioscoStockId == null) {
            return 0;
        }
        KioscoStockEntity stock = kioscoStockRepository.findById(kioscoStockId).orElse(null);
        if (stock == null) {
            return 0;
        }
        List<KioscoMovementEntity> movements = kioscoMovementRepository
                .findByKioscoStockIdOrderByCreatedAtAscIdAsc(kioscoStockId);
        int running = 0;
        for (KioscoMovementEntity movement : movements) {
            if (!Boolean.TRUE.equals(movement.getAffectsStock())) {
                continue;
            }
            int rawAfter = running + movementSignedDelta(movement);
            running = applyReplayClamp(stock.getId(), movement, rawAfter);
        }
        stock.setCurrentStock(running);
        rebuildSizesDataFromMovements(stock, movements);
        kioscoStockRepository.save(stock);
        return 1;
    }

    /**
     * Safety clamp for replay: never persist negative stock_after / current_stock.
     * Leaves a clear log + reason marker so clamps are not silent history destruction.
     */
    int applyReplayClamp(Long stockId, KioscoMovementEntity movement, int rawAfter) {
        if (rawAfter >= 0) {
            return rawAfter;
        }
        Long movementId = movement != null ? movement.getId() : null;
        String type = movement != null && movement.getMovementType() != null
                ? movement.getMovementType().name()
                : "?";
        log.warn(
                "KIOSCO_REPLAY_CLAMP stockId={} movementId={} type={} rawAfter={} -> 0",
                stockId, movementId, type, rawAfter);
        if (movement != null) {
            String note = REPLAY_CLAMP_REASON_MARKER + " rawAfter=" + rawAfter + " -> 0";
            String existing = movement.getReason();
            if (existing == null || existing.isBlank()) {
                movement.setReason(note);
            } else if (!existing.contains(REPLAY_CLAMP_REASON_MARKER)) {
                movement.setReason(existing + " · " + note);
            }
        }
        return 0;
    }

    /**
     * Reconstruye {@code sizes_data} rejugando movimientos con {@code size_key}.
     * <p>
     * Rule:
     * <ul>
     *   <li>sizes_data rebuilt only from sized (size_key) stock-affecting movements</li>
     *   <li>only sized history → current_stock = sum(positive sizes)</li>
     *   <li>only unsized history → current_stock left as full-ledger total; sizes_data cleared</li>
     *   <li>mixed sized+unsized → current_stock kept from full ledger replay (not wiped to sizes sum)</li>
     * </ul>
     */
    public void rebuildSizesDataFromMovements(KioscoStockEntity stock, List<KioscoMovementEntity> movements) {
        if (stock == null) {
            return;
        }
        List<KioscoMovementEntity> list = movements != null
                ? movements
                : kioscoMovementRepository.findByKioscoStockIdOrderByCreatedAtAscIdAsc(stock.getId());
        if (list == null) {
            list = List.of();
        }
        Map<String, Integer> bySize = new LinkedHashMap<>();
        boolean anySized = false;
        boolean anyUnsized = false;
        int runningTotal = 0;
        for (KioscoMovementEntity movement : list) {
            if (movement == null || !Boolean.TRUE.equals(movement.getAffectsStock())) {
                continue;
            }
            int delta = movementSignedDelta(movement);
            int rawAfter = runningTotal + delta;
            // Mirror replay clamp for the aggregate chain used when mixed/unsized.
            runningTotal = rawAfter < 0 ? 0 : rawAfter;
            String sizeKey = ProductInventorySizesJson.normalizeKey(movement.getSizeKey());
            if (sizeKey.isEmpty()) {
                anyUnsized = true;
                continue;
            }
            anySized = true;
            int next = bySize.getOrDefault(sizeKey, 0) + delta;
            bySize.put(sizeKey, Math.max(0, next));
        }
        if (!anySized) {
            // Only unsized (or empty ledger): clear stale size breakdown.
            boolean cleared = false;
            if (stock.getSizesData() != null) {
                stock.setSizesData(null);
                cleared = true;
            }
            if (anyUnsized) {
                stock.setCurrentStock(runningTotal);
            }
            if (cleared || anyUnsized) {
                stock.setLastUpdatedAt(GuatemalaDateTime.now());
                log.info(
                        "KIOSCO_REBUILD_SIZES_CLEAR stockId={} productId={} colorId={} reason=no_sized_movements ledgerTotal={} currentStock={}",
                        stock.getId(), stock.getProductId(), stock.getColorId(), runningTotal,
                        safeInt(stock.getCurrentStock()));
            }
            return;
        }
        Map<String, BigDecimal> sizes = new LinkedHashMap<>();
        int sizesSum = 0;
        for (Map.Entry<String, Integer> e : bySize.entrySet()) {
            int qty = e.getValue() != null ? e.getValue() : 0;
            if (qty <= 0) {
                continue;
            }
            sizes.put(e.getKey(), BigDecimal.valueOf(qty));
            sizesSum += qty;
        }
        stock.setSizesData(sizes.isEmpty() ? null : ProductInventorySizesJson.serialize(sizes));
        if (anyUnsized) {
            // Mixed: preserve full ledger total (already set by replay, or recompute here).
            stock.setCurrentStock(runningTotal);
        } else {
            // Pure sized: current equals positive sizes sum.
            stock.setCurrentStock(sizesSum);
        }
        stock.setLastUpdatedAt(GuatemalaDateTime.now());
        log.info(
                "KIOSCO_REBUILD_SIZES stockId={} productId={} colorId={} mixed={} sizesTotal={} ledgerTotal={} currentStock={} sizes={}",
                stock.getId(), stock.getProductId(), stock.getColorId(), anyUnsized, sizesSum, runningTotal,
                safeInt(stock.getCurrentStock()), sizes.keySet());
    }

    private int movementSignedDelta(KioscoMovementEntity movement) {
        if (movement == null || movement.getMovementType() == null) {
            return safeInt(movement != null ? movement.getStockAfter() : 0)
                    - safeInt(movement != null ? movement.getStockBefore() : 0);
        }
        int qty = safeInt(movement.getQuantity());
        return switch (movement.getMovementType()) {
            case ENTRADA, TRASLADO_ENTRADA, DEVOLUCION_CLIENTE, ANULACION -> qty;
            case VENTA, DEVOLUCION_DEPOSITO, DEVOLUCION_A_CLIENTE, TRASLADO_SALIDA, MERMA -> -qty;
            case AJUSTE, CAMBIO -> safeInt(movement.getStockAfter()) - safeInt(movement.getStockBefore());
        };
    }

    /**
     * Inventario final vendible/reportable.
     * FOSS con desglose: max(current_stock, sum(sizes)) so mixed ledger totals
     * (unsized history) are not understated by sizes-only sums, while size-only
     * undercounts on current_stock are still corrected upward.
     */
    public int resolveInventarioFinal(KioscoStockEntity stock, ProductEntity product) {
        if (stock == null) {
            return 0;
        }
        int current = safeInt(stock.getCurrentStock());
        ProductEntity resolvedProduct = product;
        if (resolvedProduct == null && stock.getProductId() != null) {
            resolvedProduct = productRepository.findById(stock.getProductId()).orElse(null);
        }
        if (!CinchoProductUtils.isFossCinchoProduct(resolvedProduct)) {
            return current;
        }
        Map<String, BigDecimal> sizes = ProductInventorySizesJson.parse(stock.getSizesData());
        if (sizes.isEmpty()) {
            return current;
        }
        int sizesTotal = ProductInventorySizesJson.sum(sizes).setScale(0, RoundingMode.HALF_UP).intValue();
        return Math.max(current, sizesTotal);
    }

    /**
     * Cinchos FOSS: si sizes_data tiene más unidades que current_stock (entradas por talla sin reflejar
     * el total agregado), alinea current_stock hacia arriba con la suma de tallas.
     * Never lowers current_stock (mixed unsized+sized history may legitimately exceed sizes sum).
     */
    public void syncFossCurrentStockFromSizes(KioscoStockEntity stock) {
        if (stock == null || stock.getId() == null) {
            return;
        }
        ProductEntity product = stock.getProduct();
        if (product == null && stock.getProductId() != null) {
            product = productRepository.findById(stock.getProductId()).orElse(null);
        }
        if (!CinchoProductUtils.isFossCinchoProduct(product)) {
            return;
        }
        Map<String, BigDecimal> sizes = ProductInventorySizesJson.parse(stock.getSizesData());
        if (sizes.isEmpty()) {
            return;
        }
        int sizesTotal = ProductInventorySizesJson.sum(sizes).setScale(0, RoundingMode.HALF_UP).intValue();
        int current = safeInt(stock.getCurrentStock());
        if (sizesTotal > current) {
            stock.setCurrentStock(sizesTotal);
            stock.setLastUpdatedAt(GuatemalaDateTime.now());
            kioscoStockRepository.save(stock);
            log.info(
                    "KIOSCO_SYNC_FOSS_SIZES stockId={} productId={} colorId={} {} -> {}",
                    stock.getId(), stock.getProductId(), stock.getColorId(), current, sizesTotal);
        }
    }

    /**
     * Si sizes_data quedó inconsistente con current_stock, reconstruir desde movimientos
     * en lugar de borrar el desglose (borrar ocultaba tallas con entrada real en el kardex).
     */
    public void reconcileStaleSizeBreakdown(KioscoStockEntity stock) {
        if (stock == null || stock.getId() == null) {
            return;
        }
        Map<String, BigDecimal> sizes = ProductInventorySizesJson.parse(stock.getSizesData());
        if (sizes.isEmpty()) {
            // Sin desglose: intentar recuperar desde kardex con size_key.
            rebuildSizesDataFromMovements(stock, null);
            if (stock.getSizesData() != null) {
                kioscoStockRepository.save(stock);
            }
            return;
        }
        int totalFromSizes = ProductInventorySizesJson.sum(sizes).setScale(0, RoundingMode.HALF_UP).intValue();
        int current = safeInt(stock.getCurrentStock());
        if (totalFromSizes != current) {
            log.warn(
                    "KIOSCO_RECONCILE_SIZES_REBUILD stockId={} productId={} colorId={} sizesTotal={} currentStock={}",
                    stock.getId(), stock.getProductId(), stock.getColorId(), totalFromSizes, current);
            rebuildSizesDataFromMovements(stock, null);
            kioscoStockRepository.save(stock);
        }
    }

    private void validateSizeKeyRequired(
            Long locationId,
            Long productId,
            Long colorId,
            KioscoStockEntity stock,
            String sizeKey
    ) throws BusinessException {
        if (!ProductInventorySizesJson.normalizeKey(sizeKey).isEmpty()) {
            return;
        }
        ProductEntity product = stock != null && stock.getProduct() != null
                ? stock.getProduct()
                : productRepository.findById(productId).orElse(null);
        // Cincho real (tipo/nombre); empaques SUM- quedan fuera en isCinchoLineForProduction.
        boolean cinchoRequiresSize = CinchoProductUtils.isCinchoLineForProduction(product);
        boolean kioscoBreakdown = stock != null
                && ProductInventorySizesJson.hasNonEmptyBreakdown(stock.getSizesData());
        boolean legacyBreakdown = productInventoryLocationRepository
                .findByProductIdAndLocationIdAndColorId(productId, locationId, colorId)
                .map(ProductInventoryLocation::getSizesData)
                .map(ProductInventorySizesJson::hasNonEmptyBreakdown)
                .orElse(false);
        if (cinchoRequiresSize || kioscoBreakdown || legacyBreakdown) {
            throw new BusinessException("Indique la talla para esta operación de inventario kiosko.");
        }
    }

    private Map<String, BigDecimal> normalizeRealSizesMap(Map<String, Integer> realSizes) throws BusinessException {
        if (realSizes == null || realSizes.isEmpty()) {
            return null;
        }
        Map<String, BigDecimal> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : realSizes.entrySet()) {
            String key = ProductInventorySizesJson.normalizeKey(entry.getKey());
            if (key.isEmpty()) {
                continue;
            }
            Integer value = entry.getValue();
            if (value == null || value < 0) {
                throw new BusinessException("El conteo de talla " + key + " no puede ser negativo.");
            }
            normalized.put(key, BigDecimal.valueOf(value));
        }
        return normalized.isEmpty() ? null : normalized;
    }

    private void syncLegacyInventoryToTargetSizes(
            Long locationId,
            Long productId,
            Long colorId,
            Map<String, BigDecimal> targetSizes
    ) throws BusinessException, ResourceNotFoundException {
        BigDecimal total = ProductInventorySizesJson.sum(targetSizes);
        productInventoryService.createOrUpdateInventory(ProductInventoryLocationRequest.builder()
                .productId(productId)
                .locationId(locationId)
                .colorId(colorId)
                .quantity(total)
                .sizes(new LinkedHashMap<>(targetSizes))
                .build());
    }

    /** Fuerza inventario legacy al stock actual del módulo kiosco. */
    private void alignLegacyInventoryToKioscoStock(KioscoStockEntity stock)
            throws BusinessException, ResourceNotFoundException {
        if (stock == null) {
            return;
        }
        Map<String, BigDecimal> sizes = ProductInventorySizesJson.parse(stock.getSizesData());
        if (!sizes.isEmpty()) {
            syncLegacyInventoryToTargetSizes(
                    stock.getLocationId(),
                    stock.getProductId(),
                    stock.getColorId(),
                    sizes
            );
            return;
        }
        productInventoryService.createOrUpdateInventory(ProductInventoryLocationRequest.builder()
                .productId(stock.getProductId())
                .locationId(stock.getLocationId())
                .colorId(stock.getColorId())
                .quantity(BigDecimal.valueOf(safeInt(stock.getCurrentStock())))
                .build());
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class TrasladoResult {
        private Long referenceId;
        private KioscoStockResponse originStock;
        private KioscoStockResponse destinationStock;
        /** true si se agregaron ítems a una boleta de traslado ya existente. */
        private Boolean appended;
        private Integer lineCount;
        private String physicalSlipNumber;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class CambioResult {
        private KioscoStockResponse returnedStock;
        private KioscoStockResponse givenStock;
        private Long returnedMovementId;
        private Long givenMovementId;
    }
}
