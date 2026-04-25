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
@Transactional
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
    private final InventoryService inventoryService;
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

        // Generar asientos contables automáticamente
        try {
            Long costCenterId = request.getCostCenterId(); // Opcional, puede ser null
            accountingService.generatePurchaseOrderEntries(saved.getId(), orderCode, total, costCenterId);
        } catch (Exception e) {
            // Log error pero no fallar la creación de la orden
            System.err.println("Error al generar asientos contables: " + e.getMessage());
            e.printStackTrace();
        }

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
        PurchaseOrderEntity entity = purchaseOrderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase Order", id));

        if (!"CREADA".equals(entity.getStatus())) {
            throw new BusinessException("Solo se pueden cancelar órdenes de compra con estado CREADA");
        }

        Long currentUserId = securityUtil.getCurrentUserId();
        entity.setStatus("CANCELADA");
        entity.setUpdatedBy(currentUserId);
        PurchaseOrderEntity saved = purchaseOrderRepository.save(entity);

        // Generar asientos contables de cancelación (reversión)
        try {
            Long costCenterId = null; // Se puede obtener de la solicitud si está disponible
            accountingService.generatePurchaseOrderCancellationEntries(saved.getId(), entity.getCode(), entity.getTotal(), costCenterId);
        } catch (Exception e) {
            // Log error pero no fallar la cancelación
            System.err.println("Error al generar asientos contables de cancelación: " + e.getMessage());
            e.printStackTrace();
        }

        return toPurchaseOrderResponse(saved);
    }


    // ========== MATERIAL RECEIPT ==========

    public MaterialReceiptResponse createMaterialReceipt(MaterialReceiptRequest request) throws BusinessException, ResourceNotFoundException {
        // Validar orden de compra
        PurchaseOrderEntity purchaseOrder = purchaseOrderRepository.findById(request.getPurchaseOrderId())
                .orElseThrow(() -> new ResourceNotFoundException("Purchase Order", request.getPurchaseOrderId()));

        if (!"CREADA".equals(purchaseOrder.getStatus()) && !"PARCIALMENTE_RECIBIDA".equals(purchaseOrder.getStatus())) {
            throw new BusinessException("Solo se pueden recibir órdenes de compra con estado CREADA o PARCIALMENTE_RECIBIDA");
        }

        // Obtener usuario actual
        Long currentUserId = securityUtil.getCurrentUserId();

        // Guardar solo las observaciones del usuario (sin mezclar con JSON de items)
        // Los items se procesan directamente en el código, no necesitan guardarse en observations
        String userObservations = request.getObservations();
        if (userObservations != null && userObservations.trim().isEmpty()) {
            userObservations = null;
        }

        // Crear recepción
        MaterialReceiptEntity receipt = MaterialReceiptEntity.builder()
                .purchaseOrderId(request.getPurchaseOrderId())
                .receiptDate(request.getReceiptDate() != null ? request.getReceiptDate() : LocalDate.now())
                .observations(userObservations) // Solo observaciones del usuario, sin JSON
                .createdBy(currentUserId)
                .updatedBy(currentUserId)
                .build();

        MaterialReceiptEntity saved = materialReceiptRepository.save(receipt);

        // Obtener items de la orden para copiar supplierId
        List<PurchaseOrderItemEntity> orderItemsForSupplier = purchaseOrderItemRepository.findByPurchaseOrderId(purchaseOrder.getId());
        java.util.Map<Long, Long> materialToSupplierMap = new java.util.HashMap<>();
        for (PurchaseOrderItemEntity orderItem : orderItemsForSupplier) {
            if (orderItem.getSupplierId() != null) {
                materialToSupplierMap.put(orderItem.getMaterialId(), orderItem.getSupplierId());
            }
        }

        // Guardar items de recepción en tabla separada (NO en observations)
        if (request.getItems() != null && !request.getItems().isEmpty()) {
            for (MaterialReceiptItemRequest receivedItem : request.getItems()) {
                // Usar supplierId del request, o si no está, usar el de la orden como fallback
                Long supplierId = receivedItem.getSupplierId();
                if (supplierId == null) {
                    supplierId = materialToSupplierMap.get(receivedItem.getMaterialId());
                }
                
                // Usar receiptDate del item, o si no está, usar la fecha general de la recepción
                LocalDate receiptDate = receivedItem.getReceiptDate();
                if (receiptDate == null) {
                    receiptDate = request.getReceiptDate() != null ? request.getReceiptDate() : LocalDate.now();
                }
                
                MaterialReceiptItemEntity receiptItem = MaterialReceiptItemEntity.builder()
                        .materialReceiptId(saved.getId())
                        .materialId(receivedItem.getMaterialId())
                        .quantityReceived(receivedItem.getQuantityReceived())
                        .unitPriceReceived(receivedItem.getUnitPriceReceived())
                        .supplierId(supplierId)
                        .receiptDate(receiptDate)
                        .createdBy(currentUserId)
                        .updatedBy(currentUserId)
                        .build();
                materialReceiptItemRepository.save(receiptItem);
            }
        }

        // Obtener items de la orden
        List<PurchaseOrderItemEntity> orderItems = purchaseOrderItemRepository.findByPurchaseOrderId(purchaseOrder.getId());
        
        // Crear mapa de items recibidos si se proporcionaron
        java.util.Map<Long, MaterialReceiptItemRequest> receivedItemsMap = new java.util.HashMap<>();
        if (request.getItems() != null && !request.getItems().isEmpty()) {
            for (MaterialReceiptItemRequest receivedItem : request.getItems()) {
                receivedItemsMap.put(receivedItem.getMaterialId(), receivedItem);
            }
        }

        BigDecimal totalReceived = BigDecimal.ZERO;
        boolean allItemsComplete = true;
        boolean hasPartialReceipt = false;

        // Procesar cada item de la orden
        for (PurchaseOrderItemEntity orderItem : orderItems) {
            MaterialEntity material = materialRepository.findById(orderItem.getMaterialId())
                    .orElseThrow(() -> new ResourceNotFoundException("Material", orderItem.getMaterialId()));

            // Determinar cantidad y precio recibidos
            BigDecimal quantityReceived;
            BigDecimal unitPriceReceived;
            
            MaterialReceiptItemRequest receivedItem = receivedItemsMap.get(orderItem.getMaterialId());
            if (receivedItem != null) {
                // Usar cantidades y precios recibidos
                quantityReceived = receivedItem.getQuantityReceived();
                unitPriceReceived = receivedItem.getUnitPriceReceived() != null 
                    ? receivedItem.getUnitPriceReceived() 
                    : orderItem.getUnitPrice();
            } else {
                // Si no se especifica en la recepción, no se recibió nada (0)
                // Esto permite recepciones parciales donde algunos items no se reciben
                quantityReceived = BigDecimal.ZERO;
                unitPriceReceived = orderItem.getUnitPrice();
            }

            // Si la cantidad recibida es 0 o negativa, saltar este item (no se recibió)
            // Esto permite recepciones parciales donde algunos items no se reciben
            if (quantityReceived.compareTo(BigDecimal.ZERO) <= 0) {
                // Marcar como recepción parcial si no se recibió este item
                if (quantityReceived.compareTo(BigDecimal.ZERO) == 0) {
                    hasPartialReceipt = true;
                    allItemsComplete = false;
                }
                // Continuar con el siguiente item sin procesar este
                continue;
            }

            // IMPORTANTE: quantityReceived viene en unidades de compra (como se ingresó en el frontend)
            // Necesitamos convertir a unidades de manufactura para actualizar el inventario
            // orderItem.getQuantity() también está en unidades de compra, así que la comparación es correcta
            int quantityComparison = quantityReceived.compareTo(orderItem.getQuantity());
            if (quantityComparison < 0) {
                hasPartialReceipt = true;
                allItemsComplete = false;
            } else if (quantityComparison > 0) {
                // Exceso permitido, pero se registra la variación
                // No marcamos como parcial, pero sí registramos la variación
            }

            // Convertir cantidad recibida de unidades de compra a unidades de manufactura
            BigDecimal quantityReceivedInManufacturing = quantityReceived;
            if (material.getPurchaseQuantity() != null && material.getPurchaseQuantity().compareTo(BigDecimal.ZERO) > 0) {
                // Si hay purchaseQuantity, convertir: cantidad_manufactura = cantidad_compra * purchaseQuantity
                quantityReceivedInManufacturing = quantityReceived.multiply(material.getPurchaseQuantity());
            }
            // Si no hay purchaseQuantity, asumimos que las unidades son las mismas (1:1)

            // Obtener stock actual antes de la recepción para calcular costo promedio
            // El stock está en unidades de manufactura
            BigDecimal currentStock = material.getQuantity() != null ? material.getQuantity() : BigDecimal.ZERO;
            // El unitCost es el costo por unidad de manufactura
            BigDecimal currentUnitCost = material.getUnitCost() != null ? material.getUnitCost() : BigDecimal.ZERO;
            
            // unitPriceReceived viene en precio por unidad de compra, necesitamos convertirlo a precio por unidad de manufactura
            BigDecimal unitPriceReceivedInManufacturing = unitPriceReceived;
            if (material.getPurchaseQuantity() != null && material.getPurchaseQuantity().compareTo(BigDecimal.ZERO) > 0) {
                // Precio por unidad de manufactura = precio por unidad de compra / purchaseQuantity
                unitPriceReceivedInManufacturing = unitPriceReceived.divide(material.getPurchaseQuantity(), 4, java.math.RoundingMode.HALF_UP);
            }
            
            // Calcular costo promedio ponderado (en unidades de manufactura)
            BigDecimal newUnitCost = calculateWeightedAverageCost(
                currentStock,
                currentUnitCost,
                quantityReceivedInManufacturing, // Cantidad recibida en unidades de manufactura
                unitPriceReceivedInManufacturing // Precio por unidad de manufactura
            );

            // Incrementar inventario de materiales (usar cantidad en unidades de manufactura)
            inventoryService.incrementMaterialInventory(
                    orderItem.getMaterialId(),
                    quantityReceivedInManufacturing, // Usar cantidad convertida a unidades de manufactura
                    unitPriceReceivedInManufacturing, // Usar precio convertido a unidades de manufactura
                    "MATERIAL_RECEIPT",
                    saved.getId(),
                    purchaseOrder.getCode(),
                    "Recepción de materiales - Orden: " + purchaseOrder.getCode() + 
                    (quantityComparison != 0 ? " (Variación: " + 
                        (quantityComparison > 0 ? "+" : "") + 
                        quantityReceived.subtract(orderItem.getQuantity()) + ")" : "")
            );

            // Actualizar costo unitario (por unidad de manufactura)
            material.setUnitCost(newUnitCost);
            
            // Actualizar precio de compra (en unidades de compra)
            // El purchasePrice es el precio por unidad de compra, que es lo que recibimos del frontend
            material.setPurchasePrice(unitPriceReceived);
            
            materialRepository.save(material);

            // Calcular total recibido (en unidades de compra para el total)
            totalReceived = totalReceived.add(quantityReceived.multiply(unitPriceReceived));
        }

        // Determinar nuevo estado de la orden
        String newStatus;
        if (allItemsComplete && !hasPartialReceipt) {
            newStatus = "RECIBIDA";
        } else {
            newStatus = "PARCIALMENTE_RECIBIDA";
        }

        currentUserId = securityUtil.getCurrentUserId();
        purchaseOrder.setStatus(newStatus);
        purchaseOrder.setUpdatedBy(currentUserId);
        purchaseOrderRepository.save(purchaseOrder);

        // Generar asientos contables de recepción
        try {
            Long costCenterId = null;
            accountingService.generateMaterialReceiptEntries(
                    saved.getId(),
                    purchaseOrder.getId(),
                    purchaseOrder.getCode(),
                    totalReceived,
                    purchaseOrder.getTotal(),
                    costCenterId
            );
        } catch (Exception e) {
            System.err.println("Error al generar asientos contables de recepción: " + e.getMessage());
        }

        return toMaterialReceiptResponse(saved);
    }

    /**
     * Calcula el costo promedio ponderado
     * Fórmula: (StockActual × CostoActual + CantidadRecibida × PrecioRecibido) / (StockActual + CantidadRecibida)
     */
    private BigDecimal calculateWeightedAverageCost(
            BigDecimal currentStock,
            BigDecimal currentCost,
            BigDecimal quantityReceived,
            BigDecimal priceReceived) {
        
        if (currentStock.compareTo(BigDecimal.ZERO) == 0) {
            // Si no hay stock, el nuevo costo es el precio recibido
            return priceReceived;
        }

        // Calcular valor total del stock actual
        BigDecimal currentValue = currentStock.multiply(currentCost);
        
        // Calcular valor de lo recibido
        BigDecimal receivedValue = quantityReceived.multiply(priceReceived);
        
        // Calcular nuevo stock total
        BigDecimal newStock = currentStock.add(quantityReceived);
        
        // Calcular nuevo costo promedio
        BigDecimal totalValue = currentValue.add(receivedValue);
        BigDecimal newAverageCost = totalValue.divide(newStock, 4, java.math.RoundingMode.HALF_UP);
        
        return newAverageCost;
    }

    public List<MaterialReceiptResponse> getMaterialReceipts() {
        return materialReceiptRepository.findAll().stream()
                .map(this::toMaterialReceiptResponse)
                .collect(Collectors.toList());
    }

    public MaterialReceiptResponse updateMaterialReceipt(Long receiptId, MaterialReceiptRequest request) throws BusinessException, ResourceNotFoundException {
        // Buscar recepción existente
        MaterialReceiptEntity existingReceipt = materialReceiptRepository.findById(receiptId)
                .orElseThrow(() -> new ResourceNotFoundException("Material Receipt", receiptId));

        // Validar orden de compra
        PurchaseOrderEntity purchaseOrder = purchaseOrderRepository.findById(existingReceipt.getPurchaseOrderId())
                .orElseThrow(() -> new ResourceNotFoundException("Purchase Order", existingReceipt.getPurchaseOrderId()));

        if (!"PARCIALMENTE_RECIBIDA".equals(purchaseOrder.getStatus()) && !"CREADA".equals(purchaseOrder.getStatus())) {
            throw new BusinessException("Solo se pueden actualizar recepciones de órdenes con estado CREADA o PARCIALMENTE_RECIBIDA");
        }

        // Obtener usuario actual
        Long currentUserId = securityUtil.getCurrentUserId();

        // Actualizar observaciones si se proporcionan
        if (request.getObservations() != null) {
            String userObservations = request.getObservations().trim();
            existingReceipt.setObservations(userObservations.isEmpty() ? null : userObservations);
        }

        // Actualizar fecha de recepción si se proporciona
        if (request.getReceiptDate() != null) {
            existingReceipt.setReceiptDate(request.getReceiptDate());
        }

        existingReceipt.setUpdatedBy(currentUserId);
        MaterialReceiptEntity saved = materialReceiptRepository.save(existingReceipt);

        // Obtener items ya recibidos
        List<MaterialReceiptItemEntity> existingReceiptItems = materialReceiptItemRepository.findByMaterialReceiptId(receiptId);
        java.util.Map<Long, MaterialReceiptItemEntity> existingItemsMap = new java.util.HashMap<>();
        for (MaterialReceiptItemEntity existingItem : existingReceiptItems) {
            existingItemsMap.put(existingItem.getMaterialId(), existingItem);
        }

        // Obtener items de la orden para copiar supplierId
        List<PurchaseOrderItemEntity> orderItemsForSupplier = purchaseOrderItemRepository.findByPurchaseOrderId(purchaseOrder.getId());
        java.util.Map<Long, Long> materialToSupplierMap = new java.util.HashMap<>();
        for (PurchaseOrderItemEntity orderItem : orderItemsForSupplier) {
            if (orderItem.getSupplierId() != null) {
                materialToSupplierMap.put(orderItem.getMaterialId(), orderItem.getSupplierId());
            }
        }

        // Procesar items nuevos o actualizados
        if (request.getItems() != null && !request.getItems().isEmpty()) {
            for (MaterialReceiptItemRequest receivedItem : request.getItems()) {
                MaterialReceiptItemEntity existingItem = existingItemsMap.get(receivedItem.getMaterialId());
                
                if (existingItem != null) {
                    // Actualizar item existente
                    BigDecimal oldQuantity = existingItem.getQuantityReceived();
                    BigDecimal newQuantity = receivedItem.getQuantityReceived();
                    BigDecimal quantityDifference = newQuantity.subtract(oldQuantity);
                    
                    // Actualizar valores
                    existingItem.setQuantityReceived(newQuantity);
                    if (receivedItem.getUnitPriceReceived() != null) {
                        existingItem.setUnitPriceReceived(receivedItem.getUnitPriceReceived());
                    }
                    if (receivedItem.getReceiptDate() != null) {
                        existingItem.setReceiptDate(receivedItem.getReceiptDate());
                    }
                    if (receivedItem.getSupplierId() != null) {
                        existingItem.setSupplierId(receivedItem.getSupplierId());
                    } else if (existingItem.getSupplierId() == null) {
                        existingItem.setSupplierId(materialToSupplierMap.get(receivedItem.getMaterialId()));
                    }
                    existingItem.setUpdatedBy(currentUserId);
                    materialReceiptItemRepository.save(existingItem);

                    // Actualizar inventario solo con la diferencia
                    if (quantityDifference.compareTo(BigDecimal.ZERO) != 0) {
                        MaterialEntity material = materialRepository.findById(receivedItem.getMaterialId())
                                .orElseThrow(() -> new ResourceNotFoundException("Material", receivedItem.getMaterialId()));
                        
                        BigDecimal unitPriceReceived = receivedItem.getUnitPriceReceived() != null 
                            ? receivedItem.getUnitPriceReceived() 
                            : existingItem.getUnitPriceReceived();
                        
                        // Convertir diferencia de unidades de compra a unidades de manufactura
                        BigDecimal quantityDifferenceInManufacturing = quantityDifference;
                        if (material.getPurchaseQuantity() != null && material.getPurchaseQuantity().compareTo(BigDecimal.ZERO) > 0) {
                            quantityDifferenceInManufacturing = quantityDifference.multiply(material.getPurchaseQuantity());
                        }
                        
                        // Convertir precio de unidades de compra a unidades de manufactura
                        BigDecimal unitPriceReceivedInManufacturing = unitPriceReceived;
                        if (material.getPurchaseQuantity() != null && material.getPurchaseQuantity().compareTo(BigDecimal.ZERO) > 0) {
                            unitPriceReceivedInManufacturing = unitPriceReceived.divide(material.getPurchaseQuantity(), 4, java.math.RoundingMode.HALF_UP);
                        }
                        
                        // Obtener stock actual
                        BigDecimal currentStock = material.getQuantity() != null ? material.getQuantity() : BigDecimal.ZERO;
                        BigDecimal currentUnitCost = material.getUnitCost() != null ? material.getUnitCost() : BigDecimal.ZERO;
                        
                        // Calcular nuevo costo promedio ponderado
                        BigDecimal newUnitCost = calculateWeightedAverageCost(
                            currentStock,
                            currentUnitCost,
                            quantityDifferenceInManufacturing, // Diferencia en unidades de manufactura
                            unitPriceReceivedInManufacturing
                        );
                        
                        // Actualizar inventario solo con la diferencia
                        if (quantityDifference.compareTo(BigDecimal.ZERO) > 0) {
                            inventoryService.incrementMaterialInventory(
                                    receivedItem.getMaterialId(),
                                    quantityDifferenceInManufacturing, // Usar cantidad convertida
                                    unitPriceReceivedInManufacturing, // Usar precio convertido
                                    "MATERIAL_RECEIPT_UPDATE",
                                    saved.getId(),
                                    purchaseOrder.getCode(),
                                    "Actualización de recepción - Orden: " + purchaseOrder.getCode()
                            );
                        } else {
                            // Si la diferencia es negativa, decrementar inventario
                            BigDecimal unitCost = material.getUnitCost() != null ? material.getUnitCost() : BigDecimal.ZERO;
                            
                            inventoryService.decrementMaterialInventory(
                                    receivedItem.getMaterialId(),
                                    quantityDifferenceInManufacturing.abs(), // Usar cantidad convertida
                                    unitCost,
                                    "MATERIAL_RECEIPT_UPDATE",
                                    saved.getId(),
                                    purchaseOrder.getCode(),
                                    "Actualización de recepción - Orden: " + purchaseOrder.getCode()
                            );
                        }
                        
                        // Actualizar costo unitario
                        material.setUnitCost(newUnitCost);
                        // Actualizar precio de compra (en unidades de compra)
                        material.setPurchasePrice(unitPriceReceived);
                        materialRepository.save(material);
                    }
                } else {
                    // Nuevo item - agregar
                    Long supplierId = receivedItem.getSupplierId();
                    if (supplierId == null) {
                        supplierId = materialToSupplierMap.get(receivedItem.getMaterialId());
                    }
                    
                    LocalDate receiptDate = receivedItem.getReceiptDate();
                    if (receiptDate == null) {
                        receiptDate = request.getReceiptDate() != null ? request.getReceiptDate() : LocalDate.now();
                    }
                    
                    MaterialReceiptItemEntity receiptItem = MaterialReceiptItemEntity.builder()
                            .materialReceiptId(saved.getId())
                            .materialId(receivedItem.getMaterialId())
                            .quantityReceived(receivedItem.getQuantityReceived())
                            .unitPriceReceived(receivedItem.getUnitPriceReceived())
                            .supplierId(supplierId)
                            .receiptDate(receiptDate)
                            .createdBy(currentUserId)
                            .updatedBy(currentUserId)
                            .build();
                    materialReceiptItemRepository.save(receiptItem);

                    // Actualizar inventario para el nuevo item
                    if (receivedItem.getQuantityReceived().compareTo(BigDecimal.ZERO) > 0) {
                        MaterialEntity material = materialRepository.findById(receivedItem.getMaterialId())
                                .orElseThrow(() -> new ResourceNotFoundException("Material", receivedItem.getMaterialId()));
                        
                        BigDecimal unitPriceReceived = receivedItem.getUnitPriceReceived() != null 
                            ? receivedItem.getUnitPriceReceived() 
                            : BigDecimal.ZERO;
                        
                        // Convertir cantidad de unidades de compra a unidades de manufactura
                        BigDecimal quantityReceivedInManufacturing = receivedItem.getQuantityReceived();
                        if (material.getPurchaseQuantity() != null && material.getPurchaseQuantity().compareTo(BigDecimal.ZERO) > 0) {
                            quantityReceivedInManufacturing = receivedItem.getQuantityReceived().multiply(material.getPurchaseQuantity());
                        }
                        
                        // Convertir precio de unidades de compra a unidades de manufactura
                        BigDecimal unitPriceReceivedInManufacturing = unitPriceReceived;
                        if (material.getPurchaseQuantity() != null && material.getPurchaseQuantity().compareTo(BigDecimal.ZERO) > 0) {
                            unitPriceReceivedInManufacturing = unitPriceReceived.divide(material.getPurchaseQuantity(), 4, java.math.RoundingMode.HALF_UP);
                        }
                        
                        // Obtener stock actual
                        BigDecimal currentStock = material.getQuantity() != null ? material.getQuantity() : BigDecimal.ZERO;
                        BigDecimal currentUnitCost = material.getUnitCost() != null ? material.getUnitCost() : BigDecimal.ZERO;
                        
                        // Calcular nuevo costo promedio ponderado
                        BigDecimal newUnitCost = calculateWeightedAverageCost(
                            currentStock,
                            currentUnitCost,
                            quantityReceivedInManufacturing, // Cantidad en unidades de manufactura
                            unitPriceReceivedInManufacturing // Precio en unidades de manufactura
                        );
                        
                        inventoryService.incrementMaterialInventory(
                                receivedItem.getMaterialId(),
                                quantityReceivedInManufacturing, // Usar cantidad convertida
                                unitPriceReceivedInManufacturing, // Usar precio convertido
                                "MATERIAL_RECEIPT_UPDATE",
                                saved.getId(),
                                purchaseOrder.getCode(),
                                "Actualización de recepción - Orden: " + purchaseOrder.getCode()
                        );
                        
                        // Actualizar costo unitario
                        material.setUnitCost(newUnitCost);
                        // Actualizar precio de compra (en unidades de compra)
                        material.setPurchasePrice(unitPriceReceived);
                        materialRepository.save(material);
                    }
                }
            }
        }

        // Recalcular estado de la orden basado en todos los items recibidos
        List<PurchaseOrderItemEntity> orderItems = purchaseOrderItemRepository.findByPurchaseOrderId(purchaseOrder.getId());
        List<MaterialReceiptItemEntity> allReceiptItems = materialReceiptItemRepository.findByMaterialReceiptId(receiptId);
        java.util.Map<Long, BigDecimal> totalReceivedByMaterial = new java.util.HashMap<>();
        
        for (MaterialReceiptItemEntity receiptItem : allReceiptItems) {
            BigDecimal currentTotal = totalReceivedByMaterial.getOrDefault(receiptItem.getMaterialId(), BigDecimal.ZERO);
            totalReceivedByMaterial.put(receiptItem.getMaterialId(), currentTotal.add(receiptItem.getQuantityReceived()));
        }

        boolean allItemsComplete = true;
        boolean hasPartialReceipt = false;

        for (PurchaseOrderItemEntity orderItem : orderItems) {
            BigDecimal totalReceived = totalReceivedByMaterial.getOrDefault(orderItem.getMaterialId(), BigDecimal.ZERO);
            int comparison = totalReceived.compareTo(orderItem.getQuantity());
            
            if (comparison < 0) {
                hasPartialReceipt = true;
                allItemsComplete = false;
            } else if (totalReceived.compareTo(BigDecimal.ZERO) == 0) {
                hasPartialReceipt = true;
                allItemsComplete = false;
            }
        }

        // Determinar nuevo estado de la orden
        String newStatus;
        if (allItemsComplete && !hasPartialReceipt) {
            newStatus = "RECIBIDA";
        } else {
            newStatus = "PARCIALMENTE_RECIBIDA";
        }

        purchaseOrder.setStatus(newStatus);
        purchaseOrder.setUpdatedBy(currentUserId);
        purchaseOrderRepository.save(purchaseOrder);

        return toMaterialReceiptResponse(saved);
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
                    .createdAt(entity.getCreatedAt())
                    .createdBy(entity.getCreatedBy())
                    .createdByName(createdByUser != null ? createdByUser.getUsername() : null)
                    .updatedAt(entity.getUpdatedAt())
                    .updatedBy(entity.getUpdatedBy())
                    .updatedByName(updatedByUser != null ? updatedByUser.getUsername() : null)
                    .build();
        }
        
        // Obtener información del proveedor
        SupplierEntity supplier = supplierRepository.findById(purchaseOrder.getSupplierId()).orElse(null);
        
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
        List<MaterialReceiptItemResponse> receiptItemsr = orderItems.stream()
                .map(orderItem -> {
                    MaterialEntity material = materialRepository.findById(orderItem.getMaterialId()).orElse(null);

                    // Obtener valores recibidos o usar los de la orden
                    MaterialReceiptItemRequest receivedItem = receivedItemsMap.get(orderItem.getMaterialId());
                    BigDecimal quantityReceived = receivedItem != null
                        ? receivedItem.getQuantityReceived()
                        : orderItem.getQuantity();
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
                .observations(cleanObservations) // Observaciones limpias, sin JSON
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

