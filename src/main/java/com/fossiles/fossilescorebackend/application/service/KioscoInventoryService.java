package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.KioscoConsolidatedReportResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoInventoryInitializeResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoKardexReportResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoMovementResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoStockResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.util.ProductAudienceCategory;
import com.fossiles.fossilescorebackend.application.util.ProductCinchoType;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.InventoryTransfer;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoMovementEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoMovementType;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioscoStockEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductInventoryKardex;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductShipmentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.UserEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.InventoryTransferRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoMovementRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioscoStockRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductInventoryKardexRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductShipmentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.UserRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
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
    private static final String REFERENCE_KIOSCO_INVENTORY = "KIOSCO_INVENTORY";
    private static final String ADMIN_MOVEMENT_MUTATION_KEY = "app.kiosco_movement_admin_mutation";

    private final KioscoStockRepository kioscoStockRepository;
    private final KioscoMovementRepository kioscoMovementRepository;
    private final LocationRepository locationRepository;
    private final ProductRepository productRepository;
    private final ColorRepository colorRepository;
    private final UserRepository userRepository;
    private final SecurityUtil securityUtil;
    private final ProductInventoryService productInventoryService;
    private final KioskInventoryGuard kioskInventoryGuard;
    private final ProductShipmentRepository productShipmentRepository;
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
        return registrarEntradaInternal(locationId, productId, colorId, quantity, referenceId, userId, true, null);
    }

    public KioscoStockResponse registrarVenta(
            Long locationId,
            Long productId,
            Long colorId,
            Integer quantity,
            Long invoiceId,
            Long userId
    ) throws BusinessException, ResourceNotFoundException {
        return registrarVentaInternal(locationId, productId, colorId, quantity, invoiceId, userId, true, null);
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
        String trimmedReason = safeTrim(reason);
        return applyStockMovement(
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
                physicalSlipNumber
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
                    physicalSlipNumber
            );
        }

        KioscoStockEntity stock = getOrCreateLockedStock(locationId, productId, colorId, resolvedUserId);
        int before = safeInt(stock.getCurrentStock());
        int after = before;
        saveMovement(stock, KioscoMovementType.DEVOLUCION_CLIENTE, quantity, before, after,
                originalInvoiceId, null, false, resolvedUserId, null, null, physicalSlipNumber);
        saveMovement(stock, KioscoMovementType.MERMA, quantity, before, after,
                originalInvoiceId, REASON_NON_RESELLABLE, false, resolvedUserId, null, null, physicalSlipNumber);
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
        return registrarTrasladoInternal(
                locationOriginId, locationDestinationId, productId, colorId, quantity, userId, true, physicalSlipNumber);
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
        int qty = normalizePositiveIntegerQuantity(quantity);
        return registrarTrasladoInternal(locationOriginId, locationDestinationId, productId, colorId, qty, userId, false, null);
    }

    private TrasladoResult registrarTrasladoInternal(
            Long locationOriginId,
            Long locationDestinationId,
            Long productId,
            Long colorId,
            Integer quantity,
            Long userId,
            boolean syncLegacy,
            String physicalSlipNumber
    ) throws BusinessException, ResourceNotFoundException {
        validatePhysicalSlipNumber(physicalSlipNumber, syncLegacy);
        validateQuantity(quantity);
        if (Objects.equals(locationOriginId, locationDestinationId)) {
            throw new BusinessException("El origen y el destino deben ser distintos.");
        }
        Long resolvedUserId = resolveUserIdRequired(userId);
        validateLocationIsKiosk(locationOriginId);
        validateLocationIsKiosk(locationDestinationId);
        validateProduct(productId);
        validateColor(colorId);

        Long transferReferenceId = generateTransferReferenceId();

        LocationEntity fromLocation = locationRepository.findById(locationOriginId).orElse(null);
        LocationEntity toLocation = locationRepository.findById(locationDestinationId).orElse(null);
        String trasladoReason = buildTransferReason(fromLocation, toLocation);

        KioscoStockResponse origin = applyStockMovement(
                locationOriginId,
                productId,
                colorId,
                quantity,
                transferReferenceId,
                locationOriginId,
                locationDestinationId,
                resolvedUserId,
                KioscoMovementType.TRASLADO_SALIDA,
                -quantity,
                true,
                trasladoReason,
                null,
                syncLegacy,
                physicalSlipNumber
        );

        KioscoStockResponse destination = applyStockMovement(
                locationDestinationId,
                productId,
                colorId,
                quantity,
                transferReferenceId,
                locationOriginId,
                locationDestinationId,
                resolvedUserId,
                KioscoMovementType.TRASLADO_ENTRADA,
                quantity,
                true,
                trasladoReason,
                null,
                syncLegacy,
                physicalSlipNumber
        );

        return TrasladoResult.builder()
                .referenceId(transferReferenceId)
                .originStock(origin)
                .destinationStock(destination)
                .build();
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
        Long resolvedUserId = resolveUserIdRequired(userId);
        validateLocationIsKiosk(locationId);
        validateProduct(returnedProductId);
        validateColor(returnedColorId);
        validateProduct(givenProductId);
        validateColor(givenColorId);

        String trimmedReason = safeTrim(reason);

        KioscoMovementWithStock returnedMovement = applyStockMovementWithMovement(
                locationId,
                returnedProductId,
                returnedColorId,
                quantity,
                referenceId,
                null,
                null,
                resolvedUserId,
                KioscoMovementType.CAMBIO,
                quantity,
                true,
                trimmedReason.isEmpty() ? null : trimmedReason,
                returnedSize,
                true,
                physicalSlipNumber
        );

        KioscoMovementWithStock givenMovement = applyStockMovementWithMovement(
                locationId,
                givenProductId,
                givenColorId,
                quantity,
                referenceId,
                null,
                null,
                resolvedUserId,
                KioscoMovementType.CAMBIO,
                -quantity,
                true,
                trimmedReason.isEmpty() ? null : trimmedReason,
                givenSize,
                true,
                physicalSlipNumber
        );

        verificarStockMinimo(locationId, givenProductId, givenColorId);

        return CambioResult.builder()
                .returnedStock(returnedMovement.stockResponse())
                .givenStock(givenMovement.stockResponse())
                .returnedMovementId(returnedMovement.movement().getId())
                .givenMovementId(givenMovement.movement().getId())
                .build();
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
                null,
                true
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
        if (realQuantity == null || realQuantity < 0) {
            throw new BusinessException("La cantidad real no puede ser negativa.");
        }
        if (safeTrim(reason).isEmpty()) {
            throw new BusinessException("El motivo del ajuste es obligatorio.");
        }

        Long resolvedUserId = resolveUserIdRequired(userId);
        KioscoStockEntity stock = getOrCreateLockedStock(locationId, productId, colorId, resolvedUserId);
        int before = safeInt(stock.getCurrentStock());
        int delta = realQuantity - before;
        int after = before + delta;
        if (after < 0) {
            throw new BusinessException("El ajuste resultaría en stock negativo.");
        }

        stock.setCurrentStock(after);
        stock.setUpdatedBy(resolvedUserId);
        stock.setLastUpdatedAt(LocalDateTime.now());
        KioscoStockEntity savedStock = kioscoStockRepository.save(stock);

        saveMovement(savedStock, KioscoMovementType.AJUSTE, Math.abs(delta), before, after,
                null, reason.trim(), true, resolvedUserId, null, null);

        syncLegacyInventory(locationId, productId, colorId, delta, null);
        if (delta < 0) {
            verificarStockMinimo(locationId, productId, colorId);
        }

        return toStockResponse(savedStock);
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

        return applyStockMovement(
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
                false
        );
    }

    public boolean hasShipmentReceiptLineApplied(Long locationId, Long shipmentId, String lineRef) {
        if (locationId == null || shipmentId == null || lineRef == null || lineRef.isBlank()) {
            return false;
        }
        return kioscoMovementRepository.existsShipmentReceiptLine(locationId, shipmentId, lineRef.trim());
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
        int qty = normalizePositiveIntegerQuantity(quantity);
        return registrarVentaInternal(locationId, productId, colorId, qty, invoiceId, userId, false, sizeKey);
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
        return kioscoStockRepository.findByLocationIdOrderByProductIdAscColorIdAsc(locationId).stream()
                .map(this::toStockResponse)
                .collect(Collectors.toList());
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
            List<KioscoStockEntity> stocks = kioscoStockRepository.findByLocationIdOrderByProductIdAscColorIdAsc(kiosk.getId());
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
                .generatedAt(LocalDateTime.now())
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
        if (from == null || to == null) {
            throw new BusinessException("Debes indicar el rango de fechas (from y to).");
        }
        if (from.isAfter(to)) {
            throw new BusinessException("La fecha inicial no puede ser posterior a la fecha final.");
        }
        validateLocationIsKiosk(locationId);

        LocalDateTime fromDt = from.atStartOfDay();
        LocalDateTime toDtExclusive = to.plusDays(1).atStartOfDay();

        Map<Long, Integer> initialBalanceByStockId = new LinkedHashMap<>();
        for (KioscoMovementEntity m : kioscoMovementRepository.findByLocationAndCreatedAtBeforeAsc(locationId, fromDt)) {
            if (m.getKioscoStockId() == null || !Boolean.TRUE.equals(m.getAffectsStock())) {
                continue;
            }
            int running = initialBalanceByStockId.getOrDefault(m.getKioscoStockId(), 0);
            running += movementSignedDelta(m);
            if (running < 0) {
                running = 0;
            }
            initialBalanceByStockId.put(m.getKioscoStockId(), running);
        }

        Map<Long, KardexAccumulator> accByStockId = new LinkedHashMap<>();
        for (KioscoMovementEntity m : kioscoMovementRepository.findByLocationAndCreatedAtBetween(locationId, fromDt, toDtExclusive)) {
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
        for (KioscoStockEntity stock : kioscoStockRepository.findByLocationIdOrderByProductIdAscColorIdAsc(locationId)) {
            int initial = initialBalanceByStockId.getOrDefault(stock.getId(), 0);
            KardexAccumulator acc = accByStockId.getOrDefault(stock.getId(), new KardexAccumulator());
            int finalBalance = acc.applyTo(initial);
            if (stock.getCurrentStock() != null) {
                finalBalance = safeInt(stock.getCurrentStock());
            }
            if (!includeZeroRows && initial == 0 && finalBalance == 0 && acc.isEmpty()) {
                continue;
            }
            ProductEntity product = stock.getProduct();
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
                    .inventarioFinal(finalBalance)
                    .build());
        }

        rows.sort(Comparator
                .comparing((KioscoKardexReportResponse.KioscoKardexRow r) -> String.valueOf(r.getProductCode()),
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                .thenComparing(r -> String.valueOf(r.getColorName()), Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        return rows;
    }

    /** Acumula deltas de movimientos del periodo por categoria de kardex kiosco. */
    private static final class KardexAccumulator {
        private int comprasAjustes;
        private int anulacionCompras;
        private int entradas;
        private int ventas;
        private int anulacionVenta;
        private int salida;

        void apply(KioscoMovementType type, int delta) {
            switch (type) {
                case AJUSTE -> {
                    if (delta > 0) {
                        comprasAjustes += delta;
                    } else {
                        anulacionCompras += -delta;
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
                case DEVOLUCION_DEPOSITO, TRASLADO_SALIDA, MERMA -> {
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
        int createdCount = 0;
        int existingCount = 0;

        for (LocationEntity kiosk : kiosks) {
            for (ProductEntity product : products) {
                if (product == null || product.getId() == null) {
                    continue;
                }
                boolean exists = kioscoStockRepository
                        .findByLocationIdAndProductIdAndColorId(kiosk.getId(), product.getId(), null)
                        .isPresent();
                if (exists) {
                    existingCount++;
                    continue;
                }
                kioscoStockRepository.save(KioscoStockEntity.builder()
                        .locationId(kiosk.getId())
                        .productId(product.getId())
                        .colorId(null)
                        .currentStock(0)
                        .minimumStock(0)
                        .createdBy(resolvedUserId)
                        .updatedBy(resolvedUserId)
                        .build());
                createdCount++;
            }
        }

        String scopeLabel = locationId != null ? "kiosko seleccionado" : "todos los kioskos";
        return KioscoInventoryInitializeResponse.builder()
                .message("Inventario kiosko inicializado para " + scopeLabel + ".")
                .kiosksProcessed(kiosks.size())
                .productsProcessed(products.size())
                .createdCount(createdCount)
                .existingCount(existingCount)
                .locationId(locationId)
                .build();
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
        return registrarEntradaInternal(locationId, productId, colorId, quantity, referenceId, userId, syncLegacy, sizeKey, null);
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
                syncLegacy
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
            String sizeKey
    ) throws BusinessException, ResourceNotFoundException {
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
                syncLegacy
        );
        verificarStockMinimo(locationId, productId, colorId);
        return response;
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
                physicalSlipNumber
        ).stockResponse();
    }

    private record KioscoMovementWithStock(KioscoStockResponse stockResponse, KioscoMovementEntity movement) {}

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
        validateQuantity(quantity);
        validateLocationIsKiosk(locationId);
        validateProduct(productId);
        validateColor(colorId);
        validateUser(userId);

        KioscoStockEntity stock = getOrCreateLockedStock(locationId, productId, colorId, userId);
        int before = safeInt(stock.getCurrentStock());
        int after = before;

        if (affectsStock) {
            after = applyStockDelta(stock, quantity, delta, sizeKey);
            stock.setUpdatedBy(userId);
            stock.setLastUpdatedAt(LocalDateTime.now());
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
                physicalSlipNumber
        );

        if (syncLegacy && affectsStock) {
            syncLegacyInventory(locationId, productId, colorId, delta, sizeKey);
        }

        return new KioscoMovementWithStock(toStockResponse(stock), movement);
    }

    private int applyStockDelta(KioscoStockEntity stock, int quantity, int delta, String sizeKey) throws BusinessException {
        Map<String, BigDecimal> sizesMap = ProductInventorySizesJson.parse(stock.getSizesData());
        boolean breakdown = !sizesMap.isEmpty();
        String normalizedSize = ProductInventorySizesJson.normalizeKey(sizeKey);
        BigDecimal qtyBd = BigDecimal.valueOf(quantity);

        if (breakdown || !normalizedSize.isEmpty()) {
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
            String physicalSlipNumber
    ) {
        String normalizedSlip = normalizePhysicalSlipNumber(physicalSlipNumber);
        KioscoMovementEntity movement = KioscoMovementEntity.builder()
                .kioscoStockId(stock.getId())
                .movementType(movementType)
                .quantity(quantity)
                .stockBefore(stockBefore)
                .stockAfter(stockAfter)
                .referenceId(referenceId)
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
        try {
            return kioscoStockRepository.findForUpdate(locationId, productId, colorId)
                    .orElseGet(() -> kioscoStockRepository.save(KioscoStockEntity.builder()
                            .locationId(locationId)
                            .productId(productId)
                            .colorId(colorId)
                            .currentStock(0)
                            .minimumStock(0)
                            .createdBy(userId)
                            .updatedBy(userId)
                            .build()));
        } catch (DataIntegrityViolationException ex) {
            return kioscoStockRepository.findForUpdate(locationId, productId, colorId)
                    .orElseThrow(() -> new BusinessException("No se pudo preparar el stock de kiosko para la operación.", ex));
        }
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

    private KioscoStockResponse toStockResponse(KioscoStockEntity entity) {
        LocationEntity location = entity.getLocation();
        ProductEntity product = entity.getProduct();
        ColorEntity color = entity.getColor();
        int current = safeInt(entity.getCurrentStock());
        int minimum = safeInt(entity.getMinimumStock());
        Map<String, BigDecimal> sizes = positiveSizesMap(entity.getSizesData());
        return KioscoStockResponse.builder()
                .id(entity.getId())
                .locationId(entity.getLocationId())
                .locationCode(location != null ? location.getCode() : null)
                .locationName(location != null ? location.getName() : null)
                .productId(entity.getProductId())
                .productCode(product != null ? product.getCode() : null)
                .productName(product != null ? product.getName() : null)
                .colorId(entity.getColorId())
                .colorName(color != null ? color.getName() : null)
                .currentStock(current)
                .sizes(sizes)
                .minimumStock(minimum)
                .lowStock(current <= minimum)
                .lastUpdatedAt(entity.getLastUpdatedAt())
                .build();
    }

    private KioscoMovementResponse toMovementResponse(KioscoMovementEntity entity) {
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
     * Habilita DELETE/UPDATE en kiosco_movement dentro de la transaccion actual
     * (requiere migration-kiosco-movement-admin-delete.sql).
     */
    public void enableAdminMovementMutation() {
        entityManager.createNativeQuery(
                        "SELECT set_config(:key, 'true', true)")
                .setParameter("key", ADMIN_MOVEMENT_MUTATION_KEY)
                .getSingleResult();
    }

    public void deleteAdminMovement(KioscoMovementEntity movement) {
        if (movement == null || movement.getId() == null) {
            return;
        }
        enableAdminMovementMutation();
        entityManager.createNativeQuery("DELETE FROM kiosco_movement WHERE id = :id")
                .setParameter("id", movement.getId())
                .executeUpdate();
        entityManager.flush();
    }

    public void trimAdminEntradaQuantity(KioscoMovementEntity movement, int newQuantity) {
        if (movement == null || movement.getId() == null || newQuantity <= 0) {
            return;
        }
        enableAdminMovementMutation();
        entityManager.createNativeQuery(
                        "UPDATE kiosco_movement SET quantity = :qty, stock_after = stock_before + :qty WHERE id = :id")
                .setParameter("qty", newQuantity)
                .setParameter("id", movement.getId())
                .executeUpdate();
        entityManager.flush();
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
    ) {
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
    public int pruneExcessShipmentEntradas(List<KioscoMovementEntity> movementsOrderedAsc, int expected) {
        EntradaPrunePlan plan = planPruneExcessShipmentEntradas(movementsOrderedAsc, expected);
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
            } else if ("DELETE_ENTRADA".equals(action.type())) {
                deleteAdminMovement(movement);
            }
        }
        return plan.removedCount();
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

    /**
     * Recalcula stock_before/stock_after y current_stock (requiere flag admin en la transaccion).
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
            running += delta;
            if (running < 0) {
                running = 0;
            }
            movement.setStockAfter(running);
            enableAdminMovementMutation();
            entityManager.createNativeQuery(
                            "UPDATE kiosco_movement SET stock_before = :before, stock_after = :after WHERE id = :id")
                    .setParameter("before", movement.getStockBefore())
                    .setParameter("after", movement.getStockAfter())
                    .setParameter("id", movement.getId())
                    .executeUpdate();
        }
        entityManager.flush();
        stock.setCurrentStock(running);
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
            running += movementSignedDelta(movement);
            if (running < 0) {
                running = 0;
            }
        }
        stock.setCurrentStock(running);
        kioscoStockRepository.save(stock);
        return 1;
    }

    private int movementSignedDelta(KioscoMovementEntity movement) {
        if (movement == null || movement.getMovementType() == null) {
            return safeInt(movement != null ? movement.getStockAfter() : 0)
                    - safeInt(movement != null ? movement.getStockBefore() : 0);
        }
        int qty = safeInt(movement.getQuantity());
        return switch (movement.getMovementType()) {
            case ENTRADA, TRASLADO_ENTRADA, DEVOLUCION_CLIENTE, ANULACION -> qty;
            case VENTA, DEVOLUCION_DEPOSITO, TRASLADO_SALIDA, MERMA -> -qty;
            case AJUSTE, CAMBIO -> safeInt(movement.getStockAfter()) - safeInt(movement.getStockBefore());
        };
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class TrasladoResult {
        private Long referenceId;
        private KioscoStockResponse originStock;
        private KioscoStockResponse destinationStock;
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
