package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.*;
import com.fossiles.fossilescorebackend.application.dto.response.*;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.SupplierEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.MaterialRequestItemEntity;
import com.fossiles.fossilescorebackend.application.service.AccountingService;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(rollbackFor = Exception.class)
public class PurchaseService {

    private final MaterialRequestRepository materialRequestRepository;
    private final MaterialRequestItemRepository materialRequestItemRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final MaterialReceiptRepository materialReceiptRepository;
    private final MaterialReceiptItemRepository materialReceiptItemRepository;
    private final MaterialRepository materialRepository;
    private final SupplierRepository supplierRepository;
    private final DocumentSeriesRepository documentSeriesRepository;
    private final AccountingService accountingService;
    private final MaterialReceiptService materialReceiptService;
    private final SecurityUtil securityUtil;
    private final UserRepository userRepository;

    // ========== MATERIAL REQUEST ==========

    public MaterialRequestResponse createMaterialRequest(MaterialRequestRequest request) throws ResourceNotFoundException, BusinessException {
        // Validar que todos los materiales existen
        for (MaterialRequestItemRequest itemRequest : request.getItems()) {
            if (!materialRepository.existsById(itemRequest.getMaterialId())) {
                throw new ResourceNotFoundException("Material", itemRequest.getMaterialId());
            }
        }

        // Validar que no exista una solicitud duplicada
        if (request.getOrigin() != null && request.getOriginReferenceId() != null) {
            List<String> activeStatuses = java.util.Arrays.asList("PENDIENTE", "APROBADA");
            List<MaterialRequestEntity> existingRequests = materialRequestRepository
                    .findByOriginAndOriginReferenceIdAndStatusIn(
                            request.getOrigin(), 
                            request.getOriginReferenceId(), 
                            activeStatuses);
            
            if (!existingRequests.isEmpty()) {
                // Verificar si los materiales son los mismos
                for (MaterialRequestEntity existingRequest : existingRequests) {
                    List<MaterialRequestItemEntity> existingItems = materialRequestItemRepository
                            .findByMaterialRequestId(existingRequest.getId());
                    
                    // Extraer los materialIds de la solicitud existente
                    java.util.Set<Long> existingMaterialIds = existingItems.stream()
                            .map(MaterialRequestItemEntity::getMaterialId)
                            .collect(java.util.stream.Collectors.toSet());
                    
                    // Extraer los materialIds de la nueva solicitud
                    java.util.Set<Long> newMaterialIds = request.getItems().stream()
                            .map(MaterialRequestItemRequest::getMaterialId)
                            .collect(java.util.stream.Collectors.toSet());
                    
                    // Si los materiales son iguales, es una solicitud duplicada
                    if (existingMaterialIds.equals(newMaterialIds)) {
                        throw new BusinessException(
                            String.format("Ya existe una solicitud %s para el origen %s (ID: %d) con los mismos materiales. " +
                                    "Solicitud existente ID: %d, Estado: %s",
                                    existingRequest.getStatus(),
                                    request.getOrigin(),
                                    request.getOriginReferenceId(),
                                    existingRequest.getId(),
                                    existingRequest.getStatus())
                        );
                    }
                }
            }
        }

        // Obtener usuario actual
        Long currentUserId = securityUtil.getCurrentUserId();

        // Crear solicitud
        MaterialRequestEntity entity = MaterialRequestEntity.builder()
                .origin(request.getOrigin())
                .originReferenceId(request.getOriginReferenceId())
                .status("PENDIENTE")
                .observations(request.getObservations())
                .requestDate(LocalDateTime.now())
                .createdBy(currentUserId)
                .updatedBy(currentUserId)
                .build();

        MaterialRequestEntity saved = materialRequestRepository.save(entity);

        // Guardar items (ya validamos que los materiales existen antes)
        List<MaterialRequestItemEntity> items = new java.util.ArrayList<>();
        for (MaterialRequestItemRequest itemRequest : request.getItems()) {
            MaterialEntity material = materialRepository.findById(itemRequest.getMaterialId())
                    .orElseThrow(() -> new ResourceNotFoundException("Material", itemRequest.getMaterialId()));
            
            // Validar proveedor si se proporciona
            if (itemRequest.getSupplierId() != null) {
                if (!supplierRepository.existsById(itemRequest.getSupplierId())) {
                    throw new ResourceNotFoundException("Supplier", itemRequest.getSupplierId());
                }
            }
            
            MaterialRequestItemEntity item = MaterialRequestItemEntity.builder()
                    .materialRequestId(saved.getId())
                    .materialId(itemRequest.getMaterialId())
                    .quantityRequested(itemRequest.getQuantityRequested())
                    .uomId(itemRequest.getUomId() != null ? itemRequest.getUomId() : material.getUomId())
                    .supplierId(itemRequest.getSupplierId())
                    .build();
            items.add(item);
        }

        materialRequestItemRepository.saveAll(items);

        return toMaterialRequestResponse(saved);
    }

