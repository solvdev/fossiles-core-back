package com.fossiles.fossilescorebackend.application.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fossiles.fossilescorebackend.application.dto.request.ProductDistributionRequest;
import com.fossiles.fossilescorebackend.application.dto.request.ProductShipmentRequest;
import com.fossiles.fossilescorebackend.application.dto.response.ProductDistributionResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ProductInventoryLocationResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ProductShipmentDetailResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ProductShipmentResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class ProductDistributionService {

    private static final java.util.Set<String> TERMINAL_SHIPMENT_STATUSES =
            java.util.Set.of("SENT", "DELIVERED", "COMPLETED", "RECEIVED", "CANCELLED");

    private final ProductDistributionRepository distributionRepository;
    private final ProductShipmentRepository shipmentRepository;
    private final ProductShipmentDetailRepository shipmentDetailRepository;
    private final ProductRepository productRepository;
    private final LocationRepository locationRepository;
    private final ProductInventoryLocationRepository productInventoryLocationRepository;
    private final ProductInventoryKardexRepository productInventoryKardexRepository;
    private final InventoryLocationTypeRepository inventoryLocationTypeRepository;
    private final ProductInventoryService productInventoryService;
    private final ProductionOrderRepository productionOrderRepository;
    private final ProductionOrderItemRepository productionOrderItemRepository;
    private final DocumentSeriesRepository documentSeriesRepository;
    private final ColorRepository colorRepository;
    private final ProductCategoryRepository productCategoryRepository;
    private final SecurityUtil securityUtil;
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
        
        ProductDistributionEntity entity = ProductDistributionEntity.builder()
                .distributionNumber(distributionNumber)
                .distributionDate(request.getDistributionDate())
                .status(request.getStatus() != null ? request.getStatus() : "DRAFT")
                .description(request.getDescription())
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
            if (!request.getLocationId().equals(targetShipment.getLocationId())) {
                throw new BusinessException("El envío seleccionado no pertenece al kiosko indicado.");
            }
        }

        if (targetShipment != null) {
            shipmentDetailRepository.deleteByShipmentId(targetShipment.getId());
            if (!normalizedProducts.isEmpty()) {
                updateShipmentProducts(targetShipment.getId(), normalizedProducts);
            }
            targetShipment.setNotes(request.getNotes());
            targetShipment.setPackingItems(serializePackingItems(request.getPackingItems()));
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
        ProductShipmentEntity shipment = ProductShipmentEntity.builder()
                .distributionId(distributionId)
                .shipmentNumber(shipmentNumber)
                .locationId(request.getLocationId())
                .status("DRAFT")
                .notes(request.getNotes())
                .packingItems(serializePackingItems(request.getPackingItems()))
                .build();
        ProductShipmentEntity saved = shipmentRepository.save(shipment);
        if (!normalizedProducts.isEmpty()) {
            updateShipmentProducts(saved.getId(), normalizedProducts);
        }
        return toShipmentResponse(saved);
    }

    /**
     * Actualiza los productos de un envío
     */
    public ProductShipmentResponse updateShipmentProducts(Long shipmentId, List<ProductShipmentRequest.ProductShipmentDetailRequest> products) 
            throws ResourceNotFoundException, BusinessException {
        ProductShipmentEntity shipment = shipmentRepository.findById(shipmentId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductShipment", shipmentId));
        
        // Eliminar productos existentes
        shipmentDetailRepository.deleteByShipmentId(shipmentId);

        List<ProductShipmentRequest.ProductShipmentDetailRequest> normalized = normalizeShipmentProducts(products);

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
        
        return toShipmentResponse(shipment);
    }

    /**
     * Elimina un envío
     */
    public void deleteShipment(Long id) throws ResourceNotFoundException {
        ProductShipmentEntity entity = shipmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ProductShipment", id));
        shipmentDetailRepository.deleteByShipmentId(id);
        shipmentRepository.delete(entity);
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
     * Marca un envío como SENT (en tránsito)
     */
    public ProductShipmentResponse sendShipment(Long shipmentId) throws ResourceNotFoundException, BusinessException {
        ProductShipmentEntity shipment = shipmentRepository.findById(shipmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Shipment", shipmentId));
        String currentStatus = shipment.getStatus() == null ? "" : shipment.getStatus().trim().toUpperCase();

        if (TERMINAL_SHIPMENT_STATUSES.contains(currentStatus)) {
            throw new BusinessException("El envío ya fue procesado y no puede prepararse de nuevo. Estado actual: " + currentStatus);
        }

        if (!"CONFIRMED".equals(currentStatus)) {
            throw new BusinessException("El envío debe estar confirmado para poder enviarse. Estado actual: " + currentStatus);
        }

        LocationEntity finishedGoodsLocation = getFinishedGoodsLocation();
        LocationEntity targetLocation = locationRepository.findById(shipment.getLocationId()).orElse(null);
        List<ProductShipmentDetailEntity> details = shipmentDetailRepository.findByShipmentId(shipmentId);
        if (details.isEmpty()) {
            throw new BusinessException("No se puede enviar un envío sin productos.");
        }

        // Pre-validate stock in Bodega PT to fail fast with clear shortages per product/color.
        List<String> shortages = new java.util.ArrayList<>();
        for (ProductShipmentDetailEntity detail : details) {
            BigDecimal qtyToSend = detail.getQuantity() != null ? detail.getQuantity() : BigDecimal.ZERO;
            if (qtyToSend.compareTo(BigDecimal.ZERO) <= 0) continue;
            if (isPackagingProduct(detail.getProductId())) continue;
            BigDecimal available = productInventoryService
                    .getInventoryByProductAndLocationAndColor(detail.getProductId(), finishedGoodsLocation.getId(), detail.getColorId())
                    .getQuantity();
            if (available.compareTo(qtyToSend) < 0) {
                ProductEntity product = productRepository.findById(detail.getProductId()).orElse(null);
                String productName = product != null ? product.getCode() + " - " + product.getName() : "Producto #" + detail.getProductId();
                String colorName = "";
                if (detail.getColorId() != null) {
                    ColorEntity color = colorRepository.findById(detail.getColorId()).orElse(null);
                    colorName = color != null ? " (" + color.getName() + ")" : " (Color #" + detail.getColorId() + ")";
                }
                shortages.add(productName + colorName + ": disponible " + available + ", requerido " + qtyToSend);
            }
        }
        if (!shortages.isEmpty()) {
            throw new BusinessException("Stock insuficiente en Bodega PT para enviar:\n• " + String.join("\n• ", shortages));
        }

        for (ProductShipmentDetailEntity detail : details) {
            BigDecimal qtyToSend = detail.getQuantity() != null ? detail.getQuantity() : BigDecimal.ZERO;
            if (qtyToSend.compareTo(BigDecimal.ZERO) <= 0) continue;
            if (isPackagingProduct(detail.getProductId())) continue;

            BigDecimal before = productInventoryService
                    .getInventoryByProductAndLocationAndColor(detail.getProductId(), finishedGoodsLocation.getId(), detail.getColorId())
                    .getQuantity();

            productInventoryService.decrementInventory(
                    detail.getProductId(),
                    finishedGoodsLocation.getId(),
                    detail.getColorId(),
                    qtyToSend,
                    "SHIPMENT",
                    shipment.getId(),
                    shipment.getShipmentNumber(),
                    "Salida a envio en transito hacia " + (targetLocation != null ? targetLocation.getName() : "kiosko")
            );

            BigDecimal after = productInventoryService
                    .getInventoryByProductAndLocationAndColor(detail.getProductId(), finishedGoodsLocation.getId(), detail.getColorId())
                    .getQuantity();

            productInventoryService.recordMovement(
                    detail.getProductId(),
                    finishedGoodsLocation.getId(),
                    detail.getColorId(),
                    "TRANSFER_OUT",
                    qtyToSend.negate(),
                    before,
                    after,
                    null,
                    "SHIPMENT",
                    shipment.getId(),
                    shipment.getShipmentNumber(),
                    "Salida de bodega PT a envio en transito"
            );
        }

        shipment.setStatus("SENT");
        shipment.setSentAt(LocalDateTime.now());
        shipment.setSentBy(securityUtil.getCurrentUserId());
        ProductShipmentEntity saved = shipmentRepository.save(shipment);

        markDistributionDispatchedIfAllSent(saved.getDistributionId());

        return toShipmentResponse(saved);
    }

    private void markDistributionDispatchedIfAllSent(Long distributionId) {
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
    public ProductShipmentResponse confirmReceipt(Long shipmentId, Map<String, Object> body)
            throws ResourceNotFoundException, BusinessException {
        ProductShipmentEntity shipment = shipmentRepository.findById(shipmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Shipment", shipmentId));

        if (!"SENT".equals(shipment.getStatus())) {
            throw new BusinessException("El envío debe estar en tránsito para confirmar recepción. Estado actual: " + shipment.getStatus());
        }

        String receivedNotes = body.get("notes") != null ? body.get("notes").toString() : null;
        shipment.setStatus("DELIVERED");
        shipment.setReceivedAt(LocalDateTime.now());
        shipment.setReceivedBy(securityUtil.getCurrentUserId());
        shipment.setReceivedNotes(receivedNotes);
        shipmentRepository.save(shipment);

        // Incrementar inventario de productos en la ubicación (kiosco)
        List<ProductShipmentDetailEntity> details = shipmentDetailRepository.findByShipmentId(shipmentId);
        for (ProductShipmentDetailEntity detail : details) {
            BigDecimal qtyReceived = detail.getQuantity(); // Por defecto = cantidad enviada

            // Si se proporcionaron cantidades recibidas específicas
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> itemReceipts = body.get("items") != null
                    ? (List<Map<String, Object>>) body.get("items") : null;
            if (itemReceipts != null) {
                for (Map<String, Object> ir : itemReceipts) {
                    Long detailId = Long.valueOf(ir.get("detailId").toString());
                    if (detailId.equals(detail.getId())) {
                        qtyReceived = new BigDecimal(ir.get("quantityReceived").toString());
                        break;
                    }
                }
            }

            if (qtyReceived.compareTo(BigDecimal.ZERO) < 0) {
                throw new BusinessException("La cantidad recibida no puede ser negativa para el detalle " + detail.getId());
            }
            if (qtyReceived.compareTo(detail.getQuantity()) > 0) {
                throw new BusinessException("La cantidad recibida no puede superar la enviada para el detalle " + detail.getId());
            }

            detail.setQuantityReceived(qtyReceived);
            detail.setQuantityDifference(detail.getQuantity().subtract(qtyReceived));
            shipmentDetailRepository.save(detail);

            if (isPackagingProduct(detail.getProductId())) {
                continue;
            }

            // Incrementar inventario en ubicación del kiosco
            BigDecimal before = productInventoryService
                    .getInventoryByProductAndLocationAndColor(detail.getProductId(), shipment.getLocationId(), detail.getColorId())
                    .getQuantity();
            productInventoryService.incrementInventory(
                    detail.getProductId(),
                    shipment.getLocationId(),
                    detail.getColorId(),
                    qtyReceived);
            BigDecimal after = productInventoryService
                    .getInventoryByProductAndLocationAndColor(detail.getProductId(), shipment.getLocationId(), detail.getColorId())
                    .getQuantity();

            productInventoryService.recordMovement(
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
                    shipment.getShipmentNumber(),
                    "Recepcion de envio en kiosko"
            );
        }

        // Verificar si todos los envíos de la distribución están DELIVERED
        List<ProductShipmentEntity> allShipments = shipmentRepository.findByDistributionId(shipment.getDistributionId());
        boolean allDelivered = allShipments.stream().allMatch(s -> "DELIVERED".equals(s.getStatus()));
        if (allDelivered) {
            ProductDistributionEntity dist = distributionRepository.findById(shipment.getDistributionId()).orElse(null);
            if (dist != null) {
                dist.setStatus("COMPLETED");
                distributionRepository.save(dist);
            }
        }

        return toShipmentResponse(shipmentRepository.findById(shipmentId).orElse(shipment));
    }

    /**
     * Obtiene envíos por estado
     */
    public List<ProductShipmentResponse> getShipmentsByStatus(String status) {
        return shipmentRepository.findAll().stream()
                .filter(s -> status.equals(s.getStatus()))
                .map(this::toShipmentResponse)
                .collect(Collectors.toList());
    }

    /**
     * Obtiene envíos en tránsito
     */
    public List<ProductShipmentResponse> getShipmentsInTransit() {
        return getShipmentsByStatus("SENT");
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
                .updatedAt(entity.getUpdatedAt())
                .updatedBy(entity.getUpdatedBy())
                .build();
    }

    /**
     * Convierte entidad de envío a DTO de respuesta
     */
    private ProductShipmentResponse toShipmentResponse(ProductShipmentEntity entity) {
        List<ProductShipmentDetailEntity> details = shipmentDetailRepository.findByShipmentId(entity.getId());
        LocationEntity location = locationRepository.findById(entity.getLocationId()).orElse(null);
        ProductDistributionEntity distribution = distributionRepository.findById(entity.getDistributionId()).orElse(null);
        
        return ProductShipmentResponse.builder()
                .id(entity.getId())
                .distributionId(entity.getDistributionId())
                .distributionNumber(distribution != null ? distribution.getDistributionNumber() : null)
                .shipmentNumber(entity.getShipmentNumber())
                .locationId(entity.getLocationId())
                .locationCode(location != null ? location.getCode() : null)
                .locationName(location != null ? location.getName() : null)
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
        ProductEntity product = productRepository.findById(entity.getProductId()).orElse(null);
        String colorName = null;
        if (entity.getColorId() != null) {
            ColorEntity color = colorRepository.findById(entity.getColorId()).orElse(null);
            colorName = color != null ? color.getName() : null;
        }
        
        return ProductShipmentDetailResponse.builder()
                .id(entity.getId())
                .shipmentId(entity.getShipmentId())
                .productId(entity.getProductId())
                .productCode(product != null ? product.getCode() : null)
                .productName(product != null ? product.getName() : null)
                .colorId(entity.getColorId())
                .colorName(colorName)
                .size(entity.getSizeLabel())
                .quantity(entity.getQuantity())
                .quantityReceived(entity.getQuantityReceived())
                .quantityDifference(entity.getQuantityDifference())
                .build();
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
            List<ProductShipmentRequest.ProductShipmentDetailRequest> products) throws ResourceNotFoundException {
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

