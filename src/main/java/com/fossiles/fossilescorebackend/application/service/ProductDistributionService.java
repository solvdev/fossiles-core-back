package com.fossiles.fossilescorebackend.application.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fossiles.fossilescorebackend.application.dto.request.OpcShipmentGenerateRequest;
import com.fossiles.fossilescorebackend.application.dto.request.ConfirmReceiptRequest;
import com.fossiles.fossilescorebackend.application.dto.request.ProductDistributionRequest;
import com.fossiles.fossilescorebackend.application.dto.request.ProductShipmentRequest;
import com.fossiles.fossilescorebackend.application.dto.request.StandaloneInternalShipmentRequest;
import com.fossiles.fossilescorebackend.application.dto.request.StandaloneKioskShipmentRequest;
import com.fossiles.fossilescorebackend.application.dto.response.DispatchStockPreviewResponse;
import com.fossiles.fossilescorebackend.application.dto.response.DispatchStockShortageResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ProductDistributionResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ProductInventoryLocationResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ProductShipmentDetailResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ProductShipmentResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ShipmentReceiptInventoryAuditResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ShipmentReceiptRepairResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoShipmentReconcileResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioscoShipmentReconcilePreviewResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.util.KioskAccessHelper;
import com.fossiles.fossilescorebackend.application.util.ProductCinchoType;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.*;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class ProductDistributionService {

    private static final java.util.Set<String> TERMINAL_SHIPMENT_STATUSES =
            java.util.Set.of("SENT", "DELIVERED", "COMPLETED", "RECEIVED", "CANCELLED");

    private static final String DESTINO_PREFIX = "DESTINO:";
    private static final String DOCUMENT_DATE_TAG = "DOCUMENT_DATE:";
    private static final String OPV_PACKING_TAG = "__OPV_PACKING__:";
    /** Envío interno ENVI sin orden de producción (colaborador, salida PT/Devoluciones). */
    public static final String INTERNAL_ENVI_TAG = "INTERNAL_ENVI:1";
    public static final String REQUEST_TYPE_TAG = "REQUEST_TYPE:";
    private static final String DISCOUNT_PERCENT_TAG = "DISCOUNT_PERCENT:";
    private static final String DISCOUNT_AMOUNT_TAG = "DISCOUNT_AMOUNT:";
    private static final String APPLY_HALF_PRICE_TAG = "APPLY_HALF_PRICE:";
    private static final String COLABORADOR_PHONE_TAG = "COLABORADOR_PHONE:";
    private static final String COLABORADOR_TAX_TAG = "COLABORADOR_NIT:";

    private final ProductDistributionRepository distributionRepository;
    private final ProductShipmentRepository shipmentRepository;
    private final ProductShipmentDetailRepository shipmentDetailRepository;
    private final ProductRepository productRepository;
    private final LocationRepository locationRepository;
    private final ProductInventoryLocationRepository productInventoryLocationRepository;
    private final ProductInventoryKardexRepository productInventoryKardexRepository;
    private final InventoryLocationTypeRepository inventoryLocationTypeRepository;
    private final ProductInventoryService productInventoryService;
    private final KioscoInventoryService kioscoInventoryService;
    private final ProductionOrderRepository productionOrderRepository;
    private final ProductionOrderItemRepository productionOrderItemRepository;
    private final DocumentSeriesRepository documentSeriesRepository;
    private final OpiVendorShipmentNumberService opiVendorShipmentNumberService;
    private final OpvVendorShipmentNumberService opvVendorShipmentNumberService;
    private final CustomerRepository customerRepository;
    private final UserRepository userRepository;
    private final ColorRepository colorRepository;
    private final ProductCategoryRepository productCategoryRepository;
    private final MaterialRepository materialRepository;
    private final KioscoStockRepository kioscoStockRepository;
    private final KioscoMovementRepository kioscoMovementRepository;
    private final SecurityUtil securityUtil;
    private final ProductionOrderWarehouseUnitService productionOrderWarehouseUnitService;
    private final ProductionOrderPartialReleaseRepository partialReleaseRepository;
    private final ObjectMapper objectMapper;

    // ========== DISTRIBUTION ==========

    /**
     * Obtiene todas las distribuciones
     */
    public List<ProductDistributionResponse> getAllDistributions() {
        return distributionRepository.findAll().stream()
                .map(this::toDistributionResponse)
                .collect(Collectors.toList());
    }

    /**
     * Obtiene una distribución por ID
     */
    public ProductDistributionResponse getDistributionById(Long id) throws ResourceNotFoundException {
        ProductDistributionEntity entity = distributionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ProductDistribution", id));
        return toDistributionResponse(entity);
    }

    /**
     * Crea una nueva distribución
     */
    public ProductDistributionResponse createDistribution(ProductDistributionRequest request) {
        // Generar número de distribución automáticamente
        String distributionNumber = generateDistributionNumber();
        Long currentUserId = securityUtil.getCurrentUserId();
        
        ProductDistributionEntity entity = ProductDistributionEntity.builder()
                .distributionNumber(distributionNumber)
                .distributionDate(request.getDistributionDate())
                .status(request.getStatus() != null ? request.getStatus() : "DRAFT")
                .description(request.getDescription())
                .createdBy(currentUserId)
                .updatedBy(currentUserId)
                .build();
        
        ProductDistributionEntity saved = distributionRepository.save(entity);
        return toDistributionResponse(saved);
    }

    /**
     * Actualiza una distribución
     */
    public ProductDistributionResponse updateDistribution(Long id, ProductDistributionRequest request) 
            throws ResourceNotFoundException {
        ProductDistributionEntity entity = distributionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ProductDistribution", id));
        
        if (request.getDistributionDate() != null) {
            entity.setDistributionDate(request.getDistributionDate());
        }
        if (request.getDescription() != null) {
            entity.setDescription(request.getDescription());
        }
        if (request.getStatus() != null) {
            entity.setStatus(request.getStatus());
        }
        entity.setUpdatedBy(securityUtil.getCurrentUserId());
        
        ProductDistributionEntity saved = distributionRepository.save(entity);
        return toDistributionResponse(saved);
    }

    /**
     * Elimina una distribución (solo si está en DRAFT)
     */
    public void deleteDistribution(Long id) throws ResourceNotFoundException, BusinessException {
        ProductDistributionEntity entity = distributionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ProductDistribution", id));
        
        if (!"DRAFT".equals(entity.getStatus())) {
            throw new BusinessException("Solo se pueden eliminar distribuciones en estado DRAFT");
        }
        
        distributionRepository.delete(entity);
    }

    // ========== SHIPMENT ==========

    /**
     * Obtiene todos los envíos de una distribución
     */
    public List<ProductShipmentResponse> getShipmentsByDistribution(Long distributionId) {
        return shipmentRepository.findByDistributionId(distributionId).stream()
                .map(this::toShipmentResponse)
                .collect(Collectors.toList());
    }

    /**
     * Envíos guardados bajo la OP (sin distribución).
     */
    public List<ProductShipmentResponse> getShipmentsByProductionOrder(Long productionOrderId)
            throws ResourceNotFoundException {
        productionOrderRepository.findById(productionOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductionOrder", productionOrderId));
        return shipmentRepository.findByProductionOrderId(productionOrderId).stream()
                .map(this::toShipmentResponse)
                .collect(Collectors.toList());
    }

    /**
     * Vista unificada: envíos de la distribución vinculada (si existe) + envíos directos de la OP.
     */
    public List<ProductShipmentResponse> getShipmentsLinkedToProductionOrder(Long productionOrderId)
            throws ResourceNotFoundException {
        ProductionOrderEntity po = productionOrderRepository.findById(productionOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductionOrder", productionOrderId));
        Map<Long, ProductShipmentResponse> byId = new java.util.LinkedHashMap<>();
        if (po.getDistributionId() != null) {
            for (ProductShipmentResponse s : getShipmentsByDistribution(po.getDistributionId())) {
                byId.put(s.getId(), s);
            }
        }
        for (ProductShipmentResponse s : getShipmentsByProductionOrder(productionOrderId)) {
            byId.put(s.getId(), s);
        }
        return new java.util.ArrayList<>(byId.values());
    }

    /**
     * Crea o actualiza un envío ligado a una OP INTERNA / CLIENTE_KIOSKO / OPC (cinchos) sin distribución.
     */
    public ProductShipmentResponse createOrUpdateShipmentForProductionOrder(
            Long productionOrderId,
            ProductShipmentRequest request) throws ResourceNotFoundException, BusinessException {
        ProductionOrderEntity order = productionOrderRepository.findById(productionOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductionOrder", productionOrderId));
        assertOrderAllowsDirectShipments(order);
        clearVendorShipmentVoidFlag(order);

        String orderType = order.getOrderType() == null ? "" : order.getOrderType().trim().toUpperCase();
        boolean isInterna = "INTERNA".equals(orderType);
        boolean isCincho = isCinchoOrderType(orderType);
        Long reqLocationId = request.getLocationId();

        if (("CLIENTE_KIOSKO".equals(orderType) || "NORMAL".equals(orderType))
                && reqLocationId == null
                && !isLuisFelipeVendorOrder(order)) {
            throw new BusinessException("CLIENTE_KIOSKO (OPCK) y NORMAL (OPK) requieren kiosko destino.");
        }

        String destinationAddress = resolveDestinationAddress(request, order);
        if (isCincho && reqLocationId == null && destinationAddress.isBlank()) {
            throw new BusinessException("Las órdenes OPC requieren destino/dirección cuando no se indica kiosko.");
        }

        String persistedNotes = isCincho
                ? buildNotesWithDestination(request.getNotes(), destinationAddress)
                : request.getNotes();
        persistedNotes = mergeDocumentDateIntoNotes(persistedNotes, request.getDocumentDate());

        LocationEntity location = null;
        if (reqLocationId != null) {
            location = locationRepository.findById(reqLocationId)
                    .orElseThrow(() -> new ResourceNotFoundException("Location", reqLocationId));
        }

        List<ProductShipmentRequest.ProductShipmentDetailRequest> normalizedProducts =
                normalizeShipmentProducts(request.getProducts());
        List<ProductShipmentEntity> allShipmentsForLocation = reqLocationId == null
                ? shipmentRepository.findByProductionOrderIdAndLocationIdIsNullOrderByIdAsc(productionOrderId)
                : shipmentRepository.findByProductionOrderIdAndLocationIdOrderByIdAsc(productionOrderId, reqLocationId);

        ProductShipmentEntity targetShipment = null;
        if (request.getShipmentId() != null) {
            targetShipment = shipmentRepository.findById(request.getShipmentId())
                    .orElseThrow(() -> new ResourceNotFoundException("ProductShipment", request.getShipmentId()));
            if (!productionOrderId.equals(targetShipment.getProductionOrderId())) {
                throw new BusinessException("El envío no pertenece a esta orden de producción.");
            }
            if (targetShipment.getDistributionId() != null) {
                throw new BusinessException("Este envío pertenece a una distribución; use el flujo de distribución.");
            }
            if (!java.util.Objects.equals(reqLocationId, targetShipment.getLocationId())) {
                throw new BusinessException("El envío seleccionado no pertenece al destino indicado.");
            }
        }

        if (targetShipment != null) {
            assertShipmentProductsEditable(targetShipment);
            if (!normalizedProducts.isEmpty()) {
                updateShipmentProducts(targetShipment.getId(), normalizedProducts);
            } else {
                shipmentDetailRepository.deleteByShipmentId(targetShipment.getId());
            }
            targetShipment.setNotes(persistedNotes);
            targetShipment.setPackingItems(serializePackingItems(request.getPackingItems()));
            ProductShipmentEntity saved = shipmentRepository.save(targetShipment);
            return toShipmentResponse(saved);
        }

        Long partialReleaseId = request.getPartialReleaseId();
        List<ProductShipmentEntity> draftShipments = allShipmentsForLocation.stream()
                .filter(s -> "DRAFT".equalsIgnoreCase(s.getStatus()))
                .filter(s -> partialReleaseId == null
                        || java.util.Objects.equals(partialReleaseId, s.getPartialReleaseId()))
                .collect(Collectors.toList());
        for (ProductShipmentEntity draft : draftShipments) {
            shipmentDetailRepository.deleteByShipmentId(draft.getId());
            shipmentRepository.delete(draft);
        }

        String shipmentNumber;
        if (location != null) {
            shipmentNumber = generateShipmentNumber(location);
        } else if (isLuisFelipeVendorOrder(order)) {
            order = ensureOpvVendorShipmentNumberOnOrder(order);
            shipmentNumber = allocateLfCinchoPhysicalShipmentNumber(order);
        } else if (isCincho) {
            shipmentNumber = generateOpcShipmentNumber(order);
        } else {
            shipmentNumber = generateShipmentNumberForOpiDocument(order);
        }
        ProductShipmentEntity shipment = ProductShipmentEntity.builder()
                .distributionId(null)
                .productionOrderId(productionOrderId)
                .partialReleaseId(partialReleaseId)
                .shipmentNumber(shipmentNumber)
                .locationId(reqLocationId)
                .status("DRAFT")
                .notes(persistedNotes)
                .packingItems(serializePackingItems(request.getPackingItems()))
                .createdBy(securityUtil.getCurrentUserId())
                .updatedBy(securityUtil.getCurrentUserId())
                .build();
        ProductShipmentEntity saved;
        try {
            saved = shipmentRepository.save(shipment);
        } catch (DataIntegrityViolationException ex) {
            String msg = ex.getMessage() == null ? "" : ex.getMessage();
            if (msg.contains("location_id") && reqLocationId == null) {
                throw new BusinessException(
                        "No se puede guardar el envío sin kiosko: falta migración en base de datos. "
                                + "Ejecute: ALTER TABLE product_shipment ALTER COLUMN location_id DROP NOT NULL;");
            }
            throw ex;
        }
        if (!normalizedProducts.isEmpty()) {
            updateShipmentProducts(saved.getId(), normalizedProducts);
        }
        return toShipmentResponse(saved);
    }

    /**
     * Genera envío CONFIRMED desde ítems de la OP (solo tipos cincho / OPC).
     */
    public ProductShipmentResponse generateShipmentFromProductionOrder(
            Long productionOrderId,
            OpcShipmentGenerateRequest request) throws ResourceNotFoundException, BusinessException {
        ProductionOrderEntity order = productionOrderRepository.findById(productionOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductionOrder", productionOrderId));
        if (!isCinchoOrderType(order.getOrderType())) {
            throw new BusinessException("Solo órdenes OPC (CINCHOS, CINCHOS_FOSSILES, CINCHOS_MARCAS) pueden generar envío desde la OP.");
        }
        clearVendorShipmentVoidFlag(order);

        if (isLuisFelipeVendorOrder(order)) {
            order = ensureOpvVendorShipmentNumberOnOrder(order);
            assertNoDuplicateLfShipment(productionOrderId, request.getLocationId());
        }

        String destination = request.getDestinationAddress() == null ? "" : request.getDestinationAddress().trim();
        if (destination.isBlank()) {
            destination = resolveLfDefaultDestination(order);
        }
        if (destination.isBlank()) {
            throw new BusinessException("Destino / dirección es obligatorio.");
        }

        List<ProductShipmentRequest.ProductShipmentDetailRequest> products =
                buildShipmentProductsFromOrderItems(productionOrderId);
        if (products.isEmpty()) {
            throw new BusinessException("La orden no tiene productos con cantidad para enviar.");
        }

        List<ProductShipmentRequest.PackingItemRequest> packingItems = request.getPackingItems();
        if (packingItems == null || packingItems.isEmpty()) {
            packingItems = parsePackingItemsFromOrderObservations(order);
        }

        ProductShipmentRequest createRequest = ProductShipmentRequest.builder()
                .locationId(request.getLocationId())
                .destinationAddress(destination)
                .notes(request.getNotes())
                .documentDate(request.getDocumentDate())
                .products(products)
                .packingItems(packingItems)
                .build();

        ProductShipmentResponse draft = createOrUpdateShipmentForProductionOrder(productionOrderId, createRequest);
        return confirmShipmentDraft(draft.getId());
    }

    /**
     * Envío CONFIRMED desde liberación parcial (OPC / cinchos LF).
     */
    public ProductShipmentResponse generateShipmentFromPartialRelease(
            Long productionOrderId,
            Long partialReleaseId,
            List<ProductShipmentRequest.ProductShipmentDetailRequest> products,
            OpcShipmentGenerateRequest request) throws ResourceNotFoundException, BusinessException {
        ProductionOrderEntity order = productionOrderRepository.findById(productionOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductionOrder", productionOrderId));
        if (!isCinchoOrderType(order.getOrderType())) {
            throw new BusinessException("El envío rápido desde liberación para cinchos usa solo tipos OPC.");
        }
        if (products == null || products.isEmpty()) {
            throw new BusinessException("La liberación no tiene productos con cantidad.");
        }

        if (isLuisFelipeVendorOrder(order)) {
            order = ensureOpvVendorShipmentNumberOnOrder(order);
        }

        String destination = request.getDestinationAddress() == null ? "" : request.getDestinationAddress().trim();
        if (destination.isBlank()) {
            destination = resolveLfDefaultDestination(order);
        }
        if (destination.isBlank()) {
            throw new BusinessException("Destino / dirección es obligatorio.");
        }

        List<ProductShipmentRequest.PackingItemRequest> packingItems = request.getPackingItems();
        if (packingItems == null || packingItems.isEmpty()) {
            packingItems = parsePackingItemsFromOrderObservations(order);
        }

        ProductShipmentRequest createRequest = ProductShipmentRequest.builder()
                .locationId(request.getLocationId())
                .destinationAddress(destination)
                .notes(request.getNotes())
                .documentDate(request.getDocumentDate())
                .products(products)
                .packingItems(packingItems)
                .partialReleaseId(partialReleaseId)
                .build();

        ProductShipmentResponse draft = createOrUpdateShipmentForProductionOrder(productionOrderId, createRequest);
        return confirmShipmentDraft(draft.getId());
    }

    /**
     * Crea envío DRAFT para liberación parcial (OPV u otros LF sin atajo OPC).
     */
    public ProductShipmentResponse createOrUpdateShipmentForPartialRelease(
            Long productionOrderId,
            Long partialReleaseId,
            ProductShipmentRequest request) throws ResourceNotFoundException, BusinessException {
        if (request == null) {
            request = new ProductShipmentRequest();
        }
        request.setPartialReleaseId(partialReleaseId);
        return createOrUpdateShipmentForProductionOrder(productionOrderId, request);
    }

    /**
     * Pasa un envío de DRAFT a CONFIRMED (listo para enviar / salida de bodega PT).
     */
    public ProductShipmentResponse confirmShipmentDraft(Long shipmentId)
            throws ResourceNotFoundException, BusinessException {
        ProductShipmentEntity shipment = shipmentRepository.findById(shipmentId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductShipment", shipmentId));
        String currentStatus = shipment.getStatus() == null ? "" : shipment.getStatus().trim().toUpperCase();
        if (!"DRAFT".equals(currentStatus)) {
            throw new BusinessException("Solo se puede confirmar un envío en borrador. Estado actual: " + currentStatus);
        }
        List<ProductShipmentDetailEntity> details = shipmentDetailRepository.findByShipmentId(shipmentId);
        boolean hasPacking = shipment.getPackingItems() != null && !shipment.getPackingItems().trim().isEmpty();
        if (details.isEmpty() && !hasPacking) {
            throw new BusinessException("No se puede confirmar un envío sin productos ni empaques.");
        }
        shipment.setStatus("CONFIRMED");
        ProductShipmentEntity saved = shipmentRepository.save(shipment);
        if (saved.getProductionOrderId() != null) {
            // Trazabilidad opcional en Bodega PT: no bloquea confirmación (líneas vienen de OP / parciales).
            productionOrderWarehouseUnitService.markUnitsShippedForProductShipment(
                    saved.getProductionOrderId(),
                    shipmentId,
                    securityUtil.getCurrentUserId());
        }
        return toShipmentResponse(saved);
    }

    /**
     * Envío interno ENVI sin OP: asigna ENVI-nnnnn, descuenta Devoluciones/PT y deja documento en SENT.
     */
    @Transactional(rollbackFor = Exception.class)
    public ProductShipmentResponse dispatchStandaloneInternal(
            String recipientName,
            String recipientPhone,
            String recipientTaxId,
            String notes,
            String documentDate,
            String requestType,
            List<ProductShipmentRequest.ProductShipmentDetailRequest> products)
            throws BusinessException, ResourceNotFoundException {
        return dispatchStandaloneInternal(
                recipientName,
                recipientPhone,
                recipientTaxId,
                notes,
                documentDate,
                requestType,
                null,
                null,
                products);
    }

    @Transactional(rollbackFor = Exception.class)
    public ProductShipmentResponse dispatchStandaloneInternal(
            String recipientName,
            String recipientPhone,
            String recipientTaxId,
            String notes,
            String documentDate,
            String requestType,
            BigDecimal discountPercent,
            BigDecimal discountAmount,
            List<ProductShipmentRequest.ProductShipmentDetailRequest> products)
            throws BusinessException, ResourceNotFoundException {
        String recipient = recipientName == null ? "" : recipientName.trim();
        if (recipient.isBlank()) {
            throw new BusinessException("El nombre del colaborador es obligatorio.");
        }
        List<ProductShipmentRequest.ProductShipmentDetailRequest> normalizedProducts =
                normalizeShipmentProducts(products);
        if (normalizedProducts.isEmpty()) {
            throw new BusinessException("Debe incluir al menos un producto con cantidad.");
        }
        assertDispatchStockAvailable(normalizedProducts);

        String normalizedRequestType = normalizeInternalRequestType(requestType);
        ResolvedInternalDiscount discount = resolveInternalDiscount(
                normalizedRequestType, discountPercent, discountAmount);
        String shipmentNumber = opiVendorShipmentNumberService.nextNumber();
        String shipmentNotes = buildStandaloneInternalShipmentNotes(
                recipient,
                recipientPhone,
                recipientTaxId,
                notes,
                documentDate,
                normalizedRequestType,
                discount);
        ProductShipmentEntity shipment = ProductShipmentEntity.builder()
                .distributionId(null)
                .productionOrderId(null)
                .partialReleaseId(null)
                .shipmentNumber(shipmentNumber)
                .locationId(null)
                .status("DRAFT")
                .notes(shipmentNotes)
                .packingItems(null)
                .createdBy(securityUtil.getCurrentUserId())
                .updatedBy(securityUtil.getCurrentUserId())
                .build();
        ProductShipmentEntity saved = shipmentRepository.save(shipment);
        updateShipmentProducts(saved.getId(), normalizedProducts);
        confirmShipmentDraft(saved.getId());
        return sendShipment(saved.getId());
    }

    public void validateDispatchStock(List<ProductShipmentRequest.ProductShipmentDetailRequest> products)
            throws BusinessException, ResourceNotFoundException {
        List<ProductShipmentRequest.ProductShipmentDetailRequest> normalized = normalizeShipmentProducts(products);
        if (normalized.isEmpty()) {
            throw new BusinessException("Debe incluir al menos un producto con cantidad.");
        }
        assertDispatchStockAvailable(normalized);
    }

    public List<DispatchStockShortageResponse> computeDispatchStockShortages(
            List<ProductShipmentRequest.ProductShipmentDetailRequest> products)
            throws BusinessException, ResourceNotFoundException {
        List<ProductShipmentRequest.ProductShipmentDetailRequest> normalized = normalizeShipmentProducts(products);
        if (normalized.isEmpty()) {
            throw new BusinessException("Debe incluir al menos un producto con cantidad.");
        }
        return collectDispatchStockShortages(normalized);
    }

    /**
     * @deprecated Usar solicitud interna ({@link InternalShipmentRequestService}) y aprobación Contabilidad.
     */
    @Transactional(rollbackFor = Exception.class)
    public ProductShipmentResponse createAndDispatchStandaloneInternalShipment(
            StandaloneInternalShipmentRequest request) throws BusinessException, ResourceNotFoundException {
        String requestType = request.isApplyCollaboratorDiscount() ? "PLANILLA" : "PLANILLA";
        return dispatchStandaloneInternal(
                request.getRecipientName(),
                request.getRecipientPhone(),
                request.getRecipientTaxId(),
                request.getNotes(),
                request.getDocumentDate(),
                requestType,
                request.getProducts());
    }

    public List<ProductShipmentResponse> listStandaloneInternalShipments() {
        return shipmentRepository.findStandaloneInternalEnviShipments().stream()
                .map(this::toShipmentResponse)
                .collect(Collectors.toList());
    }

    /**
     * Envío directo a kiosko sin OP ni distribución.
     * Por defecto solo confirma el documento; el envío a tránsito se hace después con sendShipment.
     */
    @Transactional(rollbackFor = Exception.class)
    public ProductShipmentResponse createStandaloneKioskShipment(StandaloneKioskShipmentRequest request)
            throws BusinessException, ResourceNotFoundException {
        Long locationId = request.getLocationId();
        if (locationId == null) {
            throw new BusinessException("El kiosko destino es obligatorio.");
        }
        LocationEntity location = locationRepository.findById(locationId)
                .orElseThrow(() -> new ResourceNotFoundException("Location", locationId));
        assertKioskLocation(location);

        List<ProductShipmentRequest.ProductShipmentDetailRequest> normalizedProducts =
                normalizeShipmentProducts(request.getProducts());
        List<ProductShipmentRequest.PackingItemRequest> packingItems = request.getPackingItems();
        boolean hasProducts = !normalizedProducts.isEmpty();
        boolean hasPacking = packingItems != null && !packingItems.isEmpty();
        if (!hasProducts && !hasPacking) {
            throw new BusinessException("Debe incluir al menos un producto o empaque SUM- con cantidad.");
        }
        boolean confirmOnly = request.getConfirmOnly() == null || Boolean.TRUE.equals(request.getConfirmOnly());

        String persistedNotes = mergeDocumentDateIntoNotes(request.getNotes(), request.getDocumentDate());
        String shipmentNumber = generateShipmentNumber(location);
        Long currentUserId = securityUtil.getCurrentUserId();
        ProductShipmentEntity shipment = ProductShipmentEntity.builder()
                .distributionId(null)
                .productionOrderId(null)
                .partialReleaseId(null)
                .shipmentNumber(shipmentNumber)
                .locationId(locationId)
                .status("DRAFT")
                .notes(persistedNotes)
                .packingItems(serializePackingItems(packingItems))
                .createdBy(currentUserId)
                .updatedBy(currentUserId)
                .build();
        ProductShipmentEntity saved = shipmentRepository.save(shipment);
        if (hasProducts) {
            updateShipmentProducts(saved.getId(), normalizedProducts);
        }
        ProductShipmentResponse confirmed = confirmShipmentDraft(saved.getId());
        if (confirmOnly) {
            return confirmed;
        }
        return sendShipment(saved.getId());
    }

    public List<ProductShipmentResponse> listStandaloneKioskShipments() {
        return shipmentRepository.findStandaloneKioskShipments().stream()
                .map(this::toShipmentResponse)
                .collect(Collectors.toList());
    }

    private void assertKioskLocation(LocationEntity location) throws BusinessException {
        String category = location.getCategoria() == null ? "" : location.getCategoria().trim().toUpperCase();
        if (!category.contains("KIOSKO") && !"KIOSK".equals(category)) {
            throw new BusinessException("La ubicación destino debe ser un kiosko.");
        }
    }

    public List<ProductShipmentResponse> listAllInternalEnviShipments() {
        return shipmentRepository.findAllInternalEnviShipments().stream()
                .map(this::toShipmentResponse)
                .collect(Collectors.toList());
    }

    public DispatchStockPreviewResponse previewDispatchStock(Long productId, Long colorId, String size)
            throws BusinessException {
        if (productId == null) {
            throw new BusinessException("Producto requerido.");
        }
        String sizeLabel = normalizeSize(size);
        List<LocationEntity> warehouses = productInventoryService.getDispatchSourceWarehouses();
        BigDecimal total = productInventoryService.getAvailableQuantityAcrossDispatchWarehouses(
                productId, colorId, sizeLabel);
        List<DispatchStockPreviewResponse.DispatchStockBreakdownRow> breakdown = new ArrayList<>();
        for (LocationEntity loc : warehouses) {
            if (loc == null) {
                continue;
            }
            BigDecimal qty = productInventoryService.getAvailableQuantity(
                    productId, loc.getId(), colorId, sizeLabel);
            breakdown.add(DispatchStockPreviewResponse.DispatchStockBreakdownRow.builder()
                    .locationId(loc.getId())
                    .locationCode(loc.getCode())
                    .locationName(loc.getName())
                    .quantity(qty)
                    .build());
        }
        return DispatchStockPreviewResponse.builder()
                .availableTotal(total)
                .breakdown(breakdown)
                .build();
    }

    private void assertDispatchStockAvailable(
            List<ProductShipmentRequest.ProductShipmentDetailRequest> products) throws BusinessException {
        List<DispatchStockShortageResponse> shortages = collectDispatchStockShortages(products);
        if (!shortages.isEmpty()) {
            throw new BusinessException(formatDispatchStockShortageMessage(shortages));
        }
    }

    private List<DispatchStockShortageResponse> collectDispatchStockShortages(
            List<ProductShipmentRequest.ProductShipmentDetailRequest> products) throws BusinessException {
        List<DispatchStockShortageResponse> shortages = new ArrayList<>();
        for (ProductShipmentRequest.ProductShipmentDetailRequest detail : products) {
            BigDecimal stillNeeded = detail.getQuantity() != null ? detail.getQuantity() : BigDecimal.ZERO;
            if (stillNeeded.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            if (isPackagingProduct(detail.getProductId())) {
                continue;
            }
            String sizeLabel = normalizeSize(detail.getSize());
            BigDecimal availableTotal = productInventoryService.getAvailableQuantityAcrossDispatchWarehouses(
                    detail.getProductId(), detail.getColorId(), sizeLabel);
            if (availableTotal.compareTo(stillNeeded) < 0) {
                ProductEntity product = productRepository.findById(detail.getProductId()).orElse(null);
                String productCode = product != null ? product.getCode() : null;
                String productName = product != null ? product.getName() : "Producto";
                BigDecimal shortageQty = stillNeeded.subtract(availableTotal);
                shortages.add(DispatchStockShortageResponse.builder()
                        .productId(detail.getProductId())
                        .productCode(productCode)
                        .productName(productName)
                        .colorId(detail.getColorId())
                        .size(sizeLabel)
                        .requiredQuantity(stillNeeded)
                        .availableQuantity(availableTotal)
                        .shortageQuantity(shortageQty)
                        .build());
            }
        }
        return shortages;
    }

    private String formatDispatchStockShortageMessage(List<DispatchStockShortageResponse> shortages)
            throws BusinessException {
        List<String> lines = new ArrayList<>();
        List<LocationEntity> dispatchWarehouses = productInventoryService.getDispatchSourceWarehouses();
        for (DispatchStockShortageResponse shortage : shortages) {
            ProductEntity product = productRepository.findById(shortage.getProductId()).orElse(null);
            String productName = product != null
                    ? product.getCode() + " - " + product.getName()
                    : "Producto";
            String stockBreakdown = buildDispatchStockBreakdown(
                    shortage.getProductId(),
                    shortage.getColorId(),
                    shortage.getSize(),
                    dispatchWarehouses);
            lines.add(productName + ": disponible " + shortage.getAvailableQuantity()
                    + " (Devoluciones + Bodega PT: " + stockBreakdown + "), requerido "
                    + shortage.getRequiredQuantity());
        }
        return "Stock insuficiente en Devoluciones / Bodega PT:\n• " + String.join("\n• ", lines);
    }

    private String buildStandaloneInternalShipmentNotes(StandaloneInternalShipmentRequest request, String recipient) {
        ResolvedInternalDiscount discount = new ResolvedInternalDiscount(BigDecimal.valueOf(50), null, true);
        return buildStandaloneInternalShipmentNotes(
                recipient,
                request.getRecipientPhone(),
                request.getRecipientTaxId(),
                request.getNotes(),
                request.getDocumentDate(),
                "PLANILLA",
                discount);
    }

    private String buildStandaloneInternalShipmentNotes(
            String recipient,
            String recipientPhone,
            String recipientTaxId,
            String userNotes,
            String documentDate,
            String requestType,
            ResolvedInternalDiscount discount) {
        StringBuilder sb = new StringBuilder();
        sb.append(INTERNAL_ENVI_TAG).append("\n");
        sb.append(REQUEST_TYPE_TAG).append(normalizeInternalRequestType(requestType)).append("\n");
        sb.append(DESTINO_PREFIX).append(" Personal interno — ").append(recipient).append("\n");
        if (recipientPhone != null && !recipientPhone.isBlank()) {
            sb.append(COLABORADOR_PHONE_TAG).append(" ").append(recipientPhone.trim()).append("\n");
        }
        if (recipientTaxId != null && !recipientTaxId.isBlank()) {
            sb.append(COLABORADOR_TAX_TAG).append(" ").append(recipientTaxId.trim()).append("\n");
        }
        if (discount.discountPercent() != null) {
            sb.append(DISCOUNT_PERCENT_TAG)
                    .append(discount.discountPercent().stripTrailingZeros().toPlainString())
                    .append("\n");
        }
        if (discount.discountAmount() != null) {
            sb.append(DISCOUNT_AMOUNT_TAG)
                    .append(discount.discountAmount().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString())
                    .append("\n");
        }
        sb.append(APPLY_HALF_PRICE_TAG).append(discount.legacyHalfPrice() ? "1" : "0").append("\n");
        String merged = mergeDocumentDateIntoNotes(userNotes, documentDate);
        if (merged != null && !merged.isBlank()) {
            if (!sb.isEmpty() && !merged.startsWith("\n")) {
                sb.append("\n");
            }
            sb.append(merged);
        }
        return sb.toString().trim();
    }

    static String normalizeInternalRequestType(String requestType) {
        if (requestType == null || requestType.isBlank()) {
            return "PLANILLA";
        }
        String normalized = requestType.trim().toUpperCase(Locale.ROOT);
        if ("DEFECTOS".equals(normalized)) {
            return "DEFECTOS";
        }
        return "PLANILLA";
    }

    static boolean appliesHalfPriceForRequestType(String requestType) {
        return "PLANILLA".equals(requestType);
    }

    static void validateDefectosDiscount(String requestType, BigDecimal discountPercent, BigDecimal discountAmount)
            throws BusinessException {
        if (!"DEFECTOS".equals(normalizeInternalRequestType(requestType))) {
            return;
        }
        boolean hasPercent = discountPercent != null && discountPercent.compareTo(BigDecimal.ZERO) > 0;
        boolean hasAmount = discountAmount != null && discountAmount.compareTo(BigDecimal.ZERO) > 0;
        if (hasPercent == hasAmount) {
            throw new BusinessException(
                    "Para defectos indique descuento por porcentaje o por monto fijo unitario (Q), no ambos ni ninguno.");
        }
        if (hasPercent && discountPercent.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new BusinessException("El porcentaje de descuento no puede ser mayor a 100.");
        }
    }

    static ResolvedInternalDiscount resolveInternalDiscount(
            String requestType,
            BigDecimal discountPercent,
            BigDecimal discountAmount) throws BusinessException {
        String normalized = normalizeInternalRequestType(requestType);
        if ("PLANILLA".equals(normalized)) {
            return new ResolvedInternalDiscount(BigDecimal.valueOf(50), null, true);
        }
        validateDefectosDiscount(normalized, discountPercent, discountAmount);
        if (discountAmount != null && discountAmount.compareTo(BigDecimal.ZERO) > 0) {
            return new ResolvedInternalDiscount(null, discountAmount.setScale(2, java.math.RoundingMode.HALF_UP), false);
        }
        BigDecimal percent = discountPercent.setScale(2, java.math.RoundingMode.HALF_UP);
        boolean legacyHalf = percent.compareTo(BigDecimal.valueOf(50)) == 0;
        return new ResolvedInternalDiscount(percent, null, legacyHalf);
    }

    record ResolvedInternalDiscount(BigDecimal discountPercent, BigDecimal discountAmount, boolean legacyHalfPrice) {}

    static boolean isStandaloneInternalEnviShipment(ProductShipmentEntity shipment) {
        if (shipment == null) {
            return false;
        }
        if (shipment.getProductionOrderId() != null || shipment.getDistributionId() != null) {
            return false;
        }
        String shipmentNumber = shipment.getShipmentNumber() == null ? "" : shipment.getShipmentNumber().trim();
        if (shipmentNumber.toUpperCase(Locale.ROOT).startsWith("ENVI-")) {
            return true;
        }
        String notes = shipment.getNotes() == null ? "" : shipment.getNotes().toLowerCase(Locale.ROOT);
        return notes.contains("internal_envi") || notes.contains("personal interno");
    }

    private void assertOrderAllowsDirectShipments(ProductionOrderEntity order) throws BusinessException {
        String ot = order.getOrderType() == null ? "" : order.getOrderType().trim().toUpperCase();
        if (!"INTERNA".equals(ot) && !"CLIENTE_KIOSKO".equals(ot) && !"NORMAL".equals(ot) && !isCinchoOrderType(ot)
                && !isLuisFelipeVendorOrder(order)) {
            throw new BusinessException(
                    "Solo órdenes INTERNA (OPI), CLIENTE_KIOSKO (OPCK), NORMAL (OPK), OPC (cinchos) u OPV Luis Felipe permiten envíos sin distribución.");
        }
    }

    private static boolean isCinchoOrderType(String orderType) {
        if (orderType == null) {
            return false;
        }
        String t = orderType.trim().toUpperCase();
        return "CINCHOS".equals(t) || "CINCHOS_FOSSILES".equals(t) || "CINCHOS_MARCAS".equals(t);
    }

    private String resolveDestinationAddress(ProductShipmentRequest request, ProductionOrderEntity order) {
        if (request.getDestinationAddress() != null && !request.getDestinationAddress().isBlank()) {
            return request.getDestinationAddress().trim();
        }
        String fromNotes = extractDestinationFromNotes(request.getNotes());
        if (!fromNotes.isBlank()) {
            return fromNotes;
        }
        if (order != null && order.getCustomerName() != null && !order.getCustomerName().isBlank()) {
            return order.getCustomerName().trim();
        }
        return "";
    }

    private String buildNotesWithDestination(String baseNotes, String destinationAddress) {
        String dest = destinationAddress == null ? "" : destinationAddress.trim();
        StringBuilder sb = new StringBuilder();
        if (!dest.isBlank()) {
            sb.append(DESTINO_PREFIX).append(" ").append(dest);
        }
        String cleaned = stripDestinationLine(stripDocumentDateLine(baseNotes));
        if (cleaned != null && !cleaned.isBlank()) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(cleaned.trim());
        }
        return sb.toString();
    }

    private String mergeDocumentDateIntoNotes(String notes, String documentDate) {
        String cleaned = stripDocumentDateLine(notes);
        if (documentDate == null || documentDate.isBlank()) {
            return cleaned == null || cleaned.isBlank() ? null : cleaned;
        }
        String date = documentDate.trim();
        if (!date.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return cleaned == null || cleaned.isBlank() ? null : cleaned;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(DOCUMENT_DATE_TAG).append(date);
        if (cleaned != null && !cleaned.isBlank()) {
            sb.append("\n").append(cleaned.trim());
        }
        return sb.toString();
    }

    private String stripDocumentDateLine(String rawNotes) {
        if (rawNotes == null || rawNotes.isBlank()) {
            return "";
        }
        return java.util.Arrays.stream(rawNotes.split("\n"))
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .filter(line -> !line.toUpperCase(Locale.ROOT).startsWith(DOCUMENT_DATE_TAG))
                .collect(Collectors.joining("\n"));
    }

    private String extractDestinationFromNotes(String rawNotes) {
        if (rawNotes == null || rawNotes.isBlank()) {
            return "";
        }
        for (String line : rawNotes.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.toUpperCase().startsWith(DESTINO_PREFIX)) {
                return trimmed.substring(DESTINO_PREFIX.length()).trim();
            }
        }
        return "";
    }

    private String stripDestinationLine(String rawNotes) {
        if (rawNotes == null || rawNotes.isBlank()) {
            return "";
        }
        return java.util.Arrays.stream(rawNotes.split("\n"))
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .filter(line -> !line.toUpperCase().startsWith(DESTINO_PREFIX))
                .collect(Collectors.joining("\n"));
    }

    private List<ProductShipmentRequest.ProductShipmentDetailRequest> buildShipmentProductsFromOrderItems(
            Long productionOrderId) throws ResourceNotFoundException, BusinessException {
        List<ProductionOrderItemEntity> items = productionOrderItemRepository.findByProductionOrderId(productionOrderId);
        List<ProductShipmentRequest.ProductShipmentDetailRequest> lines = new ArrayList<>();
        for (ProductionOrderItemEntity item : items) {
            if (item.getProductId() == null) {
                continue;
            }
            boolean addedFromSizes = false;
            if (item.getSizesData() != null && !item.getSizesData().isBlank()) {
                try {
                    Map<String, Integer> sizes = objectMapper.readValue(
                            item.getSizesData(), new TypeReference<Map<String, Integer>>() {});
                    for (Map.Entry<String, Integer> entry : sizes.entrySet()) {
                        int qty = entry.getValue() == null ? 0 : entry.getValue();
                        if (qty <= 0) {
                            continue;
                        }
                        lines.add(ProductShipmentRequest.ProductShipmentDetailRequest.builder()
                                .productId(item.getProductId())
                                .colorId(item.getColorId())
                                .size(entry.getKey())
                                .quantity(BigDecimal.valueOf(qty))
                                .build());
                        addedFromSizes = true;
                    }
                } catch (Exception ignored) {
                    // malformed sizes JSON
                }
            }
            if (!addedFromSizes && item.getQuantity() != null && item.getQuantity() > 0) {
                lines.add(ProductShipmentRequest.ProductShipmentDetailRequest.builder()
                        .productId(item.getProductId())
                        .colorId(item.getColorId())
                        .size("")
                        .quantity(BigDecimal.valueOf(item.getQuantity()))
                        .build());
            }
        }
        return normalizeShipmentProducts(lines);
    }

    private String generateOpcShipmentNumber(ProductionOrderEntity order) {
        String opCode = order.getCode() == null ? "OPC" : order.getCode().trim().toUpperCase();
        opCode = opCode.replaceAll("[^A-Za-z0-9_-]", "_");
        int maxSequence = shipmentRepository.findByProductionOrderId(order.getId()).stream()
                .map(ProductShipmentEntity::getShipmentNumber)
                .mapToInt(this::extractTrailingSequence)
                .max()
                .orElse(0);
        int sequence = maxSequence + 1;
        return String.format("%s-ENV-%05d", opCode, sequence);
    }

    /** Destino mostrado cuando no hay location_id (OPI, OPC o ENVI interno sin OP). */
    private String virtualDestinationForDirectShipment(ProductionOrderEntity po, ProductShipmentEntity shipment) {
        if (po == null && shipment != null && isStandaloneInternalEnviShipment(shipment)) {
            String dest = extractDestinationFromNotes(shipment.getNotes());
            if (!dest.isBlank()) {
                return dest;
            }
            return "Personal interno / colaborador";
        }
        if (po == null) {
            return null;
        }
        String ot = po.getOrderType() == null ? "" : po.getOrderType().trim().toUpperCase();
        if (isCinchoOrderType(ot)) {
            String dest = shipment != null ? extractDestinationFromNotes(shipment.getNotes()) : "";
            if (!dest.isBlank()) {
                return dest;
            }
            if (po.getCustomerName() != null && !po.getCustomerName().isBlank()) {
                return po.getCustomerName().trim();
            }
            return "Destino OPC";
        }
        if (!"INTERNA".equals(ot)) {
            return null;
        }
        String n = po.getCustomerName();
        if (n != null && !n.isBlank()) {
            return "Personal interno — " + n.trim();
        }
        return "Personal interno / colaborador (sin kiosko)";
    }

    /**
     * Obtiene un envío por ID
     */
    public ProductShipmentResponse getShipmentById(Long id) throws ResourceNotFoundException {
        ProductShipmentEntity entity = shipmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ProductShipment", id));
        return toShipmentResponse(entity);
    }

    /**
     * Crea o actualiza un envío en una distribución
     */
    public ProductShipmentResponse createOrUpdateShipment(Long distributionId, ProductShipmentRequest request) 
            throws ResourceNotFoundException, BusinessException {
        // Validar que la distribución existe
        ProductDistributionEntity distribution = distributionRepository.findById(distributionId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductDistribution", distributionId));

        if (request.getLocationId() == null) {
            throw new BusinessException("La distribución requiere ubicación destino (kiosko).");
        }
        
        // Validar que el kiosko existe
        LocationEntity location = locationRepository.findById(request.getLocationId())
                .orElseThrow(() -> new ResourceNotFoundException("Location", request.getLocationId()));

        List<ProductShipmentRequest.ProductShipmentDetailRequest> normalizedProducts =
                normalizeShipmentProducts(request.getProducts());
        List<ProductShipmentEntity> allShipmentsForLocation =
                shipmentRepository.findByDistributionIdAndLocationIdOrderByIdAsc(distributionId, request.getLocationId());

        ProductShipmentEntity targetShipment = null;
        if (request.getShipmentId() != null) {
            targetShipment = shipmentRepository.findById(request.getShipmentId())
                    .orElseThrow(() -> new ResourceNotFoundException("ProductShipment", request.getShipmentId()));
            if (!distributionId.equals(targetShipment.getDistributionId())) {
                throw new BusinessException("El envío seleccionado no pertenece a la distribución.");
            }
            if (targetShipment.getProductionOrderId() != null) {
                throw new BusinessException("Este envío está ligado a una orden sin distribución.");
            }
            if (!request.getLocationId().equals(targetShipment.getLocationId())) {
                throw new BusinessException("El envío seleccionado no pertenece al kiosko indicado.");
            }
        }

        if (targetShipment != null) {
            assertShipmentProductsEditable(targetShipment);
            if (!normalizedProducts.isEmpty()) {
                updateShipmentProducts(targetShipment.getId(), normalizedProducts);
            } else {
                shipmentDetailRepository.deleteByShipmentId(targetShipment.getId());
            }
            targetShipment.setNotes(request.getNotes());
            targetShipment.setPackingItems(serializePackingItems(request.getPackingItems()));
            targetShipment.setUpdatedBy(securityUtil.getCurrentUserId());
            ProductShipmentEntity saved = shipmentRepository.save(targetShipment);
            return toShipmentResponse(saved);
        }

        // Flujo principal: para kiosko sin shipmentId específico, reemplaza envíos DRAFT del kiosko
        List<ProductShipmentEntity> draftShipments = allShipmentsForLocation.stream()
                .filter(s -> "DRAFT".equalsIgnoreCase(s.getStatus()))
                .collect(Collectors.toList());
        for (ProductShipmentEntity draft : draftShipments) {
            shipmentDetailRepository.deleteByShipmentId(draft.getId());
            shipmentRepository.delete(draft);
        }

        String shipmentNumber = generateShipmentNumber(location);
        Long currentUserId = securityUtil.getCurrentUserId();
        ProductShipmentEntity shipment = ProductShipmentEntity.builder()
                .distributionId(distributionId)
                .shipmentNumber(shipmentNumber)
                .locationId(request.getLocationId())
                .status("DRAFT")
                .notes(request.getNotes())
                .packingItems(serializePackingItems(request.getPackingItems()))
                .createdBy(currentUserId)
                .updatedBy(currentUserId)
                .build();
        ProductShipmentEntity saved = shipmentRepository.save(shipment);
        if (!normalizedProducts.isEmpty()) {
            updateShipmentProducts(saved.getId(), normalizedProducts);
        }
        return toShipmentResponse(saved);
    }

    /**
     * Actualiza solo empaques (packing_items) del envío, sin tocar productos ni inventario.
     */
    public ProductShipmentResponse updateShipmentPackingItems(
            Long shipmentId,
            List<ProductShipmentRequest.PackingItemRequest> packingItems)
            throws ResourceNotFoundException, BusinessException {
        ProductShipmentEntity shipment = shipmentRepository.findById(shipmentId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductShipment", shipmentId));
        shipment.setPackingItems(serializePackingItems(packingItems));
        return toShipmentResponse(shipmentRepository.save(shipment));
    }

    /**
     * Actualiza los productos de un envío (borrador, confirmado o en tránsito).
     * Si el envío ya salió de PT, revierte la salida anterior y aplica la nueva composición.
     */
    public ProductShipmentResponse updateShipmentProducts(Long shipmentId, List<ProductShipmentRequest.ProductShipmentDetailRequest> products) 
            throws ResourceNotFoundException, BusinessException {
        ProductShipmentEntity shipment = shipmentRepository.findByIdForUpdate(shipmentId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductShipment", shipmentId));
        assertShipmentProductsEditable(shipment);

        List<ProductShipmentRequest.ProductShipmentDetailRequest> normalized = normalizeShipmentProducts(products);
        if (normalized.isEmpty()) {
            throw new BusinessException("El envío debe tener al menos un producto con cantidad.");
        }

        String status = shipment.getStatus() == null ? "" : shipment.getStatus().trim().toUpperCase();
        boolean dispatchInventory = shipmentDispatchesFromPtWarehouses(shipment);
        boolean sentStatus = "SENT".equals(status);

        if (sentStatus && dispatchInventory) {
            reverseSentShipmentDispatchInventory(shipment);
            assertDispatchStockAvailable(normalized);
        }

        shipmentDetailRepository.deleteByShipmentId(shipmentId);
        try {
            for (ProductShipmentRequest.ProductShipmentDetailRequest line : normalized) {
                ProductShipmentDetailEntity detail = ProductShipmentDetailEntity.builder()
                        .shipmentId(shipmentId)
                        .productId(line.getProductId())
                        .colorId(line.getColorId())
                        .sizeLabel(normalizeSize(line.getSize()))
                        .quantity(line.getQuantity())
                        .build();
                shipmentDetailRepository.save(detail);
            }
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException(
                    "No se pudo guardar el envio por una restriccion de base de datos. " +
                    "Verifica que product_shipment_detail use unique (shipment_id, product_id, color_id, size_label) " +
                    "y no el unique antiguo por (shipment_id, product_id).");
        }

        if (sentStatus && dispatchInventory) {
            redispatchShipmentProducts(shipment, normalized);
        }

        shipment.setUpdatedBy(securityUtil.getCurrentUserId());
        shipmentRepository.save(shipment);
        return toShipmentResponse(shipment);
    }

    private void assertShipmentProductsEditable(ProductShipmentEntity shipment) throws BusinessException {
        String status = shipment.getStatus() == null ? "" : shipment.getStatus().trim().toUpperCase();
        if (!"DRAFT".equals(status) && !"CONFIRMED".equals(status) && !"SENT".equals(status)) {
            throw new BusinessException(
                    "No se pueden editar productos de un envío en estado " + status
                            + ". Solo borrador, confirmado o en tránsito (enviado).");
        }
    }

    private boolean shipmentDispatchesFromPtWarehouses(ProductShipmentEntity shipment) {
        Long shipmentLocationId = shipment.getLocationId();
        Optional<ProductionOrderEntity> linkedPoOpt = shipment.getProductionOrderId() == null
                ? Optional.empty()
                : productionOrderRepository.findById(shipment.getProductionOrderId());
        boolean standaloneInternalEnvi = isStandaloneInternalEnviShipment(shipment);
        boolean opiDocumentOnly = !standaloneInternalEnvi
                && shipmentLocationId == null
                && linkedPoOpt
                .map(po -> "INTERNA".equalsIgnoreCase(po.getOrderType() == null ? "" : po.getOrderType().trim()))
                .orElse(false);
        boolean opcPtOutOnly = shipmentLocationId == null
                && linkedPoOpt
                .map(po -> isCinchoOrderType(po.getOrderType())
                        && !extractDestinationFromNotes(shipment.getNotes()).isBlank())
                .orElse(false);
        boolean ptWarehouseOut = opcPtOutOnly || standaloneInternalEnvi;
        if (opiDocumentOnly || shipmentLocationId != null) {
            return false;
        }
        return ptWarehouseOut;
    }

    private void reverseSentShipmentDispatchInventory(ProductShipmentEntity shipment) throws BusinessException {
        List<com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductInventoryKardex> rows =
                productInventoryKardexRepository.findByReferenceTypeAndReferenceId("SHIPMENT", shipment.getId());
        for (com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductInventoryKardex row : rows) {
            if (!"SHIPMENT".equalsIgnoreCase(row.getMovementType())) {
                continue;
            }
            BigDecimal qty = row.getQuantity();
            if (qty == null || qty.compareTo(BigDecimal.ZERO) >= 0) {
                continue;
            }
            BigDecimal restore = qty.abs();
            try {
                productInventoryService.incrementInventory(
                        row.getProductId(),
                        row.getLocationId(),
                        row.getColorId(),
                        restore,
                        null,
                        "SHIPMENT",
                        shipment.getId(),
                        shipment.getShipmentNumber(),
                        "Reversión por edición de envío en tránsito",
                        null);
            } catch (ResourceNotFoundException e) {
                throw new BusinessException("No se pudo revertir inventario del envío: " + e.getMessage());
            }
        }
    }

    private void redispatchShipmentProducts(
            ProductShipmentEntity shipment,
            List<ProductShipmentRequest.ProductShipmentDetailRequest> lines) throws BusinessException {
        LocationEntity targetLocation = shipment.getLocationId() == null
                ? null
                : locationRepository.findById(shipment.getLocationId()).orElse(null);
        String destinationLabel = targetLocation != null
                ? targetLocation.getName()
                : extractDestinationFromNotes(shipment.getNotes());
        if (destinationLabel == null || destinationLabel.isBlank()) {
            destinationLabel = "kiosko";
        }

        for (ProductShipmentRequest.ProductShipmentDetailRequest detail : lines) {
            BigDecimal qty = detail.getQuantity() != null ? detail.getQuantity() : BigDecimal.ZERO;
            if (qty.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            if (isPackagingProduct(detail.getProductId())) {
                continue;
            }
            productInventoryService.decrementFromDispatchWarehouses(
                    detail.getProductId(),
                    detail.getColorId(),
                    normalizeSize(detail.getSize()),
                    qty,
                    "SHIPMENT",
                    shipment.getId(),
                    shipment.getShipmentNumber(),
                    "Reenvío tras edición hacia " + destinationLabel,
                    "SHIPMENT_REDISPATCH");
        }
    }

    /**
     * Elimina un envío (solo borrador).
     */
    public void deleteShipment(Long id) throws ResourceNotFoundException, BusinessException {
        ProductShipmentEntity entity = shipmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ProductShipment", id));
        String status = entity.getStatus() == null ? "" : entity.getStatus().trim().toUpperCase();
        if (!"DRAFT".equals(status)) {
            throw new BusinessException(
                    "Solo se puede eliminar un envío en borrador. Para anular envíos confirmados use la acción de anulación.");
        }
        shipmentDetailRepository.deleteByShipmentId(id);
        shipmentRepository.delete(entity);
    }

    /**
     * Revierte un envío en tránsito (SENT) a confirmado y devuelve el inventario a PT/Devoluciones.
     * No aplica si el kiosko ya recibió el envío (DELIVERED u otros estados finales).
     */
    public ProductShipmentResponse revertSentShipment(Long shipmentId)
            throws ResourceNotFoundException, BusinessException {
        ProductShipmentEntity shipment = shipmentRepository.findByIdForUpdate(shipmentId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductShipment", shipmentId));
        String currentStatus = shipment.getStatus() == null ? "" : shipment.getStatus().trim().toUpperCase();
        if (!"SENT".equals(currentStatus)) {
            throw new BusinessException(
                    "Solo se puede regresar a bodega un envío en tránsito (SENT). Estado actual: " + currentStatus);
        }

        if (shipmentDispatchesFromPtWarehouses(shipment)) {
            reverseSentShipmentDispatchInventory(shipment);
        }

        shipment.setStatus("CONFIRMED");
        shipment.setSentAt(null);
        shipment.setSentBy(null);
        shipment.setUpdatedBy(securityUtil.getCurrentUserId());
        ProductShipmentEntity saved = shipmentRepository.save(shipment);

        if (saved.getDistributionId() != null) {
            distributionRepository.findById(saved.getDistributionId()).ifPresent(dist -> {
                if ("DISPATCHED".equalsIgnoreCase(String.valueOf(dist.getStatus()))) {
                    dist.setStatus("IN_PROGRESS");
                    distributionRepository.save(dist);
                }
            });
        }

        return toShipmentResponse(saved);
    }

    /**
     * Anula un envío antes de salida (DRAFT o CONFIRMED). No revierte inventario PT/kiosko enviado.
     */
    public ProductShipmentResponse cancelShipment(Long shipmentId)
            throws ResourceNotFoundException, BusinessException {
        ProductShipmentEntity shipment = shipmentRepository.findByIdForUpdate(shipmentId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductShipment", shipmentId));
        String currentStatus = shipment.getStatus() == null ? "" : shipment.getStatus().trim().toUpperCase();
        if ("CANCELLED".equals(currentStatus)) {
            return toShipmentResponse(shipment);
        }
        if (!"DRAFT".equals(currentStatus) && !"CONFIRMED".equals(currentStatus)) {
            throw new BusinessException(
                    "Solo se puede anular un envío en borrador o confirmado (antes de enviar). Estado actual: "
                            + currentStatus);
        }
        boolean wasConfirmed = "CONFIRMED".equals(currentStatus);
        shipment.setStatus("CANCELLED");
        shipment.setUpdatedBy(securityUtil.getCurrentUserId());
        ProductShipmentEntity saved = shipmentRepository.save(shipment);

        if (wasConfirmed && saved.getProductionOrderId() != null) {
            productionOrderWarehouseUnitService.clearUnitsShippedForProductShipment(
                    saved.getProductionOrderId(), shipmentId);
        }
        if (saved.getPartialReleaseId() != null) {
            partialReleaseRepository.findById(saved.getPartialReleaseId()).ifPresent(release -> {
                if ("SHIPPED".equalsIgnoreCase(release.getStatus())) {
                    release.setStatus("CONFIRMED");
                    release.setUpdatedBy(securityUtil.getCurrentUserId());
                    partialReleaseRepository.save(release);
                }
            });
        }
        return toShipmentResponse(saved);
    }

    /**
     * Anula el documento de envío OPV/OPI cuando no hay envío activo en product_shipment.
     */
    public ProductionOrderEntity voidVendorShipmentDocument(Long productionOrderId)
            throws ResourceNotFoundException, BusinessException {
        ProductionOrderEntity order = productionOrderRepository.findById(productionOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductionOrder", productionOrderId));
        if (!allowsVoidVendorShipmentDocument(order)) {
            throw new BusinessException(
                    "Solo órdenes OPV (Luis Felipe) u OPI (INTERNA) permiten anular el documento de envío sin fila en sistema.");
        }
        if (hasActiveProductShipmentForOrder(productionOrderId)) {
            throw new BusinessException(
                    "La orden tiene un envío activo. Anule el envío en Preparar envíos o distribución antes de anular el documento.");
        }
        if (order.getVendorShipmentVoidedAt() != null) {
            return order;
        }
        order.setVendorShipmentVoidedAt(LocalDateTime.now());
        order.setVendorShipmentVoidedBy(securityUtil.getCurrentUserId());
        order.setUpdatedBy(securityUtil.getCurrentUserId());
        return productionOrderRepository.save(order);
    }

    private void clearVendorShipmentVoidFlag(ProductionOrderEntity order) {
        if (order == null || order.getVendorShipmentVoidedAt() == null) {
            return;
        }
        order.setVendorShipmentVoidedAt(null);
        order.setVendorShipmentVoidedBy(null);
        order.setUpdatedBy(securityUtil.getCurrentUserId());
        productionOrderRepository.save(order);
    }

    private boolean allowsVoidVendorShipmentDocument(ProductionOrderEntity order) {
        if (order == null) {
            return false;
        }
        String orderType = order.getOrderType() == null ? "" : order.getOrderType().trim().toUpperCase();
        if ("INTERNA".equals(orderType)) {
            return true;
        }
        return isLuisFelipeVendorOrder(order) && !isCinchoOrderType(orderType);
    }

    private boolean hasActiveProductShipmentForOrder(Long productionOrderId) {
        if (productionOrderId == null) {
            return false;
        }
        return shipmentRepository.findByProductionOrderId(productionOrderId).stream()
                .anyMatch(s -> {
                    String st = s.getStatus() == null ? "" : s.getStatus().trim().toUpperCase();
                    return !st.isEmpty() && !"DRAFT".equals(st) && !"CANCELLED".equals(st);
                });
    }

    // ========== COMPLETE DISTRIBUTION ==========

    /**
     * Finaliza una distribución y opcionalmente genera una orden de producción con productos agregados.
     * NO modifica inventarios directamente; los movimientos se harán al procesar envíos / orden.
     */
    public ProductDistributionResponse completeDistribution(Long distributionId, boolean generateProductionOrder)
            throws ResourceNotFoundException, BusinessException {
        ProductDistributionEntity distribution = distributionRepository.findById(distributionId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductDistribution", distributionId));
        
        if ("COMPLETED".equals(distribution.getStatus())) {
            throw new BusinessException("La distribución ya está completada");
        }
        
        // Verificar si ya existe una OP vinculada.
        Optional<ProductionOrderEntity> existingOrder = productionOrderRepository.findByDistributionId(distributionId);
        if (existingOrder.isPresent() && generateProductionOrder) {
            throw new BusinessException("Esta distribución ya tiene una orden de producción generada: " + existingOrder.get().getCode());
        }
        
        // Obtener todos los envíos de la distribución
        List<ProductShipmentEntity> shipments = shipmentRepository.findByDistributionId(distributionId);
        
        if (shipments.isEmpty()) {
            throw new BusinessException("No se puede completar una distribución sin envíos");
        }
        
        if (generateProductionOrder) {
            // Agregar productos de todos los envíos (sumar cantidades por producto+color)
            // Key: "productId:colorId" (colorId puede ser null → "productId:null")
            Map<String, BigDecimal> aggregatedProducts = new HashMap<>();
            Map<String, Long> keyToProductId = new HashMap<>();
            Map<String, Long> keyToColorId = new HashMap<>();

            for (ProductShipmentEntity shipment : shipments) {
                List<ProductShipmentDetailEntity> details = shipmentDetailRepository.findByShipmentId(shipment.getId());
                for (ProductShipmentDetailEntity detail : details) {
                    if (isPackagingProduct(detail.getProductId())) {
                        continue;
                    }
                    String key = detail.getProductId() + ":" + detail.getColorId();
                    aggregatedProducts.merge(key, detail.getQuantity(), BigDecimal::add);
                    keyToProductId.put(key, detail.getProductId());
                    keyToColorId.put(key, detail.getColorId());
                }
            }

            if (aggregatedProducts.isEmpty()) {
                throw new BusinessException("No se puede completar una distribución sin productos en los envíos");
            }

            // Generar código de orden de producción
            String orderCode = generateProductionOrderCode();

            // Crear la orden de producción vinculada a la distribución
            ProductionOrderEntity productionOrder = ProductionOrderEntity.builder()
                    .code(orderCode)
                    .orderType("DISTRIBUTION")
                    .distributionId(distributionId)
                    .observations("Orden generada automáticamente desde Distribución #" + distribution.getDistributionNumber())
                    .status("PENDING")
                    .build();
            productionOrder = productionOrderRepository.save(productionOrder);

            // Crear items de la orden con los productos agregados (por producto+color)
            for (Map.Entry<String, BigDecimal> entry : aggregatedProducts.entrySet()) {
                String key = entry.getKey();
                Long productId = keyToProductId.get(key);
                Long colorId = keyToColorId.get(key);

                ProductionOrderItemEntity item = ProductionOrderItemEntity.builder()
                        .productionOrderId(productionOrder.getId())
                        .productId(productId)
                        .colorId(colorId)
                        .quantity(entry.getValue().intValue())
                        .observations("Cantidad total de distribución #" + distribution.getDistributionNumber())
                        .build();
                productionOrderItemRepository.save(item);
            }
        }
        
        // Actualizar estado de los envíos
        for (ProductShipmentEntity shipment : shipments) {
            shipment.setStatus("CONFIRMED");
            shipmentRepository.save(shipment);
        }
        
        // Actualizar estado de la distribución
        distribution.setStatus("COMPLETED");
        ProductDistributionEntity saved = distributionRepository.save(distribution);
        
        return toDistributionResponse(saved);
    }
    
    /**
     * Genera automáticamente el código de orden de producción usando DocumentSeries
     */
    private String generateProductionOrderCode() throws BusinessException {
        String documentType = "PRODUCTION_ORDER";
        String series = "PO";
        
        DocumentSeriesEntity seriesEntity = documentSeriesRepository
                .findByDocumentTypeAndSeriesForUpdate(documentType, series)
                .orElseGet(() -> {
                    DocumentSeriesEntity newSeries = DocumentSeriesEntity.builder()
                            .documentType(documentType)
                            .series(series)
                            .currentCorrelative(0L)
                            .status("active")
                            .description("Serie automática para órdenes de producción")
                            .build();
                    return documentSeriesRepository.save(newSeries);
                });
        
        documentSeriesRepository.incrementCorrelative(seriesEntity.getId());
        seriesEntity.setCurrentCorrelative(seriesEntity.getCurrentCorrelative() + 1);
        documentSeriesRepository.save(seriesEntity);
        
        return String.format("%s-%05d", series, seriesEntity.getCurrentCorrelative());
    }

    // ========== PREPARE & SEND SHIPMENTS ===========

    /**
     * Marca un envío como SENT (en tránsito).
     * <p>
     * Los empaques SUM- ({@code packing_items}) no rebajan inventario PT ni materiales al enviar.
     * La salida de materiales SUM- ocurre en entrega de materiales (kardex / consumo BOM).
     * Al recibir el envío en kiosko, los empaques cargan stock kiosco vía {@link #confirmReceipt}.
     */
    public ProductShipmentResponse sendShipment(Long shipmentId) throws ResourceNotFoundException, BusinessException {
        ProductShipmentEntity shipment = shipmentRepository.findByIdForUpdate(shipmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Shipment", shipmentId));
        String currentStatus = shipment.getStatus() == null ? "" : shipment.getStatus().trim().toUpperCase();

        if (TERMINAL_SHIPMENT_STATUSES.contains(currentStatus)) {
            throw new BusinessException("El envío ya fue procesado y no puede prepararse de nuevo. Estado actual: " + currentStatus);
        }

        if (!"CONFIRMED".equals(currentStatus)) {
            throw new BusinessException("El envío debe estar confirmado para poder enviarse. Estado actual: " + currentStatus);
        }

        Long shipmentLocationId = shipment.getLocationId();
        Optional<ProductionOrderEntity> linkedPoOpt = shipment.getProductionOrderId() == null
                ? Optional.empty()
                : productionOrderRepository.findById(shipment.getProductionOrderId());
        boolean standaloneInternalEnvi = isStandaloneInternalEnviShipment(shipment);
        boolean opiDocumentOnly = !standaloneInternalEnvi
                && shipmentLocationId == null
                && linkedPoOpt
                .map(po -> "INTERNA".equalsIgnoreCase(po.getOrderType() == null ? "" : po.getOrderType().trim()))
                .orElse(false);
        boolean opcPtOutOnly = shipmentLocationId == null
                && linkedPoOpt
                .map(po -> isCinchoOrderType(po.getOrderType())
                        && !extractDestinationFromNotes(shipment.getNotes()).isBlank())
                .orElse(false);
        boolean ptWarehouseOut = opcPtOutOnly || standaloneInternalEnvi;

        if (opiDocumentOnly) {
            shipment.setStatus("SENT");
            shipment.setSentAt(LocalDateTime.now());
            shipment.setSentBy(securityUtil.getCurrentUserId());
            return toShipmentResponse(shipmentRepository.save(shipment));
        }

        if (shipmentLocationId == null && !ptWarehouseOut) {
            throw new BusinessException("El envío no tiene destino (kiosko); no se puede registrar salida de PT.");
        }

        List<LocationEntity> dispatchWarehouses = productInventoryService.getDispatchSourceWarehouses();
        String opcDestinationLabel = opcPtOutOnly || standaloneInternalEnvi
                ? extractDestinationFromNotes(shipment.getNotes())
                : null;
        List<ProductShipmentDetailEntity> details = shipmentDetailRepository.findByShipmentId(shipmentId);
        if (details.isEmpty()) {
            throw new BusinessException("No se puede enviar un envío sin productos.");
        }

        // Envío a kiosko: tránsito documental; la recepción en POS carga inventario del kiosko.
        if (shipmentLocationId != null) {
            return transitionConfirmedShipmentToSent(shipmentId, shipment);
        }

        String destinationLabel = opcDestinationLabel != null && !opcDestinationLabel.isBlank()
                ? opcDestinationLabel
                : "destino";

        // Pre-validar stock: Devoluciones primero, luego Bodega PT (total combinado).
        List<String> shortages = new java.util.ArrayList<>();
        for (ProductShipmentDetailEntity detail : details) {
            BigDecimal qtyToSend = detail.getQuantity() != null ? detail.getQuantity() : BigDecimal.ZERO;
            if (qtyToSend.compareTo(BigDecimal.ZERO) <= 0) continue;
            if (isPackagingProduct(detail.getProductId())) continue;

            String sizeLabel = detail.getSizeLabel();
            BigDecimal alreadyOut = productInventoryService.getConsumedQuantityForReference(
                    "SHIPMENT", shipment.getId(), "SHIPMENT", detail.getProductId(), detail.getColorId());
            BigDecimal stillNeeded = qtyToSend.subtract(alreadyOut);
            if (stillNeeded.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            BigDecimal availableTotal = productInventoryService.getAvailableQuantityAcrossDispatchWarehouses(
                    detail.getProductId(), detail.getColorId(), sizeLabel);
            if (availableTotal.compareTo(stillNeeded) < 0) {
                ProductEntity product = productRepository.findById(detail.getProductId()).orElse(null);
                String productName = product != null ? product.getCode() + " - " + product.getName() : "Producto #" + detail.getProductId();
                String colorName = "";
                if (detail.getColorId() != null) {
                    ColorEntity color = colorRepository.findById(detail.getColorId()).orElse(null);
                    colorName = color != null ? " (" + color.getName() + ")" : " (Color #" + detail.getColorId() + ")";
                }
                String stockBreakdown = buildDispatchStockBreakdown(
                        detail.getProductId(), detail.getColorId(), sizeLabel, dispatchWarehouses);
                shortages.add(productName + colorName + ": disponible " + availableTotal
                        + " (Devoluciones + Bodega PT: " + stockBreakdown + "), requerido " + stillNeeded);
            }
        }
        if (!shortages.isEmpty()) {
            throw new BusinessException("Stock insuficiente en Devoluciones / Bodega PT para enviar:\n• "
                    + String.join("\n• ", shortages));
        }

        for (ProductShipmentDetailEntity detail : details) {
            BigDecimal qtyToSend = detail.getQuantity() != null ? detail.getQuantity() : BigDecimal.ZERO;
            if (qtyToSend.compareTo(BigDecimal.ZERO) <= 0) continue;
            if (isPackagingProduct(detail.getProductId())) continue;

            productInventoryService.decrementFromDispatchWarehouses(
                    detail.getProductId(),
                    detail.getColorId(),
                    detail.getSizeLabel(),
                    qtyToSend,
                    "SHIPMENT",
                    shipment.getId(),
                    shipment.getShipmentNumber(),
                    "Salida a envio en transito hacia " + destinationLabel,
                    "SHIPMENT");
        }

        return transitionConfirmedShipmentToSent(shipmentId, shipment);
    }

    private ProductShipmentResponse transitionConfirmedShipmentToSent(
            Long shipmentId,
            ProductShipmentEntity shipment) throws BusinessException {
        LocalDateTime sentAt = LocalDateTime.now();
        Long sentBy = securityUtil.getCurrentUserId();
        int claimed = shipmentRepository.markSentIfConfirmed(shipmentId, sentAt, sentBy);
        if (claimed == 0) {
            ProductShipmentEntity reloaded = shipmentRepository.findById(shipmentId).orElse(shipment);
            String reloadedStatus = reloaded.getStatus() == null ? "" : reloaded.getStatus().trim().toUpperCase();
            if ("SENT".equals(reloadedStatus) || TERMINAL_SHIPMENT_STATUSES.contains(reloadedStatus)) {
                return toShipmentResponse(reloaded);
            }
            throw new BusinessException("El envío ya no está confirmado; no se pudo registrar la salida.");
        }
        ProductShipmentEntity saved = shipmentRepository.findById(shipmentId).orElse(shipment);

        if (saved.getDistributionId() != null) {
            markDistributionDispatchedIfAllSent(saved.getDistributionId());
        }

        return toShipmentResponse(saved);
    }

    private void markDistributionDispatchedIfAllSent(Long distributionId) {
        if (distributionId == null) {
            return;
        }
        List<ProductShipmentEntity> siblings = shipmentRepository.findByDistributionId(distributionId);
        boolean allTerminal = siblings.stream()
                .allMatch(s -> TERMINAL_SHIPMENT_STATUSES.contains(
                        s.getStatus() == null ? "" : s.getStatus().trim().toUpperCase()));
        if (allTerminal) {
            distributionRepository.findById(distributionId).ifPresent(dist -> {
                dist.setStatus("DISPATCHED");
                distributionRepository.save(dist);
            });
        }
    }

    /**
     * Confirma la recepción de un envío en el kiosco e incrementa inventario del kiosco
     */
    public ProductShipmentResponse confirmReceipt(Long shipmentId, ConfirmReceiptRequest body)
            throws ResourceNotFoundException, BusinessException {
        ProductShipmentEntity shipment = shipmentRepository.findByIdForUpdate(shipmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Shipment", shipmentId));

        String currentStatus = shipment.getStatus() == null ? "" : shipment.getStatus().trim().toUpperCase();
        if ("DELIVERED".equals(currentStatus)) {
            return toShipmentResponse(shipment);
        }
        if (!"SENT".equals(currentStatus)) {
            throw new BusinessException("El envío debe estar en tránsito para confirmar recepción. Estado actual: " + shipment.getStatus());
        }

        if (shipment.getLocationId() == null) {
            throw new BusinessException(
                    "El envío no tiene kiosko destino (location_id). No se puede cargar inventario de kiosco.");
        }

        assertMayConfirmReceiptForLocation(shipment.getLocationId());

        String receivedNotes = body != null && body.getNotes() != null ? body.getNotes().trim() : null;
        if (receivedNotes != null && receivedNotes.isEmpty()) {
            receivedNotes = null;
        }

        List<ConfirmReceiptRequest.Item> itemReceipts = body != null && body.getItems() != null
                ? body.getItems()
                : null;

        // Incrementar inventario de productos en la ubicación (kiosco)
        List<ProductShipmentDetailEntity> details = shipmentDetailRepository.findByShipmentId(shipmentId);
        for (ProductShipmentDetailEntity detail : details) {
            BigDecimal sentQty = detail.getQuantity() != null ? detail.getQuantity() : BigDecimal.ZERO;
            BigDecimal qtyReceived = sentQty;
            String lineNotes = null;

            if (itemReceipts != null) {
                for (ConfirmReceiptRequest.Item ir : itemReceipts) {
                    if (ir == null || ir.getDetailId() == null) {
                        continue;
                    }
                    if (java.util.Objects.equals(ir.getDetailId(), detail.getId())) {
                        if (ir.getQuantityReceived() != null) {
                            qtyReceived = ir.getQuantityReceived();
                        }
                        if (ir.getLineNotes() != null) {
                            lineNotes = ir.getLineNotes().trim();
                            if (lineNotes.isEmpty()) {
                                lineNotes = null;
                            }
                        }
                        break;
                    }
                }
            }

            if (qtyReceived.compareTo(BigDecimal.ZERO) < 0) {
                throw new BusinessException("La cantidad recibida no puede ser negativa para el detalle " + detail.getId());
            }
            if (qtyReceived.compareTo(sentQty) > 0) {
                throw new BusinessException("La cantidad recibida no puede superar la enviada para el detalle " + detail.getId());
            }

            detail.setQuantityReceived(qtyReceived);
            detail.setQuantityDifference(sentQty.subtract(qtyReceived));
            detail.setReceivedLineNotes(lineNotes);
            shipmentDetailRepository.save(detail);

            if (qtyReceived.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            applyReceiptInventoryForDetail(shipment, detail, qtyReceived, shipmentReceiptLineReference(shipment, detail));
        }

        applyReceiptPackingItemsToKioskStock(shipment, details);

        shipment.setStatus("DELIVERED");
        shipment.setReceivedAt(LocalDateTime.now());
        shipment.setReceivedBy(securityUtil.getCurrentUserId());
        shipment.setReceivedNotes(receivedNotes);
        shipmentRepository.save(shipment);

        // Verificar si todos los envíos de la distribución están DELIVERED
        if (shipment.getDistributionId() != null) {
            List<ProductShipmentEntity> allShipments = shipmentRepository.findByDistributionId(shipment.getDistributionId());
            boolean allDelivered = allShipments.stream()
                    .allMatch(s -> "DELIVERED".equalsIgnoreCase(safeTrim(s.getStatus())));
            if (allDelivered) {
                ProductDistributionEntity dist = distributionRepository.findById(shipment.getDistributionId()).orElse(null);
                if (dist != null) {
                    dist.setStatus("COMPLETED");
                    distributionRepository.save(dist);
                }
            }
        }

        return toShipmentResponse(shipmentRepository.findById(shipmentId).orElse(shipment));
    }

    /**
     * Repara inventario de kiosko para un envío DELIVERED: carga todas las líneas del documento
     * (productos, tallas y empaques SUM-) que aún no estén registradas en stock kiosco.
     */
    @Transactional
    public ShipmentReceiptRepairResponse repairDeliveredShipmentReceiptInventory(Long shipmentId, boolean force)
            throws ResourceNotFoundException, BusinessException {
        if (force) {
            KioscoShipmentReconcileResponse reconciled = reconcileShipmentReceiptInventory(null, shipmentId);
            return ShipmentReceiptRepairResponse.builder()
                    .repairedLines(reconciled.getLinesReconciled())
                    .warnings(reconciled.getWarnings())
                    .build();
        }
        return repairDeliveredShipmentReceiptInventoryAdditive(shipmentId);
    }

    @Transactional
    public ShipmentReceiptRepairResponse repairDeliveredShipmentReceiptInventoryAdditive(Long shipmentId)
            throws ResourceNotFoundException, BusinessException {
        ProductShipmentEntity shipment = shipmentRepository.findByIdForUpdate(shipmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Shipment", shipmentId));
        String currentStatus = shipment.getStatus() == null ? "" : shipment.getStatus().trim().toUpperCase();
        if (!"DELIVERED".equals(currentStatus)) {
            throw new BusinessException("Solo se puede reparar inventario de envíos ya entregados (DELIVERED).");
        }
        if (shipment.getLocationId() == null) {
            throw new BusinessException("El envío no tiene kiosko destino.");
        }

        List<String> warnings = new ArrayList<>();
        List<ProductShipmentDetailEntity> details = shipmentDetailRepository.findByShipmentId(shipmentId);
        int repaired = 0;
        for (ProductShipmentDetailEntity detail : details) {
            if (detail == null || detail.getProductId() == null) {
                continue;
            }
            BigDecimal qtyExpected = resolveShipmentLineQuantity(detail);
            if (qtyExpected.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            if (repairShipmentProductLineIfMissing(shipment, detail, qtyExpected, false)) {
                repaired++;
            }
        }
        repaired += repairDeliveredShipmentPackingInventory(shipment, details, false, warnings);
        if (repaired == 0 && warnings.isEmpty()) {
            List<ProductShipmentResponse.PackingItemResponse> packingItems =
                    parsePackingItems(shipment.getPackingItems());
            if (packingItems.isEmpty()) {
                warnings.add("El envío no tiene empaques SUM- registrados (packing_items vacío). "
                        + "Revise el documento o cargue empaques manualmente en inventario kiosco.");
            }
        }
        return ShipmentReceiptRepairResponse.builder()
                .repairedLines(repaired)
                .warnings(warnings)
                .build();
    }

    /**
     * Cuadra entradas de envíos DELIVERED con cantidades del documento.
     * Solo elimina ENTRADAs duplicadas del envío; no agrega faltantes ni reescribe kardex
     * (use Sincronizar inventario kiosco para cargar lo que falte).
     */
    @Transactional
    public KioscoShipmentReconcileResponse reconcileShipmentReceiptInventory(
            Long locationId,
            Long shipmentId
    ) throws ResourceNotFoundException, BusinessException {
        requireReconcileAdminAccess();
        kioscoInventoryService.enableAdminMovementMutation();

        List<ProductShipmentEntity> shipments;

        if (shipmentId != null) {
            ProductShipmentEntity shipment = shipmentRepository.findByIdForUpdate(shipmentId)
                    .orElseThrow(() -> new ResourceNotFoundException("Shipment", shipmentId));
            assertDeliveredShipment(shipment);
            if (shipment.getLocationId() == null) {
                throw new BusinessException("El envío no tiene kiosko destino.");
            }
            shipments = List.of(shipment);
        } else {
            if (locationId == null) {
                throw new BusinessException("Debes indicar el kiosko o el envío a cuadrar.");
            }
            shipments = shipmentRepository.findByStatusIgnoreCaseAndLocationId("DELIVERED", locationId);
        }

        int linesReconciled = 0;
        int duplicatesRemoved = 0;
        List<String> warnings = new ArrayList<>();
        Set<Long> affectedStockIds = new HashSet<>();
        Set<String> processedScopeKeys = new HashSet<>();

        for (ProductShipmentEntity shipment : shipments) {
            List<ProductShipmentDetailEntity> details = shipmentDetailRepository.findByShipmentId(shipment.getId());
            for (ProductShipmentDetailEntity detail : details) {
                if (detail == null || detail.getProductId() == null) {
                    continue;
                }
                BigDecimal qtyExpected = resolveShipmentLineQuantity(detail);
                if (qtyExpected.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                processedScopeKeys.add(sumReconcileScopeKey(
                        shipment.getId(), detail.getProductId(), detail.getColorId()));
                ReconcileLineResult result = reconcileShipmentProductLine(
                        shipment, detail, qtyExpected, affectedStockIds, warnings);
                linesReconciled += result.linesReconciled();
                duplicatesRemoved += result.duplicatesRemoved();
            }
            ReconcileLineResult packingResult = reconcileDeliveredShipmentPackingInventory(
                    shipment, details, processedScopeKeys, affectedStockIds, warnings);
            linesReconciled += packingResult.linesReconciled();
            duplicatesRemoved += packingResult.duplicatesRemoved();
        }

        Long resolvedLocationId = shipmentId != null && !shipments.isEmpty()
                ? shipments.get(0).getLocationId()
                : locationId;
        if (resolvedLocationId != null) {
            ReconcileLineResult sumResult = reconcileSumPackagingEntradasDiscoveredFromMovements(
                    resolvedLocationId, shipments, processedScopeKeys, affectedStockIds, warnings, null);
            linesReconciled += sumResult.linesReconciled();
            duplicatesRemoved += sumResult.duplicatesRemoved();
        }

        int stockRowsRecalculated = 0;
        Long replayLocationId = resolvedLocationId;
        if (replayLocationId != null) {
            for (KioscoStockEntity stock : kioscoStockRepository.findByLocationIdOrderByProductIdAscColorIdAsc(replayLocationId)) {
                stockRowsRecalculated += kioscoInventoryService.replayMovementStockChain(stock.getId());
            }
        }

        return KioscoShipmentReconcileResponse.builder()
                .linesReconciled(linesReconciled)
                .duplicatesRemoved(duplicatesRemoved)
                .stockRowsRecalculated(stockRowsRecalculated)
                .warnings(warnings)
                .build();
    }

    /**
     * Vista previa (sin mutaciones) de lo que haría {@link #reconcileShipmentReceiptInventory}.
     */
    @Transactional(readOnly = true)
    public KioscoShipmentReconcilePreviewResponse previewShipmentReceiptInventoryReconcile(
            Long locationId,
            Long shipmentId
    ) throws ResourceNotFoundException, BusinessException {
        requireReconcileAdminAccess();

        List<ProductShipmentEntity> shipments;
        Long resolvedLocationId = locationId;

        if (shipmentId != null) {
            ProductShipmentEntity shipment = shipmentRepository.findById(shipmentId)
                    .orElseThrow(() -> new ResourceNotFoundException("Shipment", shipmentId));
            assertDeliveredShipment(shipment);
            if (shipment.getLocationId() == null) {
                throw new BusinessException("El envío no tiene kiosko destino.");
            }
            resolvedLocationId = shipment.getLocationId();
            shipments = List.of(shipment);
        } else {
            if (locationId == null) {
                throw new BusinessException("Debes indicar el kiosko o el envío a revisar.");
            }
            shipments = shipmentRepository.findByStatusIgnoreCaseAndLocationId("DELIVERED", locationId);
        }

        List<String> warnings = new ArrayList<>();
        List<KioscoShipmentReconcilePreviewResponse.PreviewLine> previewLines = new ArrayList<>();
        Set<Long> affectedStockIds = new HashSet<>();
        Set<String> processedScopeKeys = new HashSet<>();

        for (ProductShipmentEntity shipment : shipments) {
            List<ProductShipmentDetailEntity> details = shipmentDetailRepository.findByShipmentId(shipment.getId());
            for (ProductShipmentDetailEntity detail : details) {
                if (detail == null || detail.getProductId() == null) {
                    continue;
                }
                BigDecimal qtyExpected = resolveShipmentLineQuantity(detail);
                if (qtyExpected.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                processedScopeKeys.add(sumReconcileScopeKey(
                        shipment.getId(), detail.getProductId(), detail.getColorId()));
                KioscoShipmentReconcilePreviewResponse.PreviewLine line = planShipmentProductLine(
                        shipment, detail, qtyExpected, affectedStockIds, warnings);
                if (line != null) {
                    previewLines.add(line);
                }
            }
            previewDeliveredShipmentPackingInventory(
                    shipment, details, processedScopeKeys, affectedStockIds, warnings, previewLines);
        }

        if (resolvedLocationId != null) {
            reconcileSumPackagingEntradasDiscoveredFromMovements(
                    resolvedLocationId, shipments, processedScopeKeys, affectedStockIds, warnings, previewLines);
        }

        int entradasToDelete = 0;
        int entradasToTrim = 0;
        int entradasToAdd = 0;
        int mermasToDelete = 0;
        int kardexLinesToNormalize = 0;
        for (KioscoShipmentReconcilePreviewResponse.PreviewLine line : previewLines) {
            for (KioscoShipmentReconcilePreviewResponse.PreviewAction action : line.getActions()) {
                switch (action.getType()) {
                    case "DELETE_ENTRADA" -> entradasToDelete++;
                    case "TRIM_ENTRADA" -> entradasToTrim++;
                    case "ADD_ENTRADA" -> entradasToAdd++;
                    case "DELETE_MERMA" -> mermasToDelete++;
                    case "NORMALIZE_KARDEX" -> kardexLinesToNormalize++;
                    default -> { }
                }
            }
        }

        int linesWithChanges = (int) previewLines.stream()
                .filter(line -> "CHANGE".equals(line.getStatus()))
                .count();
        int linesWithWarnings = (int) previewLines.stream()
                .filter(line -> "WARNING".equals(line.getStatus()))
                .count();

        return KioscoShipmentReconcilePreviewResponse.builder()
                .locationId(resolvedLocationId)
                .shipmentId(shipmentId)
                .shipmentsReviewed(shipments.size())
                .linesWithChanges(linesWithChanges)
                .entradasToDelete(entradasToDelete)
                .entradasToTrim(entradasToTrim)
                .entradasToAdd(entradasToAdd)
                .mermasToDelete(mermasToDelete)
                .kardexLinesToNormalize(kardexLinesToNormalize)
                .stockRowsToRecalculate(affectedStockIds.size())
                .hasChanges(linesWithChanges > 0 || linesWithWarnings > 0)
                .warnings(warnings)
                .lines(previewLines)
                .build();
    }

    private KioscoShipmentReconcilePreviewResponse.PreviewLine planShipmentProductLine(
            ProductShipmentEntity shipment,
            ProductShipmentDetailEntity detail,
            BigDecimal qtyExpected,
            Set<Long> affectedStockIds,
            List<String> warnings
    ) {
        String lineRef = shipmentReceiptLineReference(shipment, detail);
        String sizeKey = detail.getSizeLabel() != null ? detail.getSizeLabel().trim() : "";
        String sizeKeyForInventory = sizeKey.isEmpty() ? null : sizeKey;
        return planShipmentEntradaQuantities(
                shipment,
                detail.getProductId(),
                detail.getColorId(),
                lineRef,
                qtyExpected.intValue(),
                detail.getQuantity() != null ? detail.getQuantity().intValue() : null,
                sizeKeyForInventory,
                "PRODUCT",
                affectedStockIds,
                warnings);
    }

    private void previewDeliveredShipmentPackingInventory(
            ProductShipmentEntity shipment,
            List<ProductShipmentDetailEntity> details,
            Set<String> processedScopeKeys,
            Set<Long> affectedStockIds,
            List<String> warnings,
            List<KioscoShipmentReconcilePreviewResponse.PreviewLine> previewLines
    ) {
        List<ProductShipmentResponse.PackingItemResponse> packingItems = parsePackingItems(shipment.getPackingItems());
        if (packingItems.isEmpty()) {
            return;
        }

        for (ProductShipmentResponse.PackingItemResponse item : packingItems) {
            if (item == null || item.getMaterialId() == null || item.getQuantity() == null) {
                continue;
            }
            if (item.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            Optional<ProductEntity> productOpt = resolvePackagingProductFromMaterial(item.getMaterialId());
            if (productOpt.isEmpty()) {
                MaterialEntity material = materialRepository.findById(item.getMaterialId()).orElse(null);
                String materialSku = material != null && material.getSku() != null
                        ? material.getSku().trim() : ("material#" + item.getMaterialId());
                warnings.add("Empaque SUM- " + materialSku + ": no se encontró producto SUM- en catálogo.");
                continue;
            }
            ProductEntity product = productOpt.get();
            processedScopeKeys.add(sumReconcileScopeKey(shipment.getId(), product.getId(), null));
            String lineRef = shipmentPackingLineReference(shipment, item.getMaterialId());
            KioscoShipmentReconcilePreviewResponse.PreviewLine line = planShipmentEntradaQuantities(
                    shipment,
                    product.getId(),
                    null,
                    lineRef,
                    item.getQuantity().intValue(),
                    item.getQuantity().intValue(),
                    null,
                    "PACKING",
                    affectedStockIds,
                    warnings);
            if (line != null) {
                previewLines.add(line);
            }
        }
    }

    private Set<Long> buildPackingSkipProductIds(
            ProductShipmentEntity shipment,
            List<ProductShipmentDetailEntity> details
    ) {
        Set<Long> receivedProductIds = new HashSet<>();
        if (details == null) {
            return receivedProductIds;
        }
        for (ProductShipmentDetailEntity detail : details) {
            if (detail == null || detail.getProductId() == null) {
                continue;
            }
            BigDecimal qtyExpected = resolveShipmentLineQuantity(detail);
            if (qtyExpected.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            String lineRef = shipmentReceiptLineReference(shipment, detail);
            if (kioscoInventoryService.hasShipmentReceiptLineApplied(
                    shipment.getLocationId(), shipment.getId(), lineRef)) {
                receivedProductIds.add(detail.getProductId());
            }
        }
        return receivedProductIds;
    }

    private KioscoShipmentReconcilePreviewResponse.PreviewLine planShipmentEntradaQuantities(
            ProductShipmentEntity shipment,
            Long productId,
            Long colorId,
            String lineRef,
            int expected,
            Integer sentQty,
            String sizeKeyForInventory,
            String lineType,
            Set<Long> affectedStockIds,
            List<String> warnings
    ) {
        Long locationId = shipment.getLocationId();
        List<KioscoMovementEntity> movements = resolveShipmentEntradaMovementsForReconcile(
                locationId, shipment.getId(), lineRef, productId, colorId, shipment);
        int sumQty = movements.stream().mapToInt(m -> m.getQuantity() != null ? m.getQuantity() : 0).sum();
        expected = reconcileExpectedEntradaQty(expected, sentQty, sumQty, movements.size());
        int stockQty = resolveKioscoStockQty(locationId, productId, colorId);

        List<KioscoShipmentReconcilePreviewResponse.PreviewAction> actions = new ArrayList<>();

        if (sumQty <= expected) {
            List<KioscoMovementEntity> mermaRows = kioscoMovementRepository.findShipmentReconcileMermaMovements(
                    locationId, shipment.getId(), lineRef, productId, colorId);
            if (!mermaRows.isEmpty()) {
                String productLabel = resolveProductLabel(productId);
                warnings.add(productLabel + ": hay MERMA de cuadre previo pero las ENTRADAs ya cuadran con el documento. "
                        + "No se eliminará la MERMA hasta detectar ENTRADAs duplicadas (evita inflar Fin.).");
            }
            if (movements.size() > 1) {
                String productLabel = resolveProductLabel(productId);
                warnings.add(productLabel + ": " + movements.size() + " ENTRADA(s) suman " + sumQty
                        + " (documento " + expected + "). Revise quantity_received si debería ser menor.");
                return buildPreviewLine(
                        shipment, productId, colorId, lineType, expected, sumQty, movements.size(), stockQty,
                        "WARNING", List.of());
            }
            if (stockQty > expected && movements.isEmpty()) {
                String productLabel = resolveProductLabel(productId);
                warnings.add(productLabel + ": stock kiosco=" + stockQty
                        + " sin ENTRADAs enlazadas al envío (documento " + expected + ").");
                return buildPreviewLine(
                        shipment, productId, colorId, lineType, expected, sumQty, movements.size(), stockQty,
                        "WARNING", List.of());
            }
            return null;
        }

        List<KioscoMovementEntity> mermaRows = kioscoMovementRepository.findShipmentReconcileMermaMovements(
                locationId, shipment.getId(), lineRef, productId, colorId);
        for (KioscoMovementEntity merma : mermaRows) {
            actions.add(KioscoShipmentReconcilePreviewResponse.PreviewAction.builder()
                    .type("DELETE_MERMA")
                    .movementId(merma.getId())
                    .quantity(merma.getQuantity())
                    .label("Eliminar MERMA de cuadre previo #" + merma.getId()
                            + (merma.getQuantity() != null ? " (" + merma.getQuantity() + " u.)" : ""))
                    .build());
        }

        {
            String productLabel = resolveProductLabel(productId);
            warnings.add(productLabel + ": " + movements.size() + " ENTRADA(s) suman " + sumQty
                    + " (esperado " + expected + "). Se eliminarán duplicados.");
            KioscoInventoryService.EntradaPrunePlan prunePlan =
                    kioscoInventoryService.planPruneExcessShipmentEntradas(movements, expected);
            for (KioscoInventoryService.PlannedEntradaAction planned : prunePlan.actions()) {
                actions.add(KioscoShipmentReconcilePreviewResponse.PreviewAction.builder()
                        .type(planned.type())
                        .movementId(planned.movementId())
                        .quantity(planned.quantity())
                        .label(planned.label())
                        .build());
            }
        }

        if (actions.isEmpty()) {
            return null;
        }

        trackAffectedStockIds(movements, locationId, productId, colorId, affectedStockIds);
        actions.add(KioscoShipmentReconcilePreviewResponse.PreviewAction.builder()
                .type("RECALCULATE_STOCK")
                .label("Recalcular cadena de stock del producto")
                .build());

        return buildPreviewLine(
                shipment,
                productId,
                colorId,
                lineType,
                expected,
                sumQty,
                movements.size(),
                resolveKioscoStockQty(locationId, productId, colorId),
                "CHANGE",
                actions);
    }

    private KioscoShipmentReconcilePreviewResponse.PreviewLine buildPreviewLine(
            ProductShipmentEntity shipment,
            Long productId,
            Long colorId,
            String lineType,
            int expected,
            int sumQty,
            int movementCount,
            int stockQty,
            String status,
            List<KioscoShipmentReconcilePreviewResponse.PreviewAction> actions
    ) {
        ProductEntity product = productRepository.findById(productId).orElse(null);
        String colorName = null;
        if (colorId != null) {
            ColorEntity color = colorRepository.findById(colorId).orElse(null);
            colorName = color != null ? color.getName() : null;
        }
        return KioscoShipmentReconcilePreviewResponse.PreviewLine.builder()
                .shipmentId(shipment.getId())
                .shipmentNumber(shipment.getShipmentNumber())
                .lineType(lineType)
                .productId(productId)
                .productCode(product != null ? product.getCode() : null)
                .productName(product != null ? product.getName() : null)
                .colorId(colorId)
                .colorName(colorName)
                .qtyExpected(expected)
                .currentEntradaSum(sumQty)
                .movementCount(movementCount)
                .currentStockQty(stockQty)
                .status(status)
                .actions(actions)
                .build();
    }

    private record ReconcileLineResult(int linesReconciled, int duplicatesRemoved) {}

    private void requireReconcileAdminAccess() throws BusinessException {
        Long userId = securityUtil.getCurrentUserId();
        if (userId == null) {
            throw new BusinessException("Debes iniciar sesión.");
        }
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("Usuario no encontrado."));
        if (!KioskAccessHelper.hasAllKiosksAccess(user)) {
            throw new BusinessException("Solo administradores pueden cuadrar inventario desde envíos.");
        }
    }

    private void assertDeliveredShipment(ProductShipmentEntity shipment) throws BusinessException {
        String currentStatus = shipment.getStatus() == null ? "" : shipment.getStatus().trim().toUpperCase();
        if (!"DELIVERED".equals(currentStatus)) {
            throw new BusinessException("Solo se puede cuadrar inventario de envíos ya entregados (DELIVERED).");
        }
    }

    private ReconcileLineResult reconcileShipmentProductLine(
            ProductShipmentEntity shipment,
            ProductShipmentDetailEntity detail,
            BigDecimal qtyExpected,
            Set<Long> affectedStockIds,
            List<String> warnings
    ) throws ResourceNotFoundException, BusinessException {
        String lineRef = shipmentReceiptLineReference(shipment, detail);
        String sizeKey = detail.getSizeLabel() != null ? detail.getSizeLabel().trim() : "";
        String sizeKeyForInventory = sizeKey.isEmpty() ? null : sizeKey;
        return reconcileShipmentEntradaQuantities(
                shipment,
                detail.getProductId(),
                detail.getColorId(),
                lineRef,
                qtyExpected.intValue(),
                detail.getQuantity() != null ? detail.getQuantity().intValue() : null,
                sizeKeyForInventory,
                "Recepcion de envio en kiosko",
                affectedStockIds,
                warnings);
    }

    private ReconcileLineResult reconcileDeliveredShipmentPackingInventory(
            ProductShipmentEntity shipment,
            List<ProductShipmentDetailEntity> details,
            Set<String> processedScopeKeys,
            Set<Long> affectedStockIds,
            List<String> warnings
    ) throws ResourceNotFoundException, BusinessException {
        List<ProductShipmentResponse.PackingItemResponse> packingItems = parsePackingItems(shipment.getPackingItems());
        if (packingItems.isEmpty()) {
            return new ReconcileLineResult(0, 0);
        }

        int linesReconciled = 0;
        int duplicatesRemoved = 0;
        for (ProductShipmentResponse.PackingItemResponse item : packingItems) {
            if (item == null || item.getMaterialId() == null || item.getQuantity() == null) {
                continue;
            }
            if (item.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            Optional<ProductEntity> productOpt = resolvePackagingProductFromMaterial(item.getMaterialId());
            if (productOpt.isEmpty()) {
                MaterialEntity material = materialRepository.findById(item.getMaterialId()).orElse(null);
                String materialSku = material != null && material.getSku() != null
                        ? material.getSku().trim() : ("material#" + item.getMaterialId());
                warnings.add("Empaque SUM- " + materialSku + ": no se encontró producto SUM- en catálogo.");
                continue;
            }
            ProductEntity product = productOpt.get();
            processedScopeKeys.add(sumReconcileScopeKey(shipment.getId(), product.getId(), null));
            ReconcileLineResult result = reconcileShipmentPackingLine(
                    shipment, product, item.getMaterialId(), item.getQuantity(), affectedStockIds, warnings);
            linesReconciled += result.linesReconciled();
            duplicatesRemoved += result.duplicatesRemoved();
        }
        return new ReconcileLineResult(linesReconciled, duplicatesRemoved);
    }

    private ReconcileLineResult reconcileShipmentPackingLine(
            ProductShipmentEntity shipment,
            ProductEntity product,
            Long materialId,
            BigDecimal qtyExpected,
            Set<Long> affectedStockIds,
            List<String> warnings
    ) throws ResourceNotFoundException, BusinessException {
        String lineRef = shipmentPackingLineReference(shipment, materialId);
        return reconcileShipmentEntradaQuantities(
                shipment,
                product.getId(),
                null,
                lineRef,
                qtyExpected.intValue(),
                qtyExpected.intValue(),
                null,
                "Recepcion de empaque SUM- en kiosko",
                affectedStockIds,
                warnings);
    }

    private ReconcileLineResult reconcileShipmentEntradaQuantities(
            ProductShipmentEntity shipment,
            Long productId,
            Long colorId,
            String lineRef,
            int expected,
            Integer sentQty,
            String sizeKeyForInventory,
            String kardexDescription,
            Set<Long> affectedStockIds,
            List<String> warnings
    ) throws ResourceNotFoundException, BusinessException {
        Long locationId = shipment.getLocationId();

        List<KioscoMovementEntity> movements = resolveShipmentEntradaMovementsForReconcile(
                locationId, shipment.getId(), lineRef, productId, colorId, shipment);
        int sumQty = movements.stream().mapToInt(m -> m.getQuantity() != null ? m.getQuantity() : 0).sum();
        expected = reconcileExpectedEntradaQty(expected, sentQty, sumQty, movements.size());

        int linesReconciled = 0;
        int duplicatesRemoved = 0;

        int stockQty = resolveKioscoStockQty(locationId, productId, colorId);
        if (sumQty <= expected) {
            List<KioscoMovementEntity> pendingMerma = kioscoMovementRepository.findShipmentReconcileMermaMovements(
                    locationId, shipment.getId(), lineRef, productId, colorId);
            if (!pendingMerma.isEmpty()) {
                warnings.add(resolveProductLabel(productId) + ": hay MERMA de cuadre previo pero las ENTRADAs "
                        + "ya cuadran con el documento. No se tocó la MERMA para no inflar Fin.");
            }
            if (movements.size() > 1) {
                warnings.add(resolveProductLabel(productId) + ": " + movements.size()
                        + " ENTRADA(s) del envío suman " + sumQty + " (documento " + expected
                        + "). Si el documento debería ser menor, corrija quantity_received en el envío.");
            } else if (stockQty > expected && movements.isEmpty()) {
                warnings.add(resolveProductLabel(productId) + ": stock kiosco=" + stockQty
                        + " pero no se encontraron ENTRADAs enlazadas al envío (esperado " + expected + ").");
            }
            return new ReconcileLineResult(linesReconciled, duplicatesRemoved);
        }

        duplicatesRemoved += kioscoInventoryService.deleteShipmentReconcileMermaMovements(
                locationId, shipment.getId(), lineRef, productId, colorId);

        String productLabel = resolveProductLabel(productId);
        warnings.add(productLabel + ": " + movements.size() + " ENTRADA(s) suman " + sumQty
                + " (esperado " + expected + "). Se eliminarán duplicados.");

        int removedEntradas = kioscoInventoryService.pruneExcessShipmentEntradas(movements, expected);
        duplicatesRemoved += removedEntradas;
        if (duplicatesRemoved > 0) {
            linesReconciled = 1;
        } else if (movements.size() > 1) {
            throw new BusinessException(
                    "Se detectaron " + movements.size() + " ENTRADAs (suma " + sumQty
                            + ", esperado " + expected + ") pero no se pudieron eliminar. "
                            + "Verifique que ejecutó migration-kiosco-movement-admin-delete.sql en la base de datos.");
        }

        trackAffectedStockIds(movements, locationId, productId, colorId, affectedStockIds);
        return new ReconcileLineResult(linesReconciled, duplicatesRemoved);
    }

    /**
     * Busca todas las ENTRADAs del envío para cuadrar/prune, sin ocultar duplicados
     * por filtro parcial de línea en el motivo.
     */
    private List<KioscoMovementEntity> resolveShipmentEntradaMovementsForReconcile(
            Long locationId,
            Long shipmentId,
            String lineRef,
            Long productId,
            Long colorId,
            ProductShipmentEntity shipment
    ) {
        LinkedHashMap<Long, KioscoMovementEntity> merged = new LinkedHashMap<>();

        Optional<KioscoStockEntity> stockOpt = resolveStockForShipmentLine(locationId, productId, colorId);
        if (stockOpt.isPresent()) {
            Long stockId = stockOpt.get().getId();
            mergeShipmentEntradaMovements(merged, kioscoMovementRepository.findShipmentEntradasByStockAndShipment(
                    stockId, shipmentId));
            if (merged.isEmpty() && shipment != null) {
                String shipmentNumber = shipment.getShipmentNumber() != null
                        ? shipment.getShipmentNumber().trim() : null;
                if (shipmentNumber != null && !shipmentNumber.isBlank()) {
                    mergeShipmentEntradaMovements(merged, kioscoMovementRepository.findShipmentEntradasByStockAndReasonToken(
                            stockId, shipmentNumber));
                }
            }
        }

        mergeShipmentEntradaMovements(merged, kioscoMovementRepository.findShipmentEntradaMovements(
                locationId, shipmentId, lineRef));
        if (lineRef != null && !lineRef.isBlank()) {
            mergeShipmentEntradaMovements(merged, kioscoMovementRepository.findShipmentEntradaMovements(
                    locationId, shipmentId, KioscoInventoryService.shipmentReceiptLineReason(lineRef)));
        }
        mergeShipmentEntradaMovements(merged, findShipmentEntradaMovementsFallback(
                locationId, shipmentId, productId, colorId, shipment, lineRef));

        return merged.values().stream()
                .sorted(Comparator
                        .comparing(KioscoMovementEntity::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(KioscoMovementEntity::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private void mergeShipmentEntradaMovements(
            LinkedHashMap<Long, KioscoMovementEntity> target,
            List<KioscoMovementEntity> movements
    ) {
        if (movements == null) {
            return;
        }
        for (KioscoMovementEntity movement : movements) {
            if (movement != null && movement.getId() != null) {
                target.putIfAbsent(movement.getId(), movement);
            }
        }
    }

    private int reconcileExpectedEntradaQty(int documentExpected, Integer sentQty, int sumQty, int movementCount) {
        if (sentQty != null && sentQty > 0 && movementCount > 1
                && documentExpected > sentQty && sumQty >= documentExpected) {
            return sentQty;
        }
        return documentExpected;
    }

    private List<KioscoMovementEntity> resolveShipmentEntradaMovements(
            Long locationId,
            Long shipmentId,
            String lineRef,
            Long productId,
            Long colorId,
            ProductShipmentEntity shipment
    ) {
        Optional<KioscoStockEntity> stockOpt = resolveStockForShipmentLine(locationId, productId, colorId);
        if (stockOpt.isPresent()) {
            Long stockId = stockOpt.get().getId();
            List<KioscoMovementEntity> byStock = kioscoMovementRepository.findShipmentEntradasByStockAndShipment(
                    stockId, shipmentId);
            if (byStock.isEmpty() && shipment != null) {
                String shipmentNumber = shipment.getShipmentNumber() != null
                        ? shipment.getShipmentNumber().trim() : null;
                if (shipmentNumber != null && !shipmentNumber.isBlank()) {
                    byStock = kioscoMovementRepository.findShipmentEntradasByStockAndReasonToken(
                            stockId, shipmentNumber);
                }
            }
            if (!byStock.isEmpty()) {
                return filterEntradasForShipmentLine(byStock, lineRef);
            }
        }

        List<KioscoMovementEntity> byLine = kioscoMovementRepository.findShipmentEntradaMovements(
                locationId, shipmentId, lineRef);
        if (!byLine.isEmpty()) {
            return byLine;
        }
        String lineReasonKey = KioscoInventoryService.shipmentReceiptLineReason(lineRef);
        byLine = kioscoMovementRepository.findShipmentEntradaMovements(locationId, shipmentId, lineReasonKey);
        if (!byLine.isEmpty()) {
            return byLine;
        }
        return findShipmentEntradaMovementsFallback(locationId, shipmentId, productId, colorId, shipment, lineRef);
    }

    private String sumReconcileScopeKey(Long shipmentId, Long productId, Long colorId) {
        return shipmentId + ":" + productId + ":" + (colorId != null ? colorId : "null");
    }

    private record SumExpectedQty(int expected, Integer sentQty, String lineRef) {}

    /**
     * Cuadra SUM- detectados por movimientos ENTRADA en BD aunque packing_items falte o el producto
     * no se resolvió desde material en la vista previa (solo lectura).
     */
    private ReconcileLineResult reconcileSumPackagingEntradasDiscoveredFromMovements(
            Long locationId,
            List<ProductShipmentEntity> shipments,
            Set<String> processedScopeKeys,
            Set<Long> affectedStockIds,
            List<String> warnings,
            List<KioscoShipmentReconcilePreviewResponse.PreviewLine> previewLines
    ) throws ResourceNotFoundException, BusinessException {
        if (locationId == null || shipments == null || shipments.isEmpty()) {
            return new ReconcileLineResult(0, 0);
        }
        List<Long> shipmentIds = shipments.stream()
                .map(ProductShipmentEntity::getId)
                .filter(Objects::nonNull)
                .toList();
        if (shipmentIds.isEmpty()) {
            return new ReconcileLineResult(0, 0);
        }

        Map<Long, ProductShipmentEntity> shipmentById = shipments.stream()
                .filter(s -> s.getId() != null)
                .collect(Collectors.toMap(ProductShipmentEntity::getId, s -> s, (a, b) -> a));

        List<KioscoMovementEntity> sumMovements = kioscoMovementRepository.findSumPackagingEntradasForShipments(
                locationId, shipmentIds);
        LinkedHashMap<Long, KioscoMovementEntity> movementById = new LinkedHashMap<>();
        for (KioscoMovementEntity movement : sumMovements) {
            if (movement != null && movement.getId() != null) {
                movementById.putIfAbsent(movement.getId(), movement);
            }
        }
        for (KioscoMovementEntity movement : kioscoMovementRepository.findSumPackagingEntradasWithoutReference(locationId)) {
            if (movement == null || movement.getId() == null) {
                continue;
            }
            Long inferredShipmentId = resolveShipmentIdFromReason(movement.getReason(), shipments);
            if (inferredShipmentId == null || !shipmentIds.contains(inferredShipmentId)) {
                continue;
            }
            movementById.putIfAbsent(movement.getId(), movement);
        }
        if (movementById.isEmpty()) {
            return new ReconcileLineResult(0, 0);
        }

        Map<String, List<KioscoMovementEntity>> grouped = new LinkedHashMap<>();
        for (KioscoMovementEntity movement : movementById.values()) {
            if (movement == null || movement.getKioscoStockId() == null) {
                continue;
            }
            Long shipmentIdForMovement = movement.getReferenceId();
            if (shipmentIdForMovement == null) {
                shipmentIdForMovement = resolveShipmentIdFromReason(movement.getReason(), shipments);
            }
            if (shipmentIdForMovement == null) {
                continue;
            }
            KioscoStockEntity stock = kioscoStockRepository.findById(movement.getKioscoStockId()).orElse(null);
            if (stock == null || stock.getProductId() == null) {
                continue;
            }
            String scopeKey = sumReconcileScopeKey(shipmentIdForMovement, stock.getProductId(), stock.getColorId());
            grouped.computeIfAbsent(scopeKey, key -> new ArrayList<>()).add(movement);
        }
        if (grouped.isEmpty()) {
            return new ReconcileLineResult(0, 0);
        }

        int linesReconciled = 0;
        int duplicatesRemoved = 0;
        for (Map.Entry<String, List<KioscoMovementEntity>> entry : grouped.entrySet()) {
            if (processedScopeKeys.contains(entry.getKey())) {
                continue;
            }
            List<KioscoMovementEntity> scopeMovements = entry.getValue();
            if (scopeMovements.isEmpty()) {
                continue;
            }
            KioscoMovementEntity sample = scopeMovements.get(0);
            Long shipmentIdForScope = sample.getReferenceId();
            if (shipmentIdForScope == null) {
                shipmentIdForScope = resolveShipmentIdFromReason(sample.getReason(), shipments);
            }
            ProductShipmentEntity shipment = shipmentIdForScope != null
                    ? shipmentById.get(shipmentIdForScope) : null;
            if (shipment == null) {
                continue;
            }
            KioscoStockEntity stock = kioscoStockRepository.findById(sample.getKioscoStockId()).orElse(null);
            if (stock == null) {
                continue;
            }

            SumExpectedQty expectedQty = resolveSumExpectedQuantities(shipment, stock.getProductId());
            int expected = expectedQty.expected();
            Integer sentQty = expectedQty.sentQty();
            String lineRef = expectedQty.lineRef();
            if (lineRef == null || lineRef.isBlank()) {
                lineRef = extractShipmentLineRefFromReason(sample.getReason());
            }
            if (lineRef == null || lineRef.isBlank()) {
                lineRef = shipment.getShipmentNumber() != null
                        ? shipment.getShipmentNumber().trim() + "#SUM" + stock.getProductId()
                        : "ENV#SUM" + stock.getProductId();
            }
            if (expected <= 0) {
                int sumQty = scopeMovements.stream().mapToInt(m -> m.getQuantity() != null ? m.getQuantity() : 0).sum();
                if (scopeMovements.size() > 1 || sumQty > resolveKioscoStockQty(locationId, stock.getProductId(), stock.getColorId())) {
                    String productLabel = resolveProductLabel(stock.getProductId());
                    warnings.add(productLabel + ": ENTRADAs SUM- detectadas en envío #"
                            + shipment.getId() + " pero sin cantidad documentada (packing_items/detalle). "
                            + "Revise el envío o corrija manualmente.");
                }
                continue;
            }

            processedScopeKeys.add(entry.getKey());
            if (previewLines != null) {
                KioscoShipmentReconcilePreviewResponse.PreviewLine line = planShipmentEntradaQuantities(
                        shipment,
                        stock.getProductId(),
                        stock.getColorId(),
                        lineRef,
                        expected,
                        sentQty,
                        null,
                        "PACKING",
                        affectedStockIds,
                        warnings);
                if (line != null) {
                    previewLines.add(line);
                }
            } else {
                ReconcileLineResult result = reconcileShipmentEntradaQuantities(
                        shipment,
                        stock.getProductId(),
                        stock.getColorId(),
                        lineRef,
                        expected,
                        sentQty,
                        null,
                        "Recepcion de empaque SUM- en kiosko",
                        affectedStockIds,
                        warnings);
                linesReconciled += result.linesReconciled();
                duplicatesRemoved += result.duplicatesRemoved();
            }
        }
        return new ReconcileLineResult(linesReconciled, duplicatesRemoved);
    }

    private SumExpectedQty resolveSumExpectedQuantities(ProductShipmentEntity shipment, Long productId) {
        if (shipment == null || productId == null) {
            return new SumExpectedQty(0, null, null);
        }
        List<ProductShipmentDetailEntity> details = shipmentDetailRepository.findByShipmentId(shipment.getId());
        for (ProductShipmentDetailEntity detail : details) {
            if (detail == null || !Objects.equals(detail.getProductId(), productId)) {
                continue;
            }
            BigDecimal qty = resolveShipmentLineQuantity(detail);
            if (qty.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            Integer sentQty = detail.getQuantity() != null ? detail.getQuantity().intValue() : qty.intValue();
            return new SumExpectedQty(
                    qty.intValue(),
                    sentQty,
                    shipmentReceiptLineReference(shipment, detail));
        }
        for (ProductShipmentResponse.PackingItemResponse item : parsePackingItems(shipment.getPackingItems())) {
            if (item == null || item.getMaterialId() == null || item.getQuantity() == null) {
                continue;
            }
            if (item.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            Optional<ProductEntity> productOpt = resolvePackagingProductFromMaterial(item.getMaterialId());
            if (productOpt.isEmpty() || !Objects.equals(productOpt.get().getId(), productId)) {
                continue;
            }
            int qty = item.getQuantity().intValue();
            return new SumExpectedQty(
                    qty,
                    qty,
                    shipmentPackingLineReference(shipment, item.getMaterialId()));
        }
        return new SumExpectedQty(0, null, null);
    }

    private String extractShipmentLineRefFromReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        String marker = KioscoInventoryService.SHIPMENT_RECEIPT_LINE_PREFIX;
        int idx = reason.indexOf(marker);
        if (idx < 0) {
            return null;
        }
        String tail = reason.substring(idx + marker.length()).trim();
        int end = tail.indexOf(' ');
        return end > 0 ? tail.substring(0, end).trim() : tail;
    }

    private Long resolveShipmentIdFromReason(String reason, List<ProductShipmentEntity> shipments) {
        if (reason == null || reason.isBlank() || shipments == null) {
            return null;
        }
        for (ProductShipmentEntity shipment : shipments) {
            if (shipment == null || shipment.getId() == null) {
                continue;
            }
            String shipmentNumber = shipment.getShipmentNumber() != null
                    ? shipment.getShipmentNumber().trim() : null;
            if (shipmentNumber != null && !shipmentNumber.isBlank() && reason.contains(shipmentNumber)) {
                return shipment.getId();
            }
        }
        return null;
    }

    private Optional<KioscoStockEntity> resolveStockForShipmentLine(
            Long locationId,
            Long productId,
            Long colorId
    ) {
        Optional<KioscoStockEntity> stockOpt = kioscoStockRepository.findByLocationIdAndProductIdAndColorId(
                locationId, productId, colorId);
        if (stockOpt.isPresent() || colorId == null) {
            return stockOpt;
        }
        return kioscoStockRepository.findByLocationIdAndProductIdAndColorId(locationId, productId, null);
    }

    private List<KioscoMovementEntity> findShipmentEntradaMovementsFallback(
            Long locationId,
            Long shipmentId,
            Long productId,
            Long colorId,
            ProductShipmentEntity shipment,
            String lineRef
    ) {
        List<KioscoMovementEntity> byProduct = kioscoMovementRepository.findShipmentEntradaMovementsByProduct(
                locationId, shipmentId, productId, colorId);
        if (!byProduct.isEmpty()) {
            return byProduct;
        }
        byProduct = kioscoMovementRepository.findShipmentEntradaMovementsByProductAnyColor(
                locationId, shipmentId, productId);
        if (!byProduct.isEmpty()) {
            return byProduct;
        }
        String shipmentToken = shipment != null && shipment.getShipmentNumber() != null
                ? shipment.getShipmentNumber().trim() : "";
        String lineReasonKey = lineRef != null && !lineRef.isBlank()
                ? KioscoInventoryService.shipmentReceiptLineReason(lineRef) : "";
        return kioscoMovementRepository.findShipmentEntradaMovementsByProductLoose(
                locationId,
                shipmentId,
                productId,
                colorId,
                shipmentToken,
                lineRef,
                lineReasonKey);
    }

    private String resolveProductLabel(Long productId) {
        if (productId == null) {
            return "Producto";
        }
        return productRepository.findById(productId)
                .map(p -> {
                    if (p.getCode() != null && !p.getCode().isBlank()) {
                        return p.getCode().trim();
                    }
                    if (p.getName() != null && !p.getName().isBlank()) {
                        return p.getName().trim();
                    }
                    return "producto#" + productId;
                })
                .orElse("producto#" + productId);
    }

    private List<KioscoMovementEntity> filterEntradasForShipmentLine(
            List<KioscoMovementEntity> movements,
            String lineRef
    ) {
        if (movements == null || movements.isEmpty() || lineRef == null || lineRef.isBlank()) {
            return movements != null ? movements : List.of();
        }
        String lineReasonKey = KioscoInventoryService.shipmentReceiptLineReason(lineRef);
        List<KioscoMovementEntity> matches = movements.stream()
                .filter(m -> {
                    String reason = m.getReason();
                    if (reason == null || reason.isBlank()) {
                        return false;
                    }
                    return reason.contains(lineRef) || reason.contains(lineReasonKey);
                })
                .toList();
        // Si hay ENTRADAs sin etiqueta de línea mezcladas con la línea, no ocultarlas al cuadrar.
        if (matches.isEmpty() || matches.size() < movements.size()) {
            return movements;
        }
        return matches;
    }

    private void trackAffectedStockIds(
            List<KioscoMovementEntity> movements,
            Long locationId,
            Long productId,
            Long colorId,
            Set<Long> affectedStockIds
    ) {
        if (movements != null) {
            movements.stream()
                    .map(KioscoMovementEntity::getKioscoStockId)
                    .filter(Objects::nonNull)
                    .forEach(affectedStockIds::add);
        }
        kioscoStockRepository.findByLocationIdAndProductIdAndColorId(locationId, productId, colorId)
                .ifPresent(stock -> affectedStockIds.add(stock.getId()));
    }

    private void applySingleShipmentKardexTransferIn(
            ProductShipmentEntity shipment,
            Long productId,
            Long colorId,
            String lineRef,
            BigDecimal qty,
            String sizeKeyForInventory,
            String description
    ) throws ResourceNotFoundException, BusinessException {
        BigDecimal before = productInventoryService
                .getInventoryByProductAndLocationAndColor(productId, shipment.getLocationId(), colorId)
                .getQuantity();
        productInventoryService.incrementInventory(
                productId,
                shipment.getLocationId(),
                colorId,
                qty,
                null,
                "SHIPMENT",
                shipment.getId(),
                shipment.getShipmentNumber(),
                description,
                sizeKeyForInventory);
        BigDecimal after = productInventoryService
                .getInventoryByProductAndLocationAndColor(productId, shipment.getLocationId(), colorId)
                .getQuantity();
        productInventoryService.recordProductMovementIfAbsent(
                productId,
                shipment.getLocationId(),
                colorId,
                "TRANSFER_IN",
                qty,
                before,
                after,
                null,
                "SHIPMENT",
                shipment.getId(),
                lineRef,
                description);
    }

    private void removeShipmentKardexRows(
            List<ProductInventoryKardex> kardexRows,
            Long productId,
            Long locationId,
            Long colorId
    ) throws ResourceNotFoundException, BusinessException {
        if (kardexRows == null || kardexRows.isEmpty()) {
            return;
        }
        for (ProductInventoryKardex row : kardexRows) {
            BigDecimal qty = row.getQuantity() != null ? row.getQuantity() : BigDecimal.ZERO;
            if (qty.compareTo(BigDecimal.ZERO) > 0) {
                productInventoryService.decrementInventory(
                        productId,
                        locationId,
                        colorId,
                        qty,
                        "SHIPMENT",
                        row.getReferenceId(),
                        row.getReferenceNumber(),
                        "Recepcion de envio en kiosko",
                        null);
            }
            productInventoryKardexRepository.delete(row);
        }
    }

    @Transactional(readOnly = true)
    public ShipmentReceiptInventoryAuditResponse auditDeliveredShipmentReceiptInventory(Long shipmentId)
            throws ResourceNotFoundException, BusinessException {
        ProductShipmentEntity shipment = shipmentRepository.findById(shipmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Shipment", shipmentId));
        if (shipment.getLocationId() == null) {
            throw new BusinessException("El envío no tiene kiosko destino.");
        }
        List<ShipmentReceiptInventoryAuditResponse.AuditLine> lines = new ArrayList<>();
        List<ProductShipmentDetailEntity> details = shipmentDetailRepository.findByShipmentId(shipmentId);
        for (ProductShipmentDetailEntity detail : details) {
            if (detail == null || detail.getProductId() == null) {
                continue;
            }
            BigDecimal qtyExpected = resolveShipmentLineQuantity(detail);
            if (qtyExpected.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            ProductEntity product = productRepository.findById(detail.getProductId()).orElse(null);
            String lineRef = shipmentReceiptLineReference(shipment, detail);
            lines.add(ShipmentReceiptInventoryAuditResponse.AuditLine.builder()
                    .lineType("PRODUCT")
                    .productId(detail.getProductId())
                    .productCode(product != null ? product.getCode() : null)
                    .productName(product != null ? product.getName() : null)
                    .qtyExpected(qtyExpected)
                    .kioscoStockQty(resolveKioscoStockQty(
                            shipment.getLocationId(), detail.getProductId(), detail.getColorId()))
                    .movementApplied(kioscoInventoryService.hasShipmentReceiptLineApplied(
                            shipment.getLocationId(), shipment.getId(), lineRef))
                    .lineRef(lineRef)
                    .build());
        }
        for (ProductShipmentResponse.PackingItemResponse item : parsePackingItems(shipment.getPackingItems())) {
            if (item == null || item.getMaterialId() == null || item.getQuantity() == null) {
                continue;
            }
            if (item.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            MaterialEntity material = materialRepository.findById(item.getMaterialId()).orElse(null);
            Optional<ProductEntity> productOpt = resolvePackagingProductFromMaterial(item.getMaterialId());
            ProductEntity product = productOpt.orElse(null);
            String lineRef = shipmentPackingLineReference(shipment, item.getMaterialId());
            lines.add(ShipmentReceiptInventoryAuditResponse.AuditLine.builder()
                    .lineType("PACKING")
                    .productId(product != null ? product.getId() : null)
                    .productCode(product != null ? product.getCode() : null)
                    .productName(product != null ? product.getName() : null)
                    .materialId(item.getMaterialId())
                    .materialSku(material != null ? material.getSku() : null)
                    .qtyExpected(item.getQuantity())
                    .kioscoStockQty(product != null
                            ? resolveKioscoStockQty(shipment.getLocationId(), product.getId(), null)
                            : 0)
                    .movementApplied(kioscoInventoryService.hasShipmentReceiptLineApplied(
                            shipment.getLocationId(), shipment.getId(), lineRef))
                    .lineRef(lineRef)
                    .build());
        }
        return ShipmentReceiptInventoryAuditResponse.builder()
                .shipmentId(shipmentId)
                .shipmentNumber(shipment.getShipmentNumber())
                .locationId(shipment.getLocationId())
                .lines(lines)
                .build();
    }

    private int resolveKioscoStockQty(Long locationId, Long productId, Long colorId) {
        if (locationId == null || productId == null) {
            return 0;
        }
        return kioscoStockRepository.findByLocationIdAndProductIdAndColorId(locationId, productId, colorId)
                .map(s -> s.getCurrentStock() != null ? s.getCurrentStock() : 0)
                .orElse(0);
    }

    private BigDecimal resolveShipmentLineQuantity(ProductShipmentDetailEntity detail) {
        if (detail.getQuantityReceived() != null) {
            return detail.getQuantityReceived();
        }
        return detail.getQuantity() != null ? detail.getQuantity() : BigDecimal.ZERO;
    }

    private boolean repairShipmentProductLineIfMissing(
            ProductShipmentEntity shipment,
            ProductShipmentDetailEntity detail,
            BigDecimal qtyExpected,
            boolean force) throws ResourceNotFoundException, BusinessException {
        String lineRef = shipmentReceiptLineReference(shipment, detail);
        boolean movementApplied = kioscoInventoryService.hasShipmentReceiptLineApplied(
                shipment.getLocationId(), shipment.getId(), lineRef);
        if (movementApplied) {
            if (!force) {
                return false;
            }
            int currentStock = resolveKioscoStockQty(
                    shipment.getLocationId(), detail.getProductId(), detail.getColorId());
            if (currentStock >= qtyExpected.intValue()) {
                return false;
            }
            BigDecimal missing = qtyExpected.subtract(BigDecimal.valueOf(currentStock));
            applyKioscoReceiptLineOnly(shipment, detail, missing, lineRef);
            return true;
        }
        boolean kardexApplied = productInventoryService.hasProductKardexMovement(
                "SHIPMENT", shipment.getId(), "TRANSFER_IN",
                detail.getProductId(), shipment.getLocationId(), detail.getColorId(), lineRef);
        if (kardexApplied) {
            applyKioscoReceiptLineOnly(shipment, detail, qtyExpected, lineRef);
            return true;
        }
        applyReceiptInventoryForDetail(shipment, detail, qtyExpected, lineRef);
        return true;
    }

    private void applyKioscoReceiptLineOnly(
            ProductShipmentEntity shipment,
            ProductShipmentDetailEntity detail,
            BigDecimal qtyExpected,
            String lineRef) throws BusinessException, ResourceNotFoundException {
        String sizeKey = detail.getSizeLabel() != null ? detail.getSizeLabel().trim() : "";
        String sizeKeyForInventory = sizeKey.isEmpty() ? null : sizeKey;
        kioscoInventoryService.registrarEntradaDesdeIntegracion(
                shipment.getLocationId(),
                detail.getProductId(),
                detail.getColorId(),
                qtyExpected,
                shipment.getId(),
                securityUtil.getCurrentUserId(),
                sizeKeyForInventory,
                lineRef);
    }

    private void applyReceiptInventoryForDetail(
            ProductShipmentEntity shipment,
            ProductShipmentDetailEntity detail,
            BigDecimal qtyReceived) throws ResourceNotFoundException, BusinessException {
        applyReceiptInventoryForDetail(
                shipment, detail, qtyReceived, shipmentReceiptLineReference(shipment, detail));
    }

    private void applyReceiptInventoryForDetail(
            ProductShipmentEntity shipment,
            ProductShipmentDetailEntity detail,
            BigDecimal qtyReceived,
            String lineRef) throws ResourceNotFoundException, BusinessException {
        if (kioscoInventoryService.hasShipmentReceiptLineApplied(
                shipment.getLocationId(), shipment.getId(), lineRef)
                && productInventoryService.hasProductKardexMovement(
                "SHIPMENT", shipment.getId(), "TRANSFER_IN",
                detail.getProductId(), shipment.getLocationId(), detail.getColorId(), lineRef)) {
            return;
        }
        if (productInventoryService.hasProductKardexMovement(
                "SHIPMENT", shipment.getId(), "TRANSFER_IN",
                detail.getProductId(), shipment.getLocationId(), detail.getColorId(), lineRef)) {
            applyKioscoReceiptLineOnly(shipment, detail, qtyReceived, lineRef);
            return;
        }

        String sizeKey = detail.getSizeLabel() != null ? detail.getSizeLabel().trim() : "";
        String sizeKeyForInventory = sizeKey.isEmpty() ? null : sizeKey;

        BigDecimal before = productInventoryService
                .getInventoryByProductAndLocationAndColor(
                        detail.getProductId(), shipment.getLocationId(), detail.getColorId())
                .getQuantity();
        productInventoryService.incrementInventory(
                detail.getProductId(),
                shipment.getLocationId(),
                detail.getColorId(),
                qtyReceived,
                null,
                "SHIPMENT",
                shipment.getId(),
                shipment.getShipmentNumber(),
                "Recepcion de envio en kiosko",
                sizeKeyForInventory);
        kioscoInventoryService.registrarEntradaDesdeIntegracion(
                shipment.getLocationId(),
                detail.getProductId(),
                detail.getColorId(),
                qtyReceived,
                shipment.getId(),
                securityUtil.getCurrentUserId(),
                sizeKeyForInventory,
                lineRef);
        BigDecimal after = productInventoryService
                .getInventoryByProductAndLocationAndColor(
                        detail.getProductId(), shipment.getLocationId(), detail.getColorId())
                .getQuantity();

        productInventoryService.recordProductMovementIfAbsent(
                detail.getProductId(),
                shipment.getLocationId(),
                detail.getColorId(),
                "TRANSFER_IN",
                qtyReceived,
                before,
                after,
                null,
                "SHIPMENT",
                shipment.getId(),
                lineRef,
                "Recepcion de envio en kiosko"
        );
    }

    private void applyReceiptPackingItemsToKioskStock(
            ProductShipmentEntity shipment,
            List<ProductShipmentDetailEntity> details) throws ResourceNotFoundException, BusinessException {
        Set<Long> receivedProductIds = new HashSet<>();
        if (details != null) {
            for (ProductShipmentDetailEntity detail : details) {
                if (detail == null || detail.getProductId() == null) {
                    continue;
                }
                BigDecimal qtyReceived = detail.getQuantityReceived() != null
                        ? detail.getQuantityReceived()
                        : (detail.getQuantity() != null ? detail.getQuantity() : BigDecimal.ZERO);
                if (qtyReceived.compareTo(BigDecimal.ZERO) > 0) {
                    receivedProductIds.add(detail.getProductId());
                }
            }
        }

        for (ProductShipmentResponse.PackingItemResponse item : parsePackingItems(shipment.getPackingItems())) {
            if (item == null || item.getMaterialId() == null || item.getQuantity() == null) {
                continue;
            }
            if (item.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            ProductEntity product = ensurePackagingProductFromMaterial(item.getMaterialId())
                    .orElseThrow(() -> new BusinessException(
                            "No se pudo registrar el empaque SUM- en catálogo kiosko (materialId="
                                    + item.getMaterialId()
                                    + "). Verifique que el material tenga SKU SUM- válido."));
            if (receivedProductIds.contains(product.getId())) {
                continue;
            }
            applyReceiptInventoryForPackagingProduct(shipment, product, item.getQuantity(), item.getMaterialId());
        }
    }

    private int repairDeliveredShipmentPackingInventory(
            ProductShipmentEntity shipment,
            List<ProductShipmentDetailEntity> details,
            boolean force,
            List<String> warnings) throws ResourceNotFoundException, BusinessException {
        List<ProductShipmentResponse.PackingItemResponse> packingItems = parsePackingItems(shipment.getPackingItems());
        if (packingItems.isEmpty()) {
            return 0;
        }

        Set<Long> receivedProductIds = new HashSet<>();
        if (details != null) {
            for (ProductShipmentDetailEntity detail : details) {
                if (detail == null || detail.getProductId() == null) {
                    continue;
                }
                BigDecimal qtyExpected = resolveShipmentLineQuantity(detail);
                if (qtyExpected.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                String lineRef = shipmentReceiptLineReference(shipment, detail);
                if (kioscoInventoryService.hasShipmentReceiptLineApplied(
                        shipment.getLocationId(), shipment.getId(), lineRef)) {
                    receivedProductIds.add(detail.getProductId());
                }
            }
        }

        int repaired = 0;
        for (ProductShipmentResponse.PackingItemResponse item : packingItems) {
            if (item == null || item.getMaterialId() == null || item.getQuantity() == null) {
                continue;
            }
            if (item.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            MaterialEntity material = materialRepository.findById(item.getMaterialId()).orElse(null);
            String materialSku = material != null && material.getSku() != null
                    ? material.getSku().trim() : ("material#" + item.getMaterialId());
            Optional<ProductEntity> productOpt = ensurePackagingProductFromMaterial(item.getMaterialId());
            if (productOpt.isEmpty()) {
                warnings.add("Empaque SUM- " + materialSku
                        + ": material inválido o SKU sin prefijo SUM-; no se cargó al kiosko.");
                continue;
            }
            ProductEntity product = productOpt.get();
            if (receivedProductIds.contains(product.getId())) {
                continue;
            }
            String lineRef = shipmentPackingLineReference(shipment, item.getMaterialId());
            boolean movementApplied = kioscoInventoryService.hasShipmentReceiptLineApplied(
                    shipment.getLocationId(), shipment.getId(), lineRef);
            if (movementApplied) {
                if (!force) {
                    int currentStock = resolveKioscoStockQty(
                            shipment.getLocationId(), product.getId(), null);
                    if (currentStock < item.getQuantity().intValue()) {
                        warnings.add("Empaque SUM- " + materialSku
                                + ": movimiento registrado pero stock kiosco (" + currentStock
                                + ") es menor al esperado (" + item.getQuantity().intValue()
                                + "). Use sincronización forzada si tiene permiso de administrador.");
                    }
                    continue;
                }
                int currentStock = resolveKioscoStockQty(
                        shipment.getLocationId(), product.getId(), null);
                if (currentStock >= item.getQuantity().intValue()) {
                    continue;
                }
                BigDecimal missing = item.getQuantity().subtract(BigDecimal.valueOf(currentStock));
                applyReceiptInventoryForPackagingProduct(shipment, product, missing, item.getMaterialId());
                repaired++;
                continue;
            }
            applyReceiptInventoryForPackagingProduct(shipment, product, item.getQuantity(), item.getMaterialId());
            repaired++;
        }
        return repaired;
    }

    private void applyReceiptInventoryForPackagingProduct(
            ProductShipmentEntity shipment,
            ProductEntity product,
            BigDecimal qtyReceived,
            Long materialId) throws ResourceNotFoundException, BusinessException {
        String lineRef = shipmentPackingLineReference(shipment, materialId);
        if (kioscoInventoryService.hasShipmentReceiptLineApplied(
                shipment.getLocationId(), shipment.getId(), lineRef)
                && productInventoryService.hasProductKardexMovement(
                "SHIPMENT", shipment.getId(), "TRANSFER_IN",
                product.getId(), shipment.getLocationId(), null, lineRef)) {
            return;
        }
        if (productInventoryService.hasProductKardexMovement(
                "SHIPMENT", shipment.getId(), "TRANSFER_IN",
                product.getId(), shipment.getLocationId(), null, lineRef)) {
            kioscoInventoryService.registrarEntradaDesdeIntegracion(
                    shipment.getLocationId(),
                    product.getId(),
                    null,
                    qtyReceived,
                    shipment.getId(),
                    securityUtil.getCurrentUserId(),
                    null,
                    lineRef);
            return;
        }

        BigDecimal before = productInventoryService
                .getInventoryByProductAndLocationAndColor(
                        product.getId(), shipment.getLocationId(), null)
                .getQuantity();
        productInventoryService.incrementInventory(
                product.getId(),
                shipment.getLocationId(),
                null,
                qtyReceived,
                null,
                "SHIPMENT",
                shipment.getId(),
                shipment.getShipmentNumber(),
                "Recepcion de empaque SUM- en kiosko",
                null);
        kioscoInventoryService.registrarEntradaDesdeIntegracion(
                shipment.getLocationId(),
                product.getId(),
                null,
                qtyReceived,
                shipment.getId(),
                securityUtil.getCurrentUserId(),
                null,
                lineRef);
        BigDecimal after = productInventoryService
                .getInventoryByProductAndLocationAndColor(
                        product.getId(), shipment.getLocationId(), null)
                .getQuantity();
        productInventoryService.recordProductMovementIfAbsent(
                product.getId(),
                shipment.getLocationId(),
                null,
                "TRANSFER_IN",
                qtyReceived,
                before,
                after,
                null,
                "SHIPMENT",
                shipment.getId(),
                lineRef,
                "Recepcion de empaque SUM- en kiosko");
    }

    private Optional<ProductEntity> resolvePackagingProductFromMaterial(Long materialId) {
        if (materialId == null) {
            return Optional.empty();
        }
        MaterialEntity material = materialRepository.findById(materialId).orElse(null);
        if (material == null || material.getSku() == null || material.getSku().isBlank()) {
            return Optional.empty();
        }
        String sku = material.getSku().trim();
        Optional<ProductEntity> product = productRepository.findByCode(sku);
        if (product.isEmpty()) {
            product = productRepository.findByCode(sku.toUpperCase(Locale.ROOT));
        }
        return product;
    }

    /**
     * El stock kiosko y POS usan filas de producto con código SUM-.
     * Los empaques viven como materiales; si no hay producto homólogo, se crea uno ligero
     * (precio 0 — el encargado lo configura después en catálogo o inventario kiosko).
     */
    private Optional<ProductEntity> ensurePackagingProductFromMaterial(Long materialId) {
        Optional<ProductEntity> existing = resolvePackagingProductFromMaterial(materialId);
        if (existing.isPresent()) {
            return existing;
        }
        if (materialId == null) {
            return Optional.empty();
        }
        MaterialEntity material = materialRepository.findById(materialId).orElse(null);
        if (material == null || material.getSku() == null || material.getSku().isBlank()) {
            return Optional.empty();
        }
        String sku = material.getSku().trim();
        if (!ProductCinchoType.isPackagingProductCode(sku)) {
            return Optional.empty();
        }
        String name = material.getName() != null && !material.getName().isBlank()
                ? material.getName().trim()
                : sku;
        Long userId = securityUtil.getCurrentUserId();
        try {
            ProductEntity created = ProductEntity.builder()
                    .code(sku)
                    .name(name)
                    .status("ACTIVE")
                    .salePrice(BigDecimal.ZERO)
                    .discountedPrice(BigDecimal.ZERO)
                    .sellerPrice(BigDecimal.ZERO)
                    .requiresMaterials(false)
                    .createdBy(userId)
                    .updatedBy(userId)
                    .build();
            return Optional.of(productRepository.save(created));
        } catch (DataIntegrityViolationException ex) {
            return resolvePackagingProductFromMaterial(materialId);
        }
    }

    private String shipmentPackingLineReference(ProductShipmentEntity shipment, Long materialId) {
        String number = shipment.getShipmentNumber() != null ? shipment.getShipmentNumber().trim() : "ENV";
        return number + "#P" + materialId;
    }

    private String shipmentReceiptLineReference(ProductShipmentEntity shipment, ProductShipmentDetailEntity detail) {
        String number = shipment.getShipmentNumber() != null ? shipment.getShipmentNumber().trim() : "ENV";
        return number + "#L" + detail.getId();
    }

    private String receiptProductColorKey(Long productId, Long colorId) {
        return productId + ":" + (colorId != null ? colorId : "null");
    }

    /**
     * Obtiene envíos por estado
     */
    public List<ProductShipmentResponse> getShipmentsByStatus(String status) {
        if (status == null || status.isBlank()) {
            return List.of();
        }
        String normalizedStatus = status.trim();
        return shipmentRepository.findAll().stream()
                .filter(s -> normalizedStatus.equalsIgnoreCase(safeTrim(s.getStatus())))
                .map(this::toShipmentResponse)
                .collect(Collectors.toList());
    }

    /**
     * Obtiene envíos en tránsito (SENT).
     * Con {@code kioskLocationId} solo devuelve envíos de esa ubicación (POS kiosko).
     * Sin parámetro: admin ve todos; encargada ve sus kioskos asignados (pantalla distribución).
     */
    public List<ProductShipmentResponse> getShipmentsInTransit(Long kioskLocationId) throws BusinessException {
        return findShipmentsInTransitEntities(kioskLocationId).stream()
                .map(this::toShipmentResponse)
                .collect(Collectors.toList());
    }

    public long countShipmentsInTransit(Long kioskLocationId) throws BusinessException {
        return findShipmentsInTransitEntities(kioskLocationId).size();
    }

    private List<ProductShipmentEntity> findShipmentsInTransitEntities(Long kioskLocationId) throws BusinessException {
        Set<Long> scopedLocationIds = resolveReceiptLocationScope();
        if (kioskLocationId != null) {
            assertMayAccessKioskLocation(kioskLocationId, scopedLocationIds);
            return shipmentRepository.findByStatusIgnoreCaseAndLocationId("SENT", kioskLocationId);
        }
        if (scopedLocationIds == null) {
            return shipmentRepository.findByStatusIgnoreCase("SENT");
        }
        if (scopedLocationIds.isEmpty()) {
            return List.of();
        }
        return shipmentRepository.findByStatusIgnoreCaseAndLocationIdIn("SENT", scopedLocationIds);
    }

    private boolean matchesReceiptLocationScope(Long shipmentLocationId, Set<Long> filterLocationIds) {
        if (filterLocationIds == null) {
            return true;
        }
        if (shipmentLocationId == null) {
            return false;
        }
        return filterLocationIds.stream().anyMatch(id -> java.util.Objects.equals(id, shipmentLocationId));
    }

    private void assertMayAccessKioskLocation(Long kioskLocationId, Set<Long> scopedLocationIds)
            throws BusinessException {
        if (scopedLocationIds == null) {
            return;
        }
        if (kioskLocationId == null
                || scopedLocationIds.stream().noneMatch(id -> java.util.Objects.equals(id, kioskLocationId))) {
            throw new BusinessException("No tienes acceso para ver recepciones de este kiosko.");
        }
    }

    private void assertMayConfirmReceiptForLocation(Long locationId) throws BusinessException {
        Set<Long> scopedLocationIds = resolveReceiptLocationScope();
        assertMayAccessKioskLocation(locationId, scopedLocationIds);
    }

    /**
     * null = sin filtro (admin); conjunto vacío = sin kioskos asignados.
     */
    private Set<Long> resolveReceiptLocationScope() throws BusinessException {
        Long userId = securityUtil.getCurrentUserId();
        if (userId == null) {
            throw new BusinessException("No se pudo identificar el usuario autenticado.");
        }
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("No se encontró el usuario autenticado."));
        if (isAdminUser(user)) {
            return null;
        }
        List<LocationEntity> assigned = locationRepository.findByEncargadoIdOrderByNameAsc(userId);
        if (assigned == null || assigned.isEmpty()) {
            throw new BusinessException("Tu usuario no tiene kiosko asignado para confirmar recepciones.");
        }
        return assigned.stream()
                .map(LocationEntity::getId)
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toCollection(HashSet::new));
    }

    private boolean isAdminUser(UserEntity user) {
        if (user == null || user.getRoles() == null) {
            return false;
        }
        return user.getRoles().stream()
                .filter(role -> role != null && role.getName() != null)
                .map(role -> role.getName().trim().toUpperCase(Locale.ROOT))
                .anyMatch(name -> name.contains("ADMIN"));
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    // ========== HELPER METHODS ==========

    /**
     * Genera número de distribución automáticamente basado en el ID secuencial
     */
    private String generateDistributionNumber() {
        // Obtener el siguiente ID disponible (contar distribuciones existentes + 1)
        long nextId = distributionRepository.count() + 1;
        String prefix = "DIST";
        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        
        // Formato: DIST-YYYYMMDD-001 (basado en ID secuencial)
        return String.format("%s-%s-%03d", prefix, dateStr, nextId);
    }

    /**
     * Número de envío para constancia OPI sin kiosko: ENVI-nnnnn (mismo correlativo que en la OP).
     */
    private String generateShipmentNumberForOpiDocument(ProductionOrderEntity order) {
        opiVendorShipmentNumberService.assignIfMissing(order);
        productionOrderRepository.save(order);
        String v = order.getVendorShipmentNumber();
        if (v != null && !v.isBlank()) {
            return v.trim();
        }
        return opiVendorShipmentNumberService.nextNumber();
    }

    /**
     * Genera número de envío automáticamente
     */
    private String generateShipmentNumber(LocationEntity location) {
        String kioskCode = String.valueOf(location.getCode() == null ? "" : location.getCode()).trim();
        if (kioskCode.isEmpty()) {
            kioskCode = "KIOSKO_" + location.getId();
        }
        kioskCode = kioskCode.replaceAll("[^A-Za-z0-9_-]", "_").toUpperCase();

        int maxSequence = shipmentRepository.findByLocationIdOrderByIdAsc(location.getId()).stream()
                .map(ProductShipmentEntity::getShipmentNumber)
                .mapToInt(this::extractTrailingSequence)
                .max()
                .orElse(0);
        int sequence = maxSequence + 1;
        return String.format("%s-ENV-%05d", kioskCode, sequence);
    }

    /**
     * Convierte entidad a DTO de respuesta
     */
    private ProductDistributionResponse toDistributionResponse(ProductDistributionEntity entity) {
        List<ProductShipmentEntity> shipments = shipmentRepository.findByDistributionId(entity.getId());
        
        // Buscar si hay una orden de producción vinculada
        Optional<ProductionOrderEntity> linkedOrder = productionOrderRepository.findByDistributionId(entity.getId());
        
        return ProductDistributionResponse.builder()
                .id(entity.getId())
                .distributionNumber(entity.getDistributionNumber())
                .distributionDate(entity.getDistributionDate())
                .status(entity.getStatus())
                .description(entity.getDescription())
                .shipmentCount(shipments.size())
                .shipments(shipments.stream()
                        .map(this::toShipmentResponse)
                        .collect(Collectors.toList()))
                .productionOrderId(linkedOrder.map(ProductionOrderEntity::getId).orElse(null))
                .productionOrderCode(linkedOrder.map(ProductionOrderEntity::getCode).orElse(null))
                .createdAt(entity.getCreatedAt())
                .createdBy(entity.getCreatedBy())
                .createdByName(resolveUserDisplayName(
                        entity.getCreatedBy() != null ? entity.getCreatedBy() : entity.getUpdatedBy()))
                .updatedAt(entity.getUpdatedAt())
                .updatedBy(entity.getUpdatedBy())
                .build();
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

    /**
     * Convierte entidad de envío a DTO de respuesta
     */
    private ProductShipmentResponse toShipmentResponse(ProductShipmentEntity entity) {
        List<ProductShipmentDetailEntity> details = shipmentDetailRepository.findByShipmentId(entity.getId());
        LocationEntity location = entity.getLocationId() == null
                ? null
                : locationRepository.findById(entity.getLocationId()).orElse(null);
        ProductDistributionEntity distribution = entity.getDistributionId() == null
                ? null
                : distributionRepository.findById(entity.getDistributionId()).orElse(null);
        ProductionOrderEntity linkedPo = entity.getProductionOrderId() == null
                ? null
                : productionOrderRepository.findById(entity.getProductionOrderId()).orElse(null);

        String locationName = location != null ? location.getName() : virtualDestinationForDirectShipment(linkedPo, entity);
        String locationCode = location != null ? location.getCode() : null;

        return ProductShipmentResponse.builder()
                .id(entity.getId())
                .distributionId(entity.getDistributionId())
                .productionOrderId(entity.getProductionOrderId())
                .partialReleaseId(entity.getPartialReleaseId())
                .productionOrderCode(linkedPo != null ? linkedPo.getCode() : null)
                .distributionNumber(distribution != null ? distribution.getDistributionNumber() : null)
                .shipmentNumber(entity.getShipmentNumber())
                .locationId(entity.getLocationId())
                .locationCode(locationCode)
                .locationName(locationName)
                .status(entity.getStatus())
                .notes(entity.getNotes())
                .packingItems(parsePackingItems(entity.getPackingItems()))
                .sentAt(entity.getSentAt())
                .sentBy(entity.getSentBy())
                .receivedAt(entity.getReceivedAt())
                .receivedBy(entity.getReceivedBy())
                .receivedNotes(entity.getReceivedNotes())
                .products(details.stream()
                        .map(this::toShipmentDetailResponse)
                        .collect(Collectors.toList()))
                .createdAt(entity.getCreatedAt())
                .createdBy(entity.getCreatedBy())
                .createdByName(resolveUserDisplayName(
                        entity.getCreatedBy() != null ? entity.getCreatedBy() : entity.getUpdatedBy()))
                .updatedAt(entity.getUpdatedAt())
                .updatedBy(entity.getUpdatedBy())
                .build();
    }
    private static final String PACKAGING_PRODUCT_CODE_PREFIX = "SUM";

    private boolean isPackagingProduct(Long productId) {
        if (productId == null) return false;
        ProductEntity product = productRepository.findById(productId).orElse(null);
        if (product == null) return false;
        String code = product.getCode() != null ? product.getCode().trim().toUpperCase() : "";
        return code.startsWith(PACKAGING_PRODUCT_CODE_PREFIX);
    }

    private String buildDispatchStockBreakdown(
            Long productId,
            Long colorId,
            String sizeLabel,
            List<LocationEntity> dispatchWarehouses) {
        List<String> parts = new ArrayList<>();
        for (LocationEntity loc : dispatchWarehouses) {
            if (loc == null) {
                continue;
            }
            BigDecimal qty = productInventoryService.getAvailableQuantity(productId, loc.getId(), colorId, sizeLabel);
            String label = loc.getName() != null && !loc.getName().isBlank() ? loc.getName() : loc.getCode();
            parts.add(label + " " + qty);
        }
        return String.join(", ", parts);
    }


    private LocationEntity getFinishedGoodsLocation() throws BusinessException {
        Optional<InventoryLocationTypeEntity> bodegaType = inventoryLocationTypeRepository.findByCodeAndIsActiveTrue("BODEGA_PT");
        if (bodegaType.isEmpty()) {
            throw new BusinessException("No existe el tipo de ubicacion BODEGA_PT.");
        }
        return locationRepository.findAll().stream()
                .filter(loc -> bodegaType.get().getCode().equalsIgnoreCase(loc.getCode()))
                .findFirst()
                .orElseThrow(() -> new BusinessException("No existe una ubicacion configurada para BODEGA_PT."));
    }

    /**
     * Convierte entidad de detalle a DTO de respuesta
     */
    private ProductShipmentDetailResponse toShipmentDetailResponse(ProductShipmentDetailEntity entity) {
        ProductEntity product = entity.getProductId() == null
                ? null
                : productRepository.findById(entity.getProductId()).orElse(null);
        String colorName = null;
        if (entity.getColorId() != null) {
            ColorEntity color = colorRepository.findById(entity.getColorId()).orElse(null);
            colorName = color != null ? color.getName() : null;
        }
        Long categoryId = product != null ? product.getCategoryId() : null;
        String categoryName = resolveProductCategoryName(categoryId);

        return ProductShipmentDetailResponse.builder()
                .id(entity.getId())
                .shipmentId(entity.getShipmentId())
                .productId(entity.getProductId())
                .productCode(product != null ? product.getCode() : null)
                .productName(product != null ? product.getName() : null)
                .productImageUrl(product != null ? product.getImageUrl() : null)
                .categoryId(categoryId)
                .categoryName(categoryName)
                .colorId(entity.getColorId())
                .colorName(colorName)
                .size(entity.getSizeLabel())
                .quantity(entity.getQuantity())
                .quantityReceived(entity.getQuantityReceived())
                .quantityDifference(entity.getQuantityDifference())
                .receivedLineNotes(entity.getReceivedLineNotes())
                .build();
    }

    private String resolveProductCategoryName(Long categoryId) {
        if (categoryId == null) {
            return null;
        }
        return productCategoryRepository.findById(categoryId)
                .map(ProductCategoryEntity::getName)
                .orElse(null);
    }

    private String normalizeSize(String size) {
        return size == null ? "" : size.trim().toUpperCase();
    }

    private int extractTrailingSequence(String shipmentNumber) {
        if (shipmentNumber == null) return 0;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+)$").matcher(shipmentNumber.trim());
        if (!matcher.find()) return 0;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private List<ProductShipmentRequest.ProductShipmentDetailRequest> normalizeShipmentProducts(
            List<ProductShipmentRequest.ProductShipmentDetailRequest> products)
            throws ResourceNotFoundException, BusinessException {
        Map<String, BigDecimal> groupedQuantities = new java.util.LinkedHashMap<>();
        Map<String, Long> keyToProductId = new HashMap<>();
        Map<String, Long> keyToColorId = new HashMap<>();
        Map<String, String> keyToSize = new HashMap<>();

        if (products != null) {
            for (ProductShipmentRequest.ProductShipmentDetailRequest productRequest : products) {
                if (productRequest == null || productRequest.getProductId() == null) continue;
                if (productRequest.getQuantity() == null ||
                        productRequest.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                if (!productRepository.existsById(productRequest.getProductId())) {
                    throw new ResourceNotFoundException("Product", productRequest.getProductId());
                }
                String normalizedSize = normalizeSize(productRequest.getSize());
                String key = productRequest.getProductId() + ":" +
                        (productRequest.getColorId() == null ? "null" : productRequest.getColorId()) + ":" +
                        normalizedSize;
                groupedQuantities.merge(key, productRequest.getQuantity(), BigDecimal::add);
                keyToProductId.put(key, productRequest.getProductId());
                keyToColorId.put(key, productRequest.getColorId());
                keyToSize.put(key, normalizedSize);
            }
        }

        return groupedQuantities.entrySet().stream()
                .map(entry -> ProductShipmentRequest.ProductShipmentDetailRequest.builder()
                        .productId(keyToProductId.get(entry.getKey()))
                        .colorId(keyToColorId.get(entry.getKey()))
                        .size(keyToSize.getOrDefault(entry.getKey(), ""))
                        .quantity(entry.getValue())
                        .build())
                .collect(Collectors.toList());
    }

    private String serializePackingItems(List<ProductShipmentRequest.PackingItemRequest> packingItems) {
        try {
            if (packingItems == null || packingItems.isEmpty()) {
                return null;
            }
            List<ProductShipmentRequest.PackingItemRequest> filtered = packingItems.stream()
                    .filter(item -> item != null
                            && item.getMaterialId() != null
                            && item.getMaterialId() > 0
                            && item.getQuantity() != null
                            && item.getQuantity().compareTo(BigDecimal.ZERO) > 0)
                    .collect(Collectors.toList());
            if (filtered.isEmpty()) {
                return null;
            }
            return objectMapper.writeValueAsString(filtered);
        } catch (Exception _err) {
            return null;
        }
    }

    private void assertNoDuplicateLfShipment(Long productionOrderId, Long locationId) throws BusinessException {
        List<ProductShipmentEntity> existing = shipmentRepository.findByProductionOrderId(productionOrderId);
        Optional<ProductShipmentEntity> confirmed = existing.stream()
                .filter(s -> java.util.Objects.equals(locationId, s.getLocationId()))
                .filter(s -> !"DRAFT".equalsIgnoreCase(String.valueOf(s.getStatus())))
                .findFirst();
        if (confirmed.isPresent()) {
            throw new BusinessException(
                    "Esta orden ya tiene un envío generado ("
                            + confirmed.get().getShipmentNumber()
                            + "). Use Preparar envíos para reimprimir o consultar el envío existente.");
        }
    }

    /**
     * Número en product_shipment para cinchos Luis Felipe: siempre ENVP-xxxxx-ENV-nnnnn (o OPC-ENV si no hay ENVP).
     * El impreso sigue usando vendorShipmentNumber de la OP (ENVP puro), sin colisión con envíos de otras OP.
     */
    private String allocateLfCinchoPhysicalShipmentNumber(ProductionOrderEntity order) throws BusinessException {
        String envp = order.getVendorShipmentNumber();
        String base = (envp != null && !envp.isBlank())
                ? envp.trim().toUpperCase()
                : (order.getCode() == null ? "OPC" : order.getCode().trim().toUpperCase().replaceAll("[^A-Za-z0-9_-]", "_"));
        return nextUniqueShipmentNumberWithPrefix(order.getId(), base + "-ENV-");
    }

    private String nextUniqueShipmentNumberWithPrefix(Long productionOrderId, String prefix) throws BusinessException {
        int maxOnOrder = shipmentRepository.findByProductionOrderId(productionOrderId).stream()
                .map(ProductShipmentEntity::getShipmentNumber)
                .filter(n -> n != null && n.toUpperCase().startsWith(prefix.toUpperCase()))
                .mapToInt(this::extractTrailingSequence)
                .max()
                .orElse(0);
        for (int seq = maxOnOrder + 1; seq < maxOnOrder + 10000; seq++) {
            String candidate = String.format("%s%05d", prefix, seq);
            if (!shipmentRepository.existsByShipmentNumber(candidate)) {
                return candidate;
            }
        }
        throw new BusinessException("No se pudo generar un número de envío único para la orden.");
    }

    private ProductionOrderEntity ensureOpvVendorShipmentNumberOnOrder(ProductionOrderEntity order) {
        String before = order.getVendorShipmentNumber();
        opvVendorShipmentNumberService.assignIfMissing(order);
        boolean reconciled = opvVendorShipmentNumberService.reconcileVendorNumberIfColliding(order);
        if (reconciled || !java.util.Objects.equals(before, order.getVendorShipmentNumber())) {
            return productionOrderRepository.save(order);
        }
        return order;
    }

    private boolean isLuisFelipeVendorOrder(ProductionOrderEntity order) {
        if (order == null) {
            return false;
        }
        String seller = String.valueOf(order.getSellerName() == null ? "" : order.getSellerName()).trim().toUpperCase();
        return seller.contains("LUIS FELIPE");
    }

    private String resolveLfDefaultDestination(ProductionOrderEntity order) {
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

    private List<ProductShipmentRequest.PackingItemRequest> parsePackingItemsFromOrderObservations(
            ProductionOrderEntity order) {
        if (order == null || order.getObservations() == null) {
            return List.of();
        }
        String packingRaw = "";
        for (String line : order.getObservations().lines().toList()) {
            if (line.startsWith(OPV_PACKING_TAG)) {
                packingRaw = line.substring(OPV_PACKING_TAG.length()).trim();
                break;
            }
        }
        if (packingRaw.isEmpty()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> parsed = objectMapper.readValue(
                    packingRaw, new TypeReference<List<Map<String, Object>>>() {});
            List<ProductShipmentRequest.PackingItemRequest> items = new ArrayList<>();
            for (Map<String, Object> item : parsed) {
                if (item == null || item.get("materialId") == null) {
                    continue;
                }
                Long materialId = Long.valueOf(String.valueOf(item.get("materialId")));
                BigDecimal quantity = item.get("quantity") == null
                        ? BigDecimal.ZERO
                        : new BigDecimal(String.valueOf(item.get("quantity")));
                BigDecimal unitPrice = item.get("unitPrice") == null
                        ? BigDecimal.ZERO
                        : new BigDecimal(String.valueOf(item.get("unitPrice")));
                if (quantity.compareTo(BigDecimal.ZERO) > 0) {
                    items.add(ProductShipmentRequest.PackingItemRequest.builder()
                            .materialId(materialId)
                            .quantity(quantity)
                            .unitPrice(unitPrice)
                            .build());
                }
            }
            return items;
        } catch (Exception _err) {
            return List.of();
        }
    }

    private List<ProductShipmentResponse.PackingItemResponse> parsePackingItems(String packingItemsRaw) {
        try {
            if (packingItemsRaw == null || packingItemsRaw.trim().isEmpty()) {
                return List.of();
            }
            List<ProductShipmentRequest.PackingItemRequest> parsed = objectMapper.readValue(
                    packingItemsRaw,
                    new TypeReference<List<ProductShipmentRequest.PackingItemRequest>>() {}
            );
            return parsed.stream()
                    .filter(item -> item != null
                            && item.getMaterialId() != null
                            && item.getMaterialId() > 0
                            && item.getQuantity() != null
                            && item.getQuantity().compareTo(BigDecimal.ZERO) > 0)
                    .map(item -> ProductShipmentResponse.PackingItemResponse.builder()
                            .materialId(item.getMaterialId())
                            .quantity(item.getQuantity())
                            .unitPrice(item.getUnitPrice())
                            .build())
                    .collect(Collectors.toList());
        } catch (Exception _err) {
            return List.of();
        }
    }
}