    public MaterialRequestResponse approveMaterialRequest(Long id, Long approvedBy) throws BusinessException, ResourceNotFoundException {
        MaterialRequestEntity entity = materialRequestRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Material Request", id));

        if (!"PENDIENTE".equals(entity.getStatus())) {
            throw new BusinessException("Solo se pueden aprobar solicitudes con estado PENDIENTE");
        }

        Long currentUserId = securityUtil.getCurrentUserId();
        
        entity.setStatus("APROBADA");
        entity.setApprovedDate(LocalDateTime.now());
        entity.setApprovedBy(approvedBy);
        entity.setRejectedBy(null);
        entity.setRejectedDate(null);
        entity.setRejectionReason(null);
        entity.setUpdatedBy(currentUserId);

        MaterialRequestEntity saved = materialRequestRepository.save(entity);
        return toMaterialRequestResponse(saved);
    }

    public MaterialRequestResponse rejectMaterialRequest(Long id, Long rejectedBy, String rejectionReason) throws BusinessException, ResourceNotFoundException {
        MaterialRequestEntity entity = materialRequestRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Material Request", id));

        if (!"PENDIENTE".equals(entity.getStatus())) {
            throw new BusinessException("Solo se pueden rechazar solicitudes con estado PENDIENTE");
        }

        Long currentUserId = securityUtil.getCurrentUserId();
        
        entity.setStatus("RECHAZADA");
        entity.setRejectedDate(LocalDateTime.now());
        entity.setRejectedBy(rejectedBy);
        entity.setRejectionReason(rejectionReason);
        entity.setApprovedBy(null);
        entity.setApprovedDate(null);
        entity.setUpdatedBy(currentUserId);

        MaterialRequestEntity saved = materialRequestRepository.save(entity);
        return toMaterialRequestResponse(saved);
    }

    public MaterialRequestResponse addReviewComments(Long id, String reviewComments) throws ResourceNotFoundException {
        MaterialRequestEntity entity = materialRequestRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Material Request", id));

        entity.setReviewComments(reviewComments);

        MaterialRequestEntity saved = materialRequestRepository.save(entity);
        return toMaterialRequestResponse(saved);
    }

    public void deleteMaterialRequest(Long id) throws BusinessException, ResourceNotFoundException {
        if (!materialRequestRepository.existsById(id)) {
            throw new ResourceNotFoundException("Material Request", id);
        }
        
        MaterialRequestEntity entity = materialRequestRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Material Request", id));
        
        if ("COMPRADA".equals(entity.getStatus())) {
            throw new BusinessException("No se pueden eliminar solicitudes que ya han sido compradas");
        }

        // Eliminar items primero
        materialRequestItemRepository.deleteByMaterialRequestId(id);
        // Eliminar solicitud
        materialRequestRepository.deleteById(id);
    }

    public List<MaterialRequestResponse> getMaterialRequestsByStatus(String status) {
        List<MaterialRequestEntity> entities = status != null && !status.isEmpty() 
                ? materialRequestRepository.findByStatus(status)
                : materialRequestRepository.findAll();
        return entities.stream()
                .map(this::toMaterialRequestResponse)
                .collect(Collectors.toList());
    }

    public MaterialRequestResponse getMaterialRequestById(Long id) throws ResourceNotFoundException {
        MaterialRequestEntity entity = materialRequestRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Material Request", id));
        return toMaterialRequestResponse(entity);
    }

    public MaterialRequestResponse updateMaterialRequest(Long id, MaterialRequestRequest request) throws ResourceNotFoundException, BusinessException {
        MaterialRequestEntity entity = materialRequestRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Material Request", id));

        // Solo se pueden editar solicitudes PENDIENTE o RECHAZADA
        if (!"PENDIENTE".equals(entity.getStatus()) && !"RECHAZADA".equals(entity.getStatus())) {
            throw new BusinessException("Solo se pueden editar solicitudes con estado PENDIENTE o RECHAZADA");
        }

        // Validar que todos los materiales existen
        for (MaterialRequestItemRequest itemRequest : request.getItems()) {
            if (!materialRepository.existsById(itemRequest.getMaterialId())) {
                throw new ResourceNotFoundException("Material", itemRequest.getMaterialId());
            }
        }

        // Obtener usuario actual
        Long currentUserId = securityUtil.getCurrentUserId();

        // Actualizar campos básicos
        entity.setOrigin(request.getOrigin());
        entity.setOriginReferenceId(request.getOriginReferenceId());
        entity.setObservations(request.getObservations());
        entity.setUpdatedBy(currentUserId);
        
        // Si estaba rechazada y se edita, volver a PENDIENTE
        if ("RECHAZADA".equals(entity.getStatus())) {
            entity.setStatus("PENDIENTE");
            entity.setRejectedBy(null);
            entity.setRejectedDate(null);
            entity.setRejectionReason(null);
        }

        MaterialRequestEntity saved = materialRequestRepository.save(entity);

        // Eliminar items antiguos y crear nuevos
        materialRequestItemRepository.deleteByMaterialRequestId(id);

        // Crear nuevos items (ya validamos que los materiales existen antes)
        List<MaterialRequestItemEntity> items = new java.util.ArrayList<>();
        for (MaterialRequestItemRequest itemRequest : request.getItems()) {
            MaterialEntity material = materialRepository.findById(itemRequest.getMaterialId())
                    .orElseThrow(() -> new ResourceNotFoundException("Material", itemRequest.getMaterialId()));
            
            // Validar proveedor si se proporciona
            if (itemRequest.getSupplierId() != null) {
                if (!supplierRepository.existsById(itemRequest.getSupplierId())) {
                    throw new ResourceNotFoundException("Supplier", itemRequest.getSupplierId());
                }
            }
            
            MaterialRequestItemEntity item = MaterialRequestItemEntity.builder()
                    .materialRequestId(saved.getId())
                    .materialId(itemRequest.getMaterialId())
                    .quantityRequested(itemRequest.getQuantityRequested())
                    .uomId(itemRequest.getUomId() != null ? itemRequest.getUomId() : material.getUomId())
                    .supplierId(itemRequest.getSupplierId())
                    .build();
            items.add(item);
        }

        materialRequestItemRepository.saveAll(items);

        return toMaterialRequestResponse(saved);
    }

    // ========== PURCHASE ORDER ==========

    public PurchaseOrderResponse createPurchaseOrder(PurchaseOrderRequest request) throws BusinessException, ResourceNotFoundException {
        // Validar proveedor
        SupplierEntity supplier = supplierRepository.findById(request.getSupplierId())
                .orElseThrow(() -> new ResourceNotFoundException("Supplier", request.getSupplierId()));

        // Generar código automáticamente si no se proporciona
        String orderCode = request.getCode();
        if (orderCode == null || orderCode.trim().isEmpty()) {
            orderCode = generatePurchaseOrderCode();
        }

        if (purchaseOrderRepository.existsByCode(orderCode)) {
            throw new BusinessException("Purchase order code already exists: " + orderCode);
        }

        // Validar que las solicitudes estén aprobadas
        if (request.getMaterialRequestIds() != null && !request.getMaterialRequestIds().isEmpty()) {
            for (Long requestId : request.getMaterialRequestIds()) {
                MaterialRequestEntity materialRequest = materialRequestRepository.findById(requestId)
                        .orElseThrow(() -> new ResourceNotFoundException("Material Request", requestId));
                if (!"APROBADA".equals(materialRequest.getStatus())) {
                    throw new BusinessException("Solo se pueden crear órdenes de compra con solicitudes APROBADAS");
                }
            }
        }

        // Obtener usuario actual
        Long currentUserId = securityUtil.getCurrentUserId();

        // Calcular total
        BigDecimal total = request.getItems().stream()
                .map(item -> item.getQuantity().multiply(item.getUnitPrice()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Crear orden de compra
        PurchaseOrderEntity order = PurchaseOrderEntity.builder()
                .code(orderCode)
                .supplierId(request.getSupplierId())
                .orderDate(request.getOrderDate() != null ? request.getOrderDate() : LocalDate.now())
                .status(request.getStatus() != null ? request.getStatus() : "CREADA")
                .total(total)
                .referenceRequests(request.getMaterialRequestIds() != null 
                        ? request.getMaterialRequestIds().stream()
                                .map(String::valueOf)
                                .collect(Collectors.joining(","))
                        : null)
                .observations(request.getObservations())
                // Recepciones, ajustes y anulación toman el centro de costo de la compra.
                .costCenterId(request.getCostCenterId())
                .createdBy(currentUserId)
                .updatedBy(currentUserId)
                .build();

        PurchaseOrderEntity saved = purchaseOrderRepository.save(order);

        // Validar materiales antes de procesar items
        for (PurchaseOrderItemRequest itemRequest : request.getItems()) {
            if (!materialRepository.existsById(itemRequest.getMaterialId())) {
                throw new ResourceNotFoundException("Material", itemRequest.getMaterialId());
            }
        }

        // Crear mapa de MaterialRequestItems por materialId para obtener supplierId
        java.util.Map<Long, Long> materialToSupplierMap = new java.util.HashMap<>();
        if (request.getMaterialRequestIds() != null && !request.getMaterialRequestIds().isEmpty()) {
            for (Long requestId : request.getMaterialRequestIds()) {
                List<MaterialRequestItemEntity> requestItems = materialRequestItemRepository.findByMaterialRequestId(requestId);
                for (MaterialRequestItemEntity requestItem : requestItems) {
                    if (requestItem.getSupplierId() != null) {
                        materialToSupplierMap.put(requestItem.getMaterialId(), requestItem.getSupplierId());
                    }
                }
            }
        }

        // Guardar items
        List<PurchaseOrderItemEntity> items = request.getItems().stream()
                .map(itemRequest -> {
                    // Obtener supplierId: primero del itemRequest, luego del mapa de solicitudes, luego del proveedor de la orden
                    Long supplierId = itemRequest.getSupplierId();
                    if (supplierId == null) {
                        supplierId = materialToSupplierMap.get(itemRequest.getMaterialId());
                    }
                    if (supplierId == null) {
                        supplierId = request.getSupplierId(); // Fallback al proveedor de la orden
                    }
                    
                    // Calcular subtotal
                    BigDecimal subtotal = itemRequest.getQuantity().multiply(itemRequest.getUnitPrice())
                            .setScale(2, java.math.RoundingMode.HALF_UP);
                    
                    return PurchaseOrderItemEntity.builder()
                            .purchaseOrderId(saved.getId())
                            .materialId(itemRequest.getMaterialId())
                            .quantity(itemRequest.getQuantity())
                            .unitPrice(itemRequest.getUnitPrice())
                            .subtotal(subtotal)
                            .supplierId(supplierId)
                            .build();
                })
                .collect(Collectors.toList());

        purchaseOrderItemRepository.saveAll(items);

        // Actualizar estado de solicitudes a COMPRADA
        if (request.getMaterialRequestIds() != null && !request.getMaterialRequestIds().isEmpty()) {
            for (Long requestId : request.getMaterialRequestIds()) {
                MaterialRequestEntity materialRequest = materialRequestRepository.findById(requestId)
                        .orElseThrow(() -> new ResourceNotFoundException("Material Request", requestId));
                materialRequest.setStatus("COMPRADA");
                materialRequestRepository.save(materialRequest);
            }
        }

        // La compra y sus asientos se guardan en la misma transacción.
        accountingService.generatePurchaseOrderEntries(saved.getId(), orderCode, total, request.getCostCenterId());

        return toPurchaseOrderResponse(saved);
    }

    public PurchaseOrderResponse getPurchaseOrderById(Long id) throws ResourceNotFoundException {
        PurchaseOrderEntity entity = purchaseOrderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase Order", id));
        return toPurchaseOrderResponse(entity);
    }

    public List<PurchaseOrderResponse> getPurchaseOrdersByStatus(String status) {
        List<PurchaseOrderEntity> entities = status != null && !status.isEmpty()
                ? purchaseOrderRepository.findByStatus(status)
                : purchaseOrderRepository.findAll();
        return entities.stream()
                .map(this::toPurchaseOrderResponse)
                .collect(Collectors.toList());
    }

    public PurchaseOrderResponse cancelPurchaseOrder(Long id) throws BusinessException, ResourceNotFoundException {
        PurchaseOrderEntity entity = purchaseOrderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase Order", id));

        if (!"CREADA".equals(entity.getStatus())) {
            throw new BusinessException("Solo se pueden cancelar órdenes de compra con estado CREADA");
        }

        Long currentUserId = securityUtil.getCurrentUserId();
        entity.setStatus("CANCELADA");
        entity.setUpdatedBy(currentUserId);
        PurchaseOrderEntity saved = purchaseOrderRepository.save(entity);

        accountingService.generatePurchaseOrderCancellationEntries(saved.getId(), entity.getCode(), entity.getTotal(), entity.getCostCenterId());

        return toPurchaseOrderResponse(saved);
    }


    // ========== MATERIAL RECEIPT ==========

    public MaterialReceiptResponse createMaterialReceipt(MaterialReceiptRequest request) throws BusinessException, ResourceNotFoundException {
        return toMaterialReceiptResponse(materialReceiptService.create(request));
    }

    public MaterialReceiptResponse updateMaterialReceipt(Long id, MaterialReceiptRequest request) throws BusinessException, ResourceNotFoundException {
        return toMaterialReceiptResponse(materialReceiptService.update(id, request));
    }

    public MaterialReceiptResponse cancelMaterialReceipt(Long id, String reason) throws BusinessException, ResourceNotFoundException {
        return toMaterialReceiptResponse(materialReceiptService.cancel(id, reason));
    }

    public List<MaterialReceiptResponse> getMaterialReceipts() {
        return materialReceiptRepository.findAll().stream().map(this::toMaterialReceiptResponse).toList();
    }

    // ========== HELPER METHODS ==========

    private String generatePurchaseOrderCode() {
        String documentType = "PURCHASE_ORDER";
        String series = "OC";

        DocumentSeriesEntity seriesEntity = documentSeriesRepository
                .findByDocumentTypeAndSeriesForUpdate(documentType, series)
                .orElseGet(() -> {
                    DocumentSeriesEntity newSeries = DocumentSeriesEntity.builder()
                            .documentType(documentType)
                            .series(series)
                            .currentCorrelative(0L)
                            .status("active")
                            .description("Serie automática para órdenes de compra")
                            .build();
                    return documentSeriesRepository.save(newSeries);
                });

        documentSeriesRepository.incrementCorrelative(seriesEntity.getId());
        seriesEntity.setCurrentCorrelative(seriesEntity.getCurrentCorrelative() + 1);
        documentSeriesRepository.save(seriesEntity);

        return String.format("%s-%05d", series, seriesEntity.getCurrentCorrelative());
    }

    private MaterialRequestResponse toMaterialRequestResponse(MaterialRequestEntity entity) {
        List<MaterialRequestItemEntity> items = materialRequestItemRepository.findByMaterialRequestId(entity.getId());
        
        // Obtener nombres de usuarios
        UserEntity createdByUser = entity.getCreatedBy() != null 
                ? userRepository.findById(entity.getCreatedBy()).orElse(null) 
                : null;
        UserEntity updatedByUser = entity.getUpdatedBy() != null 
                ? userRepository.findById(entity.getUpdatedBy()).orElse(null) 
                : null;
        
        return MaterialRequestResponse.builder()
                .id(entity.getId())
                .origin(entity.getOrigin())
                .originReferenceId(entity.getOriginReferenceId())
                .status(entity.getStatus())
                .requestDate(entity.getRequestDate())
                .approvedDate(entity.getApprovedDate())
                .approvedBy(entity.getApprovedBy())
                .rejectedDate(entity.getRejectedDate())
                .rejectedBy(entity.getRejectedBy())
                .rejectionReason(entity.getRejectionReason())
                .reviewComments(entity.getReviewComments())
                .observations(entity.getObservations())
                .items(items.stream().map(this::toMaterialRequestItemResponse).collect(Collectors.toList()))
                .createdAt(entity.getCreatedAt())
                .createdBy(entity.getCreatedBy())
                .createdByName(createdByUser != null ? createdByUser.getUsername() : null)
                .updatedAt(entity.getUpdatedAt())
                .updatedBy(entity.getUpdatedBy())
                .updatedByName(updatedByUser != null ? updatedByUser.getUsername() : null)
                .build();
    }

    private MaterialRequestItemResponse toMaterialRequestItemResponse(MaterialRequestItemEntity entity) {
        MaterialEntity material = materialRepository.findById(entity.getMaterialId()).orElse(null);
        
        // Usar supplierId del item, o si no tiene, usar el del material como fallback
        Long supplierIdToUse = entity.getSupplierId();
        if (supplierIdToUse == null && material != null && material.getSupplierId() != null) {
            supplierIdToUse = material.getSupplierId();
        }
        
        SupplierEntity supplier = supplierIdToUse != null 
                ? supplierRepository.findById(supplierIdToUse).orElse(null) 
                : null;
        
        return MaterialRequestItemResponse.builder()
                .id(entity.getId())
                .materialRequestId(entity.getMaterialRequestId())
                .materialId(entity.getMaterialId())
                .materialSku(material != null ? material.getSku() : null)
                .materialName(material != null ? material.getName() : null)
                .quantityRequested(entity.getQuantityRequested())
                .uomId(entity.getUomId())
                .supplierId(supplierIdToUse)
                .supplierName(supplier != null ? supplier.getName() : null)
                .build();
    }

    private PurchaseOrderResponse toPurchaseOrderResponse(PurchaseOrderEntity entity) {
        SupplierEntity supplier = supplierRepository.findById(entity.getSupplierId()).orElse(null);
        List<PurchaseOrderItemEntity> items = purchaseOrderItemRepository.findByPurchaseOrderId(entity.getId());

        List<Long> materialRequestIds = null;
        if (entity.getReferenceRequests() != null && !entity.getReferenceRequests().isEmpty()) {
            materialRequestIds = java.util.Arrays.stream(entity.getReferenceRequests().split(","))
                    .map(Long::parseLong)
                    .collect(Collectors.toList());
        }

        // Obtener nombres de usuarios
        UserEntity createdByUser = entity.getCreatedBy() != null 
                ? userRepository.findById(entity.getCreatedBy()).orElse(null) 
                : null;
        UserEntity updatedByUser = entity.getUpdatedBy() != null 
                ? userRepository.findById(entity.getUpdatedBy()).orElse(null) 
                : null;

        return PurchaseOrderResponse.builder()
                .id(entity.getId())
                .code(entity.getCode())
                .supplierId(entity.getSupplierId())
                .supplierName(supplier != null ? supplier.getName() : null)
                .orderDate(entity.getOrderDate())
                .status(entity.getStatus())
                .total(entity.getTotal())
                .referenceRequests(entity.getReferenceRequests())
                .materialRequestIds(materialRequestIds)
                .observations(entity.getObservations())
                .costCenterId(entity.getCostCenterId())
                .items(items.stream().map(this::toPurchaseOrderItemResponse).collect(Collectors.toList()))
                .createdAt(entity.getCreatedAt())
                .createdBy(entity.getCreatedBy())
                .createdByName(createdByUser != null ? createdByUser.getUsername() : null)
                .updatedAt(entity.getUpdatedAt())
                .updatedBy(entity.getUpdatedBy())
                .updatedByName(updatedByUser != null ? updatedByUser.getUsername() : null)
                .build();
    }

    private PurchaseOrderItemResponse toPurchaseOrderItemResponse(PurchaseOrderItemEntity entity) {
        MaterialEntity material = materialRepository.findById(entity.getMaterialId()).orElse(null);
        SupplierEntity supplier = entity.getSupplierId() != null 
            ? supplierRepository.findById(entity.getSupplierId()).orElse(null) : null;
        
        return PurchaseOrderItemResponse.builder()
                .id(entity.getId())
                .purchaseOrderId(entity.getPurchaseOrderId())
                .materialId(entity.getMaterialId())
                .materialSku(material != null ? material.getSku() : null)
                .materialName(material != null ? material.getName() : null)
                .quantity(entity.getQuantity())
                .unitPrice(entity.getUnitPrice())
                .subtotal(entity.getSubtotal())
                .supplierId(entity.getSupplierId())
                .supplierName(supplier != null ? supplier.getName() : null)
                .build();
    }

    private List<PurchaseOrderItemEntity> groupReceiptOrderMaterials(List<PurchaseOrderItemEntity> rows) {
        var grouped = rows.stream().collect(Collectors.groupingBy(PurchaseOrderItemEntity::getMaterialId,
                java.util.LinkedHashMap::new, Collectors.toList()));
        return grouped.values().stream().map(lines -> {
            BigDecimal quantity = lines.stream().map(PurchaseOrderItemEntity::getQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal value = lines.stream().map(line -> line.getQuantity().multiply(
                    line.getUnitPrice() == null ? BigDecimal.ZERO : line.getUnitPrice())).reduce(BigDecimal.ZERO, BigDecimal::add);
            Long supplierId = lines.stream().map(PurchaseOrderItemEntity::getSupplierId).distinct().count() == 1 ? lines.getFirst().getSupplierId() : null;
            return PurchaseOrderItemEntity.builder().materialId(lines.getFirst().getMaterialId()).quantity(quantity)
                    .unitPrice(quantity.signum() > 0 ? value.divide(quantity, 2, java.math.RoundingMode.HALF_UP) : BigDecimal.ZERO)
                    .supplierId(supplierId).build();
        }).toList();
    }

    private MaterialReceiptResponse toMaterialReceiptResponse(MaterialReceiptEntity entity) {
        PurchaseOrderEntity purchaseOrder = purchaseOrderRepository.findById(entity.getPurchaseOrderId()).orElse(null);
        
        // Obtener nombres de usuarios
        UserEntity createdByUser = entity.getCreatedBy() != null 
                ? userRepository.findById(entity.getCreatedBy()).orElse(null) 
                : null;
        UserEntity updatedByUser = entity.getUpdatedBy() != null 
                ? userRepository.findById(entity.getUpdatedBy()).orElse(null) 
                : null;
        
        if (purchaseOrder == null) {
            return MaterialReceiptResponse.builder()
                    .id(entity.getId())
                    .purchaseOrderId(entity.getPurchaseOrderId())
                    .receiptDate(entity.getReceiptDate())
                    .observations(entity.getObservations())
                    .status(entity.getStatus()).cancellationReason(entity.getCancellationReason())
                    .createdAt(entity.getCreatedAt())
                    .createdBy(entity.getCreatedBy())
                    .createdByName(createdByUser != null ? createdByUser.getUsername() : null)
                    .updatedAt(entity.getUpdatedAt())
                    .updatedBy(entity.getUpdatedBy())
                    .updatedByName(updatedByUser != null ? updatedByUser.getUsername() : null)
                    .build();
        }
        
        // Obtener información del proveedor
        SupplierEntity supplier = purchaseOrder.getSupplierId() == null ? null : supplierRepository.findById(purchaseOrder.getSupplierId()).orElse(null);
        
        // Obtener items de la orden
        List<PurchaseOrderItemEntity> orderItems = purchaseOrderItemRepository.findByPurchaseOrderId(purchaseOrder.getId());
        
        // Obtener IDs de solicitudes relacionadas
        List<Long> materialRequestIds = null;
        if (purchaseOrder.getReferenceRequests() != null && !purchaseOrder.getReferenceRequests().isEmpty()) {
            materialRequestIds = java.util.Arrays.stream(purchaseOrder.getReferenceRequests().split(","))
                    .filter(s -> !s.trim().isEmpty())
                    .map(Long::parseLong)
                    .collect(Collectors.toList());
        }
        
        // Obtener items recibidos de la tabla separada
        java.util.Map<Long, MaterialReceiptItemRequest> receivedItemsMap = new java.util.HashMap<>();
        List<MaterialReceiptItemEntity> receiptItems = materialReceiptItemRepository.findByMaterialReceiptId(entity.getId());
        for (MaterialReceiptItemEntity receiptItem : receiptItems) {
            MaterialReceiptItemRequest itemRequest = MaterialReceiptItemRequest.builder()
                    .materialId(receiptItem.getMaterialId())
                    .quantityReceived(receiptItem.getQuantityReceived())
                    .unitPriceReceived(receiptItem.getUnitPriceReceived())
                    .build();
            receivedItemsMap.put(receiptItem.getMaterialId(), itemRequest);
        }
        
        // Si no hay items en la tabla, intentar leer de observations (para compatibilidad con datos antiguos)
        if (receivedItemsMap.isEmpty() && entity.getObservations() != null && entity.getObservations().contains("ITEMS_RECEIVED_JSON:")) {
            try {
                String jsonPart = entity.getObservations().substring(entity.getObservations().indexOf("ITEMS_RECEIVED_JSON:") + "ITEMS_RECEIVED_JSON:".length());
                com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.core.type.TypeReference<java.util.List<MaterialReceiptItemRequest>> typeRef = 
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.List<MaterialReceiptItemRequest>>() {};
                List<MaterialReceiptItemRequest> savedItems = objectMapper.readValue(jsonPart, typeRef);
                for (MaterialReceiptItemRequest item : savedItems) {
                    receivedItemsMap.put(item.getMaterialId(), item);
                }
            } catch (Exception e) {
                System.err.println("Error al deserializar items recibidos: " + e.getMessage());
            }
        }
        
        // Construir items de recepción con variaciones reales
        List<MaterialReceiptItemResponse> receiptItemsr = groupReceiptOrderMaterials(orderItems).stream()
                .map(orderItem -> {
                    MaterialEntity material = materialRepository.findById(orderItem.getMaterialId()).orElse(null);

                    // Obtener valores recibidos o usar los de la orden
                    MaterialReceiptItemRequest receivedItem = receivedItemsMap.get(orderItem.getMaterialId());
                    BigDecimal quantityReceived = receivedItem != null
                        ? receivedItem.getQuantityReceived()
                        : BigDecimal.ZERO;
                    BigDecimal unitPriceReceived = receivedItem != null && receivedItem.getUnitPriceReceived() != null
                        ? receivedItem.getUnitPriceReceived()
                        : orderItem.getUnitPrice();

                    // Calcular variaciones
                    BigDecimal quantityVariation = quantityReceived.subtract(orderItem.getQuantity());
                    BigDecimal priceVariation = unitPriceReceived.subtract(orderItem.getUnitPrice());

                    // Obtener receiptDate y supplierId del item recibido
                    MaterialReceiptItemEntity receiptItemEntity = receiptItems.stream()
                        .filter(item -> item.getMaterialId().equals(orderItem.getMaterialId()))
                        .findFirst()
                        .orElse(null);
                    
                    // Usar supplierId del item de recepción guardado, o del item de la orden como fallback
                    Long itemSupplierId = receiptItemEntity != null && receiptItemEntity.getSupplierId() != null
                        ? receiptItemEntity.getSupplierId()
                        : orderItem.getSupplierId();
                    
                    // Obtener información del proveedor del item
                    SupplierEntity itemSupplier = itemSupplierId != null 
                        ? supplierRepository.findById(itemSupplierId).orElse(null) : null;
                    
                    LocalDate itemReceiptDate = receiptItemEntity != null && receiptItemEntity.getReceiptDate() != null
                        ? receiptItemEntity.getReceiptDate()
                        : entity.getReceiptDate();
                    
                    return MaterialReceiptItemResponse.builder()
                            .materialId(orderItem.getMaterialId())
                            .materialSku(material != null ? material.getSku() : null)
                            .materialName(material != null ? material.getName() : null)
                            .quantityOrdered(orderItem.getQuantity())
                            .quantityReceived(quantityReceived)
                            .unitPriceOrdered(orderItem.getUnitPrice())
                            .unitPriceReceived(unitPriceReceived)
                            .subtotal(quantityReceived.multiply(unitPriceReceived))
                            .quantityVariation(quantityVariation)
                            .priceVariation(priceVariation)
                            .supplierId(itemSupplierId) // Usar el supplierId del item de recepción guardado
                            .supplierName(itemSupplier != null ? itemSupplier.getName() : null)
                            .receiptDate(itemReceiptDate)
                            .build();
                })
                .collect(Collectors.toList());

        // Limpiar observaciones para remover JSON de items (compatibilidad con datos antiguos)
        String cleanObservations = entity.getObservations();
        if (cleanObservations != null && cleanObservations.contains("ITEMS_RECEIVED_JSON:")) {
            int jsonIndex = cleanObservations.indexOf("ITEMS_RECEIVED_JSON:");
            cleanObservations = cleanObservations.substring(0, jsonIndex).trim();
            if (cleanObservations.isEmpty()) {
                cleanObservations = null;
            }
        }
        
        return MaterialReceiptResponse.builder()
                .id(entity.getId())
                .purchaseOrderId(entity.getPurchaseOrderId())
                .purchaseOrderCode(purchaseOrder.getCode())
                .receiptDate(entity.getReceiptDate())
                .observations(cleanObservations)
                .status(entity.getStatus()).cancellationReason(entity.getCancellationReason())
                .createdAt(entity.getCreatedAt())
                .createdBy(entity.getCreatedBy())
                .createdByName(createdByUser != null ? createdByUser.getUsername() : null)
                .updatedAt(entity.getUpdatedAt())
                .updatedBy(entity.getUpdatedBy())
                .updatedByName(updatedByUser != null ? updatedByUser.getUsername() : null)
                .supplierName(supplier != null ? supplier.getName() : null)
                .orderDate(purchaseOrder.getOrderDate())
                .orderStatus(purchaseOrder.getStatus())
                .orderTotal(purchaseOrder.getTotal())
                .orderObservations(purchaseOrder.getObservations())
                .materialRequestIds(materialRequestIds)
                .items(receiptItemsr)
                .build();
    }
}

