package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.*;
import com.fossiles.fossilescorebackend.application.dto.response.CriticalInventoryResponse;
import com.fossiles.fossilescorebackend.application.dto.response.InventoryKardexResponse;
import com.fossiles.fossilescorebackend.application.dto.response.InventoryLocationResponse;
import com.fossiles.fossilescorebackend.application.dto.response.MaterialInventoryResponse;
import com.fossiles.fossilescorebackend.application.dto.response.MaterialInventoryKardexPageResponse;
import com.fossiles.fossilescorebackend.application.dto.response.MaterialInventoryKardexResponse;
import com.fossiles.fossilescorebackend.application.dto.response.InventoryOutflowResponse;
import com.fossiles.fossilescorebackend.application.dto.response.InventoryTransferResponse;
import com.fossiles.fossilescorebackend.application.dto.response.InventoryAdjustmentResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.*;
import com.fossiles.fossilescorebackend.infrastructure.util.CinchoProductUtils;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductInventorySizesJson;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
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
public class InventoryService {

    private final InventoryLocationRepository inventoryLocationRepository;
    private final InventoryKardexRepository inventoryKardexRepository;
    private final MaterialInventoryRepository materialInventoryRepository;
    private final MaterialInventoryKardexRepository materialInventoryKardexRepository;
    private final MaterialRepository materialRepository;
    private final LocationRepository locationRepository;
    private final ProductRepository productRepository;
    private final InventoryTransferRepository inventoryTransferRepository;
    private final InventoryAdjustmentRepository inventoryAdjustmentRepository;
    private final ProductInventoryLocationRepository productInventoryLocationRepository;
    private final ProductInventoryService productInventoryService;
    private final UserRepository userRepository;
    private final SecurityUtil securityUtil;
    private final ColorRepository colorRepository;
    private final MaterialFifoBatchRepository materialFifoBatchRepository;
    private final ProductFifoBatchRepository productFifoBatchRepository;
    private final UomRepository uomRepository;
    private final InventoryOutflowRepository inventoryOutflowRepository;
    private final SupplierRepository supplierRepository;
    private final KioskInventoryGuard kioskInventoryGuard;
    private final KioscoInventoryService kioscoInventoryService;

    // ========== HELPER METHODS ==========

    /** Evita falsos negativos por escala al comparar suma de tallas con totales del request. */
    private static boolean sumsMatchStock(BigDecimal sum, BigDecimal stock) {
        if (sum == null || stock == null) {
            return false;
        }
        if (sum.compareTo(stock) == 0) {
            return true;
        }
        return sum.setScale(6, RoundingMode.HALF_UP).compareTo(stock.setScale(6, RoundingMode.HALF_UP)) == 0;
    }

    // ========== INVENTORY LOCATION ==========

    /**
     * Obtiene el inventario de un material en una ubicación específica
     */
    public InventoryLocationResponse getInventoryByMaterialAndLocation(Long materialId, Long locationId) 
            throws ResourceNotFoundException {
        InventoryLocation entity = inventoryLocationRepository
                .findByMaterialIdAndLocationId(materialId, locationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                    "Inventory Location", "Material: " + materialId + ", Location: " + locationId));
        return toInventoryLocationResponse(entity);
    }

    /**
     * Obtiene todo el inventario de un material (suma de todas las ubicaciones)
     */
    public List<InventoryLocationResponse> getInventoryByMaterial(Long materialId) {
        List<InventoryLocation> entities = inventoryLocationRepository.findByMaterialId(materialId);
        return entities.stream()
                .map(this::toInventoryLocationResponse)
                .collect(Collectors.toList());
    }

    /**
     * Obtiene todo el inventario de una ubicación
     * Incluye todos los materiales, incluso si no tienen stock (muestra 0)
     */
    public List<InventoryLocationResponse> getInventoryByLocation(Long locationId) {
        // Obtener todos los materiales
        List<MaterialEntity> allMaterials = materialRepository.findAll();
        
        // Obtener inventario existente para esta ubicación
        List<InventoryLocation> existingInventory = inventoryLocationRepository.findByLocationId(locationId);
        
        // Crear un mapa para búsqueda rápida
        java.util.Map<Long, InventoryLocation> inventoryMap = existingInventory.stream()
                .collect(Collectors.toMap(InventoryLocation::getMaterialId, il -> il));
        
        // Obtener la ubicación
        LocationEntity location = locationRepository.findById(locationId).orElse(null);
        
        // Crear respuesta incluyendo todos los materiales
        return allMaterials.stream()
                .map(material -> {
                    InventoryLocation inventory = inventoryMap.get(material.getId());
                    if (inventory != null) {
                        // Material tiene inventario en esta ubicación
                        return toInventoryLocationResponse(inventory);
                    } else {
                        // Material no tiene inventario, crear respuesta con cantidad 0
                        return createEmptyInventoryResponse(material.getId(), locationId, material, location);
                    }
                })
                .collect(Collectors.toList());
    }

    /**
     * Obtiene el inventario agregado de materiales (sin ubicación)
     * Los materiales solo se almacenan en la planta, no tienen location_id
     */
    public List<MaterialInventoryResponse> getAggregatedMaterialInventory() {
        // Obtener todos los materiales
        List<MaterialEntity> allMaterials = materialRepository.findAll();
        
        // Obtener todo el inventario de materiales (sin ubicación)
        List<MaterialInventory> materialInventory = materialInventoryRepository.findAll();
        
        // Crear mapa de material -> cantidad
        java.util.Map<Long, BigDecimal> materialQuantities = materialInventory.stream()
                .collect(Collectors.toMap(
                    MaterialInventory::getMaterialId,
                    MaterialInventory::getQuantity
                ));

        Set<Long> supplierIds = allMaterials.stream()
                .map(MaterialEntity::getSupplierId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> supplierNames = new HashMap<>();
        for (SupplierEntity sup : supplierRepository.findAllById(supplierIds)) {
            supplierNames.put(sup.getId(), sup.getName());
        }

        // Crear respuesta con inventario de materiales
        return allMaterials.stream()
                .map(material -> {
                    BigDecimal quantity = materialQuantities.getOrDefault(material.getId(), BigDecimal.ZERO);
                    Long sid = material.getSupplierId();
                    return MaterialInventoryResponse.builder()
                            .materialId(material.getId())
                            .materialSku(material.getSku())
                            .materialName(material.getName())
                            .totalQuantity(quantity)
                            .purchaseUomId(material.getPurchaseUomId())
                            .purchaseUomCode(resolveUomCode(material.getPurchaseUomId()))
                            .purchaseUomName(resolveUomName(material.getPurchaseUomId()))
                            .purchaseQuantity(material.getPurchaseQuantity())
                            .manufacturingUomId(material.getManufacturingUomId())
                            .manufacturingUomCode(resolveMaterialUomCode(material))
                            .manufacturingUomName(resolveMaterialUomName(material))
                            .conversionText(buildConversionText(material))
                            .materialMin(material.getMin())
                            .materialMax(material.getMax())
                            .supplierId(sid)
                            .supplierName(sid != null ? supplierNames.get(sid) : null)
                            .build();
                })
                .collect(Collectors.toList());
    }

    // ========== MATERIAL INVENTORY (SIN UBICACIÓN) ==========

    /**
     * Obtiene el inventario de un material específico
     */
    public MaterialInventoryResponse getMaterialInventory(Long materialId) throws ResourceNotFoundException {
        MaterialInventory entity = materialInventoryRepository.findByMaterialId(materialId)
                .orElseThrow(() -> new ResourceNotFoundException("Material Inventory", materialId));
        
        MaterialEntity material = materialRepository.findById(materialId).orElse(null);
        
        return enrichMaterialSupplier(material,
                MaterialInventoryResponse.builder()
                .materialId(entity.getMaterialId())
                .materialSku(material != null ? material.getSku() : null)
                .materialName(material != null ? material.getName() : null)
                .totalQuantity(entity.getQuantity())
                .purchaseUomId(material != null ? material.getPurchaseUomId() : null)
                .purchaseUomCode(material != null ? resolveUomCode(material.getPurchaseUomId()) : null)
                .purchaseUomName(material != null ? resolveUomName(material.getPurchaseUomId()) : null)
                .purchaseQuantity(material != null ? material.getPurchaseQuantity() : null)
                .manufacturingUomId(material != null ? material.getManufacturingUomId() : null)
                .manufacturingUomCode(material != null ? resolveMaterialUomCode(material) : null)
                .manufacturingUomName(material != null ? resolveMaterialUomName(material) : null)
                .conversionText(material != null ? buildConversionText(material) : null)
                .materialMin(material != null ? material.getMin() : null)
                .materialMax(material != null ? material.getMax() : null)
        ).build();
    }

    /**
     * Crea o actualiza el inventario de un material
     */
    public MaterialInventoryResponse createOrUpdateMaterialInventory(Long materialId, BigDecimal quantity) 
            throws ResourceNotFoundException {
        // Validar que el material existe
        if (!materialRepository.existsById(materialId)) {
            throw new ResourceNotFoundException("Material", materialId);
        }

        Optional<MaterialInventory> existing = materialInventoryRepository.findByMaterialId(materialId);

        MaterialInventory entity;
        if (existing.isPresent()) {
            // Actualizar
            entity = existing.get();
            entity.setQuantity(quantity);
        } else {
            // Crear nuevo
            entity = MaterialInventory.builder()
                    .materialId(materialId)
                    .quantity(quantity)
                    .build();
        }

        MaterialInventory saved = materialInventoryRepository.save(entity);
        
        // Actualizar quantity en MaterialEntity
        MaterialEntity material = materialRepository.findById(materialId).orElse(null);
        if (material != null) {
            material.setQuantity(quantity);
            materialRepository.save(material);
        }

        MaterialEntity materialEntity = materialRepository.findById(materialId).orElse(null);
        return enrichMaterialSupplier(materialEntity,
                MaterialInventoryResponse.builder()
                .materialId(saved.getMaterialId())
                .materialSku(materialEntity != null ? materialEntity.getSku() : null)
                .materialName(materialEntity != null ? materialEntity.getName() : null)
                .totalQuantity(saved.getQuantity())
                .purchaseUomId(materialEntity != null ? materialEntity.getPurchaseUomId() : null)
                .purchaseUomCode(materialEntity != null ? resolveUomCode(materialEntity.getPurchaseUomId()) : null)
                .purchaseUomName(materialEntity != null ? resolveUomName(materialEntity.getPurchaseUomId()) : null)
                .purchaseQuantity(materialEntity != null ? materialEntity.getPurchaseQuantity() : null)
                .manufacturingUomId(materialEntity != null ? materialEntity.getManufacturingUomId() : null)
                .manufacturingUomCode(materialEntity != null ? resolveMaterialUomCode(materialEntity) : null)
                .manufacturingUomName(materialEntity != null ? resolveMaterialUomName(materialEntity) : null)
                .conversionText(materialEntity != null ? buildConversionText(materialEntity) : null)
                .materialMin(materialEntity != null ? materialEntity.getMin() : null)
                .materialMax(materialEntity != null ? materialEntity.getMax() : null)
        ).build();
    }

    /**
     * Incrementa el inventario de un material (entrada)
     */
    public MaterialInventoryResponse incrementMaterialInventory(
            Long materialId, 
            BigDecimal quantity,
            BigDecimal unitCost,
            String referenceType,
            Long referenceId,
            String referenceNumber,
            String description) throws ResourceNotFoundException {
        
        // Validar que el material existe
        if (!materialRepository.existsById(materialId)) {
            throw new ResourceNotFoundException("Material", materialId);
        }

        Optional<MaterialInventory> existing = materialInventoryRepository.findByMaterialId(materialId);

        MaterialInventory entity;
        BigDecimal quantityBefore;
        BigDecimal quantityAfter;
        
        if (existing.isPresent()) {
            entity = existing.get();
            quantityBefore = entity.getQuantity();
            entity.setQuantity(entity.getQuantity().add(quantity));
            quantityAfter = entity.getQuantity();
        } else {
            quantityBefore = BigDecimal.ZERO;
            entity = MaterialInventory.builder()
                    .materialId(materialId)
                    .quantity(quantity)
                    .build();
            quantityAfter = quantity;
        }

        MaterialInventory saved = materialInventoryRepository.save(entity);
        
        // Actualizar quantity en MaterialEntity
        MaterialEntity material = materialRepository.findById(materialId).orElse(null);
        if (material != null) {
            material.setQuantity(saved.getQuantity());
            materialRepository.save(material);
        }

        // Crear lote FIFO para esta entrada (solo si hay un costo válido)
        if (unitCost != null && unitCost.compareTo(BigDecimal.ZERO) > 0) {
            Long currentUserId = securityUtil.getCurrentUserId();
            MaterialFifoBatch fifoBatch = MaterialFifoBatch.builder()
                    .materialId(materialId)
                    .entryDate(LocalDateTime.now())
                    .quantityOriginal(quantity)
                    .quantityAvailable(quantity)
                    .unitCost(unitCost)
                    .referenceType(referenceType)
                    .referenceId(referenceId)
                    .referenceNumber(referenceNumber)
                    .description(description)
                    .createdBy(currentUserId)
                    .build();
            materialFifoBatchRepository.save(fifoBatch);
        }

        // Registrar movimiento en kardex
        recordMaterialMovement(
                materialId,
                "ENTRY",
                quantity,
                quantityBefore,
                quantityAfter,
                unitCost,
                referenceType,
                referenceId,
                referenceNumber,
                description
        );

        MaterialEntity materialEntity = materialRepository.findById(materialId).orElse(null);
        return enrichMaterialSupplier(materialEntity,
                MaterialInventoryResponse.builder()
                .materialId(saved.getMaterialId())
                .materialSku(materialEntity != null ? materialEntity.getSku() : null)
                .materialName(materialEntity != null ? materialEntity.getName() : null)
                .totalQuantity(saved.getQuantity())
                .purchaseUomId(materialEntity != null ? materialEntity.getPurchaseUomId() : null)
                .purchaseUomCode(materialEntity != null ? resolveUomCode(materialEntity.getPurchaseUomId()) : null)
                .purchaseUomName(materialEntity != null ? resolveUomName(materialEntity.getPurchaseUomId()) : null)
                .purchaseQuantity(materialEntity != null ? materialEntity.getPurchaseQuantity() : null)
                .manufacturingUomId(materialEntity != null ? materialEntity.getManufacturingUomId() : null)
                .manufacturingUomCode(materialEntity != null ? resolveMaterialUomCode(materialEntity) : null)
                .manufacturingUomName(materialEntity != null ? resolveMaterialUomName(materialEntity) : null)
                .conversionText(materialEntity != null ? buildConversionText(materialEntity) : null)
                .materialMin(materialEntity != null ? materialEntity.getMin() : null)
                .materialMax(materialEntity != null ? materialEntity.getMax() : null)
        ).build();
    }

    /**
     * Decrementa el inventario de un material (salida)
     */
    public MaterialInventoryResponse decrementMaterialInventory(
            Long materialId,
            BigDecimal quantity,
            BigDecimal unitCost,
            String referenceType,
            Long referenceId,
            String referenceNumber,
            String description) throws ResourceNotFoundException, BusinessException {
        return decrementMaterialInventory(materialId, quantity, unitCost, referenceType, referenceId, referenceNumber, description, false);
    }

    /**
     * Decrementa inventario; si {@code allowNegativeStock} es true no valida saldo (solo entrega materiales a OP forzada).
     */
    public MaterialInventoryResponse decrementMaterialInventory(
            Long materialId,
            BigDecimal quantity,
            BigDecimal unitCost,
            String referenceType,
            Long referenceId,
            String referenceNumber,
            String description,
            boolean allowNegativeStock) throws ResourceNotFoundException, BusinessException {

        if (referenceType != null && referenceId != null
                && materialInventoryKardexRepository.existsMovement(materialId, referenceType, referenceId, "EXIT")) {
            return getMaterialInventory(materialId);
        }

        MaterialInventory entity = materialInventoryRepository.findByMaterialId(materialId)
                .orElseThrow(() -> new ResourceNotFoundException("Material Inventory", materialId));

        BigDecimal quantityBefore = entity.getQuantity();
        BigDecimal newQuantity = entity.getQuantity().subtract(quantity);

        if (!allowNegativeStock && newQuantity.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("No hay suficiente inventario. Disponible: " + entity.getQuantity() + ", Solicitado: " + quantity);
        }

        entity.setQuantity(newQuantity);
        BigDecimal quantityAfter = newQuantity;
        MaterialInventory saved = materialInventoryRepository.save(entity);
        
        // Actualizar quantity en MaterialEntity
        MaterialEntity material = materialRepository.findById(materialId).orElse(null);
        if (material != null) {
            material.setQuantity(saved.getQuantity());
            materialRepository.save(material);
        }

        // Consumir lotes FIFO (más antiguos primero) y calcular costo promedio
        BigDecimal remainingQuantity = quantity;
        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal totalQuantityConsumed = BigDecimal.ZERO;
        
        List<MaterialFifoBatch> availableBatches = materialFifoBatchRepository
                .findAvailableBatchesByMaterialIdOrderByEntryDate(materialId);
        
        java.util.List<String> fifoBatchInfo = new java.util.ArrayList<>();
        LocalDateTime oldestBatchDate = null;
        LocalDateTime newestBatchDate = null;
        
        for (MaterialFifoBatch batch : availableBatches) {
            if (remainingQuantity.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            
            BigDecimal quantityToConsume = remainingQuantity.min(batch.getQuantityAvailable());
            BigDecimal batchCost = quantityToConsume.multiply(batch.getUnitCost());
            
            totalCost = totalCost.add(batchCost);
            totalQuantityConsumed = totalQuantityConsumed.add(quantityToConsume);
            
            // Guardar información del lote para la descripción
            if (oldestBatchDate == null || batch.getEntryDate().isBefore(oldestBatchDate)) {
                oldestBatchDate = batch.getEntryDate();
            }
            if (newestBatchDate == null || batch.getEntryDate().isAfter(newestBatchDate)) {
                newestBatchDate = batch.getEntryDate();
            }
            fifoBatchInfo.add(String.format("Lote del %s: %.3f u. @ Q%.2f", 
                batch.getEntryDate().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")),
                quantityToConsume.doubleValue(),
                batch.getUnitCost().doubleValue()));
            
            // Reducir cantidad disponible del lote
            batch.setQuantityAvailable(batch.getQuantityAvailable().subtract(quantityToConsume));
            materialFifoBatchRepository.save(batch);
            
            remainingQuantity = remainingQuantity.subtract(quantityToConsume);
        }
        
        // Si no hay suficiente inventario en lotes FIFO, permitir la operación pero sin consumir lotes
        // Esto puede pasar en transferencias o ajustes donde el inventario existe pero no hay lotes FIFO registrados
        // En este caso, el costo se calculará como null y se registrará en el kardex sin costo FIFO
        if (remainingQuantity.compareTo(BigDecimal.ZERO) > 0) {
            // No lanzar error, solo registrar que no se pudo consumir completamente de lotes FIFO
            // El inventario se decrementa de todas formas
        }
        
        // Calcular costo unitario promedio basado en FIFO
        BigDecimal averageUnitCost = totalQuantityConsumed.compareTo(BigDecimal.ZERO) > 0
                ? totalCost.divide(totalQuantityConsumed, 2, java.math.RoundingMode.HALF_UP)
                : (unitCost != null ? unitCost : BigDecimal.ZERO);

        // Construir descripción con información FIFO
        String fifoDescription = description != null ? description : "";
        if (oldestBatchDate != null) {
            String fifoInfo = String.format(" [FIFO: Lotes desde %s hasta %s]",
                oldestBatchDate.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")),
                newestBatchDate.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")));
            fifoDescription = fifoDescription + (fifoDescription.isEmpty() ? "" : " ") + fifoInfo;
        }

        // Registrar movimiento en kardex (cantidad negativa para salida)
        recordMaterialMovement(
                materialId,
                "EXIT",
                quantity.negate(), // Negativo para salida
                quantityBefore,
                quantityAfter,
                averageUnitCost, // Usar costo FIFO calculado
                referenceType,
                referenceId,
                referenceNumber,
                fifoDescription
        );

        MaterialEntity materialEntity = materialRepository.findById(materialId).orElse(null);
        return enrichMaterialSupplier(materialEntity,
                MaterialInventoryResponse.builder()
                .materialId(saved.getMaterialId())
                .materialSku(materialEntity != null ? materialEntity.getSku() : null)
                .materialName(materialEntity != null ? materialEntity.getName() : null)
                .totalQuantity(saved.getQuantity())
                .purchaseUomId(materialEntity != null ? materialEntity.getPurchaseUomId() : null)
                .purchaseUomCode(materialEntity != null ? resolveUomCode(materialEntity.getPurchaseUomId()) : null)
                .purchaseUomName(materialEntity != null ? resolveUomName(materialEntity.getPurchaseUomId()) : null)
                .purchaseQuantity(materialEntity != null ? materialEntity.getPurchaseQuantity() : null)
                .manufacturingUomId(materialEntity != null ? materialEntity.getManufacturingUomId() : null)
                .manufacturingUomCode(materialEntity != null ? resolveMaterialUomCode(materialEntity) : null)
                .manufacturingUomName(materialEntity != null ? resolveMaterialUomName(materialEntity) : null)
                .conversionText(materialEntity != null ? buildConversionText(materialEntity) : null)
                .materialMin(materialEntity != null ? materialEntity.getMin() : null)
                .materialMax(materialEntity != null ? materialEntity.getMax() : null)
        ).build();
    }

    /**
     * Agrega datos de proveedor al DTO agregado de material.
     */
    private MaterialInventoryResponse.MaterialInventoryResponseBuilder enrichMaterialSupplier(
            MaterialEntity material,
            MaterialInventoryResponse.MaterialInventoryResponseBuilder builder
    ) {
        if (material == null || material.getSupplierId() == null) {
            return builder.supplierId(null).supplierName(null);
        }
        Long sid = material.getSupplierId();
        return builder.supplierId(sid)
                .supplierName(supplierRepository.findById(sid).map(SupplierEntity::getName).orElse(null));
    }

    // ========== MATERIAL KARDEX (SIN UBICACIÓN) ==========

    /**
     * Registra un movimiento en el kardex de materiales (sin ubicación)
     */
    public MaterialInventoryKardexResponse recordMaterialMovement(
            Long materialId,
            String movementType,
            BigDecimal quantity,
            BigDecimal quantityBefore,
            BigDecimal quantityAfter,
            BigDecimal unitCost,
            String referenceType,
            Long referenceId,
            String referenceNumber,
            String description) throws ResourceNotFoundException {

        // Validar que el material existe
        if (!materialRepository.existsById(materialId)) {
            throw new ResourceNotFoundException("Material", materialId);
        }

        BigDecimal totalCost = null;
        if (unitCost != null && quantity != null) {
            totalCost = unitCost.multiply(quantity.abs());
        }

        MaterialInventoryKardex entity = MaterialInventoryKardex.builder()
                .materialId(materialId)
                .movementType(movementType)
                .quantity(quantity)
                .quantityBefore(quantityBefore)
                .quantityAfter(quantityAfter)
                .unitCost(unitCost)
                .totalCost(totalCost)
                .referenceType(referenceType)
                .referenceId(referenceId)
                .referenceNumber(referenceNumber)
                .description(description)
                .movementDate(LocalDateTime.now())
                .build();

        MaterialInventoryKardex saved = materialInventoryKardexRepository.save(entity);
        return toMaterialInventoryKardexResponse(saved);
    }

    /**
     * Obtiene el kardex de un material
     */
    public List<MaterialInventoryKardexResponse> getMaterialKardex(Long materialId) {
        return materialInventoryKardexRepository.findByMaterialId(materialId).stream()
                .map(this::toMaterialInventoryKardexResponse)
                .collect(Collectors.toList());
    }

    /**
     * Kardex paginado por material (más recientes primero). Tamaño máximo de página: 100.
     */
    public MaterialInventoryKardexPageResponse getMaterialKardexPage(Long materialId, int page, int size) {
        int pageSize = Math.min(Math.max(size, 1), 100);
        int pageIndex = Math.max(page, 0);
        Pageable pageable = PageRequest.of(pageIndex, pageSize, Sort.by(Sort.Direction.DESC, "movementDate"));
        Page<MaterialInventoryKardex> result =
                materialInventoryKardexRepository.findByMaterialIdOrderByMovementDateDesc(materialId, pageable);
        List<MaterialInventoryKardexResponse> content = result.getContent().stream()
                .map(this::toMaterialInventoryKardexResponse)
                .collect(Collectors.toList());
        return MaterialInventoryKardexPageResponse.builder()
                .content(content)
                .totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .size(result.getSize())
                .number(result.getNumber())
                .first(result.isFirst())
                .last(result.isLast())
                .build();
    }

    /**
     * Obtiene el kardex por tipo de movimiento
     */
    public List<MaterialInventoryKardexResponse> getMaterialKardexByMovementType(String movementType) {
        return materialInventoryKardexRepository.findByMovementType(movementType).stream()
                .map(this::toMaterialInventoryKardexResponse)
                .collect(Collectors.toList());
    }

    /**
     * Obtiene el kardex por referencia (ej: orden de compra, orden de producción)
     */
    public List<MaterialInventoryKardexResponse> getMaterialKardexByReference(String referenceType, Long referenceId) {
        return materialInventoryKardexRepository.findByReferenceTypeAndReferenceId(referenceType, referenceId).stream()
                .map(this::toMaterialInventoryKardexResponse)
                .collect(Collectors.toList());
    }

    /**
     * Obtiene todo el inventario (todas las ubicaciones)
     * Incluye todos los materiales en todas las ubicaciones, incluso si no tienen stock (muestra 0)
     */
    public List<InventoryLocationResponse> getAllInventory() {
        // Obtener todos los materiales
        List<MaterialEntity> allMaterials = materialRepository.findAll();
        
        // Obtener todas las ubicaciones
        List<LocationEntity> allLocations = locationRepository.findAll();
        
        // Obtener todo el inventario existente
        List<InventoryLocation> existingInventory = inventoryLocationRepository.findAll();
        
        // Crear un mapa para búsqueda rápida: (materialId, locationId) -> InventoryLocation
        java.util.Map<String, InventoryLocation> inventoryMap = existingInventory.stream()
                .collect(Collectors.toMap(
                    il -> il.getMaterialId() + "_" + il.getLocationId(),
                    il -> il
                ));
        
        // Crear respuesta: todos los materiales en todas las ubicaciones
        List<InventoryLocationResponse> result = new java.util.ArrayList<>();
        
        for (MaterialEntity material : allMaterials) {
            for (LocationEntity location : allLocations) {
                String key = material.getId() + "_" + location.getId();
                InventoryLocation inventory = inventoryMap.get(key);
                
                if (inventory != null) {
                    // Material tiene inventario en esta ubicación
                    result.add(toInventoryLocationResponse(inventory));
                } else {
                    // Material no tiene inventario, crear respuesta con cantidad 0
                    result.add(createEmptyInventoryResponse(material.getId(), location.getId(), material, location));
                }
            }
        }
        
        return result;
    }

    /**
     * Crea o actualiza el inventario de un material en una ubicación
     */
    public InventoryLocationResponse createOrUpdateInventory(InventoryLocationRequest request) 
            throws ResourceNotFoundException {
        // Validar que el material existe
        if (!materialRepository.existsById(request.getMaterialId())) {
            throw new ResourceNotFoundException("Material", request.getMaterialId());
        }

        // Validar que la ubicación existe
        if (!locationRepository.existsById(request.getLocationId())) {
            throw new ResourceNotFoundException("Location", request.getLocationId());
        }

        // Buscar si ya existe
        Optional<InventoryLocation> existing = inventoryLocationRepository
                .findByMaterialIdAndLocationId(request.getMaterialId(), request.getLocationId());

        InventoryLocation entity;
        if (existing.isPresent()) {
            // Actualizar
            entity = existing.get();
            entity.setQuantity(request.getQuantity());
        } else {
            // Crear nuevo
            entity = InventoryLocation.builder()
                    .materialId(request.getMaterialId())
                    .locationId(request.getLocationId())
                    .quantity(request.getQuantity())
                    .build();
        }

        InventoryLocation saved = inventoryLocationRepository.save(entity);
        
        // Actualizar quantity global en MaterialEntity (suma de todas las ubicaciones)
        updateGlobalMaterialQuantity(request.getMaterialId());

        return toInventoryLocationResponse(saved);
    }

    /**
     * Actualiza el inventario (incrementa o decrementa)
     */
    public InventoryLocationResponse updateInventory(InventoryUpdateRequest request) 
            throws ResourceNotFoundException {
        InventoryLocation entity = inventoryLocationRepository
                .findByMaterialIdAndLocationId(request.getMaterialId(), request.getLocationId())
                .orElseThrow(() -> new ResourceNotFoundException(
                    "Inventory Location", "Material: " + request.getMaterialId() + ", Location: " + request.getLocationId()));

        entity.setQuantity(request.getQuantity());
        InventoryLocation saved = inventoryLocationRepository.save(entity);
        
        // Actualizar quantity global en MaterialEntity
        updateGlobalMaterialQuantity(request.getMaterialId());

        return toInventoryLocationResponse(saved);
    }

    /**
     * Incrementa el inventario de un material en una ubicación
     */
    public InventoryLocationResponse incrementInventory(Long materialId, Long locationId, BigDecimal quantity) 
            throws ResourceNotFoundException {
        Optional<InventoryLocation> existing = inventoryLocationRepository
                .findByMaterialIdAndLocationId(materialId, locationId);

        InventoryLocation entity;
        if (existing.isPresent()) {
            entity = existing.get();
            entity.setQuantity(entity.getQuantity().add(quantity));
        } else {
            entity = InventoryLocation.builder()
                    .materialId(materialId)
                    .locationId(locationId)
                    .quantity(quantity)
                    .build();
        }

        InventoryLocation saved = inventoryLocationRepository.save(entity);
        
        // Actualizar quantity global en MaterialEntity
        updateGlobalMaterialQuantity(materialId);

        return toInventoryLocationResponse(saved);
    }

    /**
     * Decrementa el inventario de un material en una ubicación
     */
    public InventoryLocationResponse decrementInventory(Long materialId, Long locationId, BigDecimal quantity) 
            throws ResourceNotFoundException, BusinessException {
        InventoryLocation entity = inventoryLocationRepository
                .findByMaterialIdAndLocationId(materialId, locationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                    "Inventory Location", "Material: " + materialId + ", Location: " + locationId));

        BigDecimal newQuantity = entity.getQuantity().subtract(quantity);
        if (newQuantity.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("No hay suficiente inventario. Disponible: " + entity.getQuantity() + ", Solicitado: " + quantity);
        }

        entity.setQuantity(newQuantity);
        InventoryLocation saved = inventoryLocationRepository.save(entity);
        
        // Actualizar quantity global en MaterialEntity
        updateGlobalMaterialQuantity(materialId);

        return toInventoryLocationResponse(saved);
    }

    /**
     * Boleta de salida desde kiosko: descuenta material en la ubicación, sin destino de inventario.
     */
    public InventoryOutflowResponse registerKioskOutflow(InventoryOutflowRequest request)
            throws ResourceNotFoundException, BusinessException {
        LocationEntity location = locationRepository.findById(request.getFromLocationId())
                .orElseThrow(() -> new ResourceNotFoundException("Location", request.getFromLocationId()));
        if (!isKioskLocation(location)) {
            throw new BusinessException("La ubicación no es kiosko (use categoría KIOSKO en la ubicación)");
        }

        InventoryLocation beforeRow = inventoryLocationRepository
                .findByMaterialIdAndLocationId(request.getMaterialId(), request.getFromLocationId())
                .orElse(null);
        BigDecimal qtyBefore = beforeRow != null && beforeRow.getQuantity() != null
                ? beforeRow.getQuantity() : BigDecimal.ZERO;

        if (qtyBefore.compareTo(request.getQuantity()) < 0) {
            throw new BusinessException("Stock insuficiente en kiosko. Disponible: " + qtyBefore
                    + ", solicitado: " + request.getQuantity());
        }

        decrementInventory(request.getMaterialId(), request.getFromLocationId(), request.getQuantity());

        InventoryLocation afterRow = inventoryLocationRepository
                .findByMaterialIdAndLocationId(request.getMaterialId(), request.getFromLocationId())
                .orElse(null);
        BigDecimal qtyAfter = afterRow != null && afterRow.getQuantity() != null
                ? afterRow.getQuantity() : BigDecimal.ZERO;

        InventoryOutflowEntity out = InventoryOutflowEntity.builder()
                .ticketNumber("TEMP")
                .materialId(request.getMaterialId())
                .fromLocationId(request.getFromLocationId())
                .quantity(request.getQuantity())
                .reason(request.getReason())
                .referenceType(request.getReferenceType())
                .referenceId(request.getReferenceId())
                .referenceNumber(request.getReferenceNumber())
                .build();
        out = inventoryOutflowRepository.save(out);
        out.setTicketNumber("KO-" + out.getId());
        out = inventoryOutflowRepository.save(out);

        String desc = request.getReason() != null ? request.getReason()
                : "Salida kiosko material " + request.getMaterialId();
        recordMovement(
                request.getMaterialId(),
                request.getFromLocationId(),
                "KIOSKO_OUTFLOW",
                request.getQuantity().negate(),
                qtyBefore,
                qtyAfter,
                null,
                request.getReferenceType() != null ? request.getReferenceType() : "INVENTORY_OUTFLOW",
                request.getReferenceId(),
                out.getTicketNumber(),
                desc);

        return InventoryOutflowResponse.builder()
                .id(out.getId())
                .ticketNumber(out.getTicketNumber())
                .materialId(out.getMaterialId())
                .fromLocationId(out.getFromLocationId())
                .fromLocationName(location.getName())
                .quantity(out.getQuantity())
                .reason(out.getReason())
                .referenceType(out.getReferenceType())
                .referenceId(out.getReferenceId())
                .referenceNumber(out.getReferenceNumber())
                .createdAt(out.getCreatedAt())
                .build();
    }

    private boolean isKioskLocation(LocationEntity loc) {
        if (loc == null || loc.getCategoria() == null) {
            return false;
        }
        String c = loc.getCategoria().toUpperCase(Locale.ROOT);
        return c.contains("KIOSKO") || c.contains("KIOSK");
    }

    /**
     * Actualiza el quantity global en MaterialEntity (suma de todas las ubicaciones)
     */
    private void updateGlobalMaterialQuantity(Long materialId) {
        BigDecimal totalQuantity = inventoryLocationRepository.getTotalQuantityByMaterialId(materialId);
        if (totalQuantity == null) {
            totalQuantity = BigDecimal.ZERO;
        }
        
        MaterialEntity material = materialRepository.findById(materialId).orElse(null);
        if (material != null) {
            material.setQuantity(totalQuantity);
            materialRepository.save(material);
        }
    }

    // ========== KARDEX ==========

    /**
     * Registra un movimiento en el kardex
     */
    public InventoryKardexResponse recordMovement(
            Long materialId,
            Long locationId,
            String movementType,
            BigDecimal quantity,
            BigDecimal quantityBefore,
            BigDecimal quantityAfter,
            BigDecimal unitCost,
            String referenceType,
            Long referenceId,
            String referenceNumber,
            String description) throws ResourceNotFoundException {

        // Validar que el material existe
        if (!materialRepository.existsById(materialId)) {
            throw new ResourceNotFoundException("Material", materialId);
        }

        // Validar que la ubicación existe
        if (!locationRepository.existsById(locationId)) {
            throw new ResourceNotFoundException("Location", locationId);
        }

        BigDecimal totalCost = null;
        if (unitCost != null && quantity != null) {
            totalCost = unitCost.multiply(quantity.abs());
        }

        InventoryKardexEntity entity = InventoryKardexEntity.builder()
                .materialId(materialId)
                .locationId(locationId)
                .movementType(movementType)
                .quantity(quantity)
                .quantityBefore(quantityBefore)
                .quantityAfter(quantityAfter)
                .unitCost(unitCost)
                .totalCost(totalCost)
                .referenceType(referenceType)
                .referenceId(referenceId)
                .referenceNumber(referenceNumber)
                .description(description)
                .movementDate(LocalDateTime.now())
                .build();

        InventoryKardexEntity saved = inventoryKardexRepository.save(entity);
        return toInventoryKardexResponse(saved);
    }

    /**
     * Obtiene el kardex de un material
     */
    public List<InventoryKardexResponse> getKardexByMaterial(Long materialId) {
        return inventoryKardexRepository.findByMaterialId(materialId).stream()
                .map(this::toInventoryKardexResponse)
                .collect(Collectors.toList());
    }

    /**
     * Obtiene el kardex de una ubicación
     */
    public List<InventoryKardexResponse> getKardexByLocation(Long locationId) {
        return inventoryKardexRepository.findByLocationId(locationId).stream()
                .map(this::toInventoryKardexResponse)
                .collect(Collectors.toList());
    }

    /**
     * Obtiene el kardex de un material en una ubicación específica
     */
    public List<InventoryKardexResponse> getKardexByMaterialAndLocation(Long materialId, Long locationId) {
        return inventoryKardexRepository.findByMaterialIdAndLocationId(materialId, locationId).stream()
                .map(this::toInventoryKardexResponse)
                .collect(Collectors.toList());
    }

    /**
     * Obtiene el kardex por tipo de movimiento
     */
    public List<InventoryKardexResponse> getKardexByMovementType(String movementType) {
        return inventoryKardexRepository.findByMovementType(movementType).stream()
                .map(this::toInventoryKardexResponse)
                .collect(Collectors.toList());
    }

    /**
     * Obtiene el kardex por referencia (ej: orden de compra, orden de producción)
     */
    public List<InventoryKardexResponse> getKardexByReference(String referenceType, Long referenceId) {
        return inventoryKardexRepository.findByReferenceTypeAndReferenceId(referenceType, referenceId).stream()
                .map(this::toInventoryKardexResponse)
                .collect(Collectors.toList());
    }

    // ========== CRITICAL INVENTORY ==========

    /**
     * Obtiene el inventario crítico (materiales con stock bajo)
     */
    public List<CriticalInventoryResponse> getCriticalInventory(Long locationId) {
        List<CriticalInventoryResponse> criticalItems = new java.util.ArrayList<>();
        
        // Obtener inventario por ubicación
        List<InventoryLocation> inventoryList;
        if (locationId != null) {
            inventoryList = inventoryLocationRepository.findByLocationId(locationId);
        } else {
            inventoryList = inventoryLocationRepository.findAll();
        }

        // Procesar inventario por ubicación
        for (InventoryLocation entity : inventoryList) {
            MaterialEntity material = materialRepository.findById(entity.getMaterialId()).orElse(null);
            if (material == null) {
                continue;
            }

            // Obtener stock actual, asegurándose de que no sea null
            BigDecimal currentStock = entity.getQuantity();
            if (currentStock == null) {
                currentStock = BigDecimal.ZERO;
            }
            
            Integer minStock = material.getMin();
            Integer maxStock = material.getMax();

            // Verificar si es crítico
            boolean isCritical = false;
            String priority = "OK";
            String reason = null;
            BigDecimal deficit = BigDecimal.ZERO;

            // Si el stock actual es 0 o negativo, siempre es crítico
            if (currentStock.compareTo(BigDecimal.ZERO) <= 0) {
                isCritical = true;
                priority = "CRITICAL";
                reason = "STOCK_ZERO";
                if (minStock != null && minStock > 0) {
                    deficit = BigDecimal.valueOf(minStock);
                } else {
                    deficit = BigDecimal.ONE; // Al menos mostrar 1 como déficit
                }
            } else if (minStock != null && minStock > 0) {
                // Si hay stock mínimo definido, verificar si está por debajo
                if (currentStock.compareTo(BigDecimal.valueOf(minStock)) < 0) {
                    isCritical = true;
                    priority = "CRITICAL";
                    reason = "BELOW_MIN";
                    deficit = BigDecimal.valueOf(minStock).subtract(currentStock);
                } else if (maxStock != null && maxStock > 0) {
                    // Verificar umbral mínimo (20% del máximo por defecto)
                    BigDecimal threshold = BigDecimal.valueOf(maxStock).multiply(BigDecimal.valueOf(0.20));
                    if (currentStock.compareTo(threshold) < 0) {
                        isCritical = true;
                        priority = "WARNING";
                        reason = "BELOW_THRESHOLD";
                        deficit = threshold.subtract(currentStock);
                    }
                }
            } else if (minStock == null || minStock == 0) {
                // Si no hay stock mínimo definido pero el stock es muy bajo (menos de 5 unidades), mostrar advertencia
                if (currentStock.compareTo(BigDecimal.valueOf(5)) < 0) {
                    isCritical = true;
                    priority = "WARNING";
                    reason = "LOW_STOCK_NO_MIN";
                    deficit = BigDecimal.valueOf(5).subtract(currentStock);
                }
            }

            if (isCritical) {
                LocationEntity location = locationRepository.findById(entity.getLocationId()).orElse(null);
                criticalItems.add(CriticalInventoryResponse.builder()
                        .materialId(entity.getMaterialId())
                        .materialSku(material.getSku())
                        .materialName(material.getName())
                        .locationId(entity.getLocationId())
                        .locationCode(location != null ? location.getCode() : null)
                        .locationName(location != null ? location.getName() : null)
                        .currentStock(currentStock)
                        .minStock(minStock)
                        .maxStock(maxStock)
                        .deficit(deficit)
                        .priority(priority)
                        .reason(reason)
                        .build());
            }
        }

        // También incluir materiales del inventario agregado (MaterialInventory) que no tienen ubicación específica
        // pero tienen stock bajo o cero
        if (locationId == null) { // Solo si no se está filtrando por ubicación específica
            List<MaterialInventory> materialInventoryList = materialInventoryRepository.findAll();
            java.util.Set<Long> processedMaterialIds = inventoryList.stream()
                    .map(InventoryLocation::getMaterialId)
                    .collect(Collectors.toSet());

            for (MaterialInventory materialInv : materialInventoryList) {
                // Solo procesar si no se procesó ya en el inventario por ubicación
                if (processedMaterialIds.contains(materialInv.getMaterialId())) {
                    continue;
                }

                MaterialEntity material = materialRepository.findById(materialInv.getMaterialId()).orElse(null);
                if (material == null) {
                    continue;
                }

                BigDecimal currentStock = materialInv.getQuantity();
                if (currentStock == null) {
                    currentStock = BigDecimal.ZERO;
                }

                Integer minStock = material.getMin();
                Integer maxStock = material.getMax();

                boolean isCritical = false;
                String priority = "OK";
                String reason = null;
                BigDecimal deficit = BigDecimal.ZERO;

                // Si el stock actual es 0 o negativo, siempre es crítico
                if (currentStock.compareTo(BigDecimal.ZERO) <= 0) {
                    isCritical = true;
                    priority = "CRITICAL";
                    reason = "STOCK_ZERO";
                    if (minStock != null && minStock > 0) {
                        deficit = BigDecimal.valueOf(minStock);
                    } else {
                        deficit = BigDecimal.ONE;
                    }
                } else if (minStock != null && minStock > 0) {
                    if (currentStock.compareTo(BigDecimal.valueOf(minStock)) < 0) {
                        isCritical = true;
                        priority = "CRITICAL";
                        reason = "BELOW_MIN";
                        deficit = BigDecimal.valueOf(minStock).subtract(currentStock);
                    }
                } else if (minStock == null || minStock == 0) {
                    if (currentStock.compareTo(BigDecimal.valueOf(5)) < 0) {
                        isCritical = true;
                        priority = "WARNING";
                        reason = "LOW_STOCK_NO_MIN";
                        deficit = BigDecimal.valueOf(5).subtract(currentStock);
                    }
                }

                if (isCritical) {
                    criticalItems.add(CriticalInventoryResponse.builder()
                            .materialId(materialInv.getMaterialId())
                            .materialSku(material.getSku())
                            .materialName(material.getName())
                            .locationId(null) // Sin ubicación específica
                            .locationCode(null)
                            .locationName("Sin ubicación específica")
                            .currentStock(currentStock)
                            .minStock(minStock)
                            .maxStock(maxStock)
                            .deficit(deficit)
                            .priority(priority)
                            .reason(reason)
                            .build());
                }
            }
            
            // También verificar materiales que tienen minStock definido pero no tienen registro en InventoryLocation
            // Esto cubre el caso donde un material nunca ha tenido stock en ninguna ubicación
            java.util.Set<Long> allProcessedMaterialIds = new java.util.HashSet<>(processedMaterialIds);
            materialInventoryList.forEach(mi -> allProcessedMaterialIds.add(mi.getMaterialId()));
            
            List<MaterialEntity> allMaterials = materialRepository.findAll();
            for (MaterialEntity material : allMaterials) {
                if (allProcessedMaterialIds.contains(material.getId())) {
                    continue; // Ya procesado
                }
                
                // Solo considerar materiales con stock mínimo definido o que deberían tener stock
                Integer minStock = material.getMin();
                if (minStock == null || minStock == 0) {
                    continue; // Sin mínimo definido, no es crítico si no hay registro
                }
                
                // Si tiene minStock definido pero no hay registro en InventoryLocation ni MaterialInventory,
                // significa que nunca ha tenido stock, lo cual es crítico
                BigDecimal totalStock = inventoryLocationRepository.getTotalQuantityByMaterialId(material.getId());
                if (totalStock == null) {
                    totalStock = BigDecimal.ZERO;
                }
                
                if (totalStock.compareTo(BigDecimal.ZERO) <= 0 || 
                    (minStock != null && minStock > 0 && totalStock.compareTo(BigDecimal.valueOf(minStock)) < 0)) {
                    boolean isCritical = false;
                    String priority = "CRITICAL";
                    String reason = null;
                    BigDecimal deficit = BigDecimal.ZERO;
                    
                    if (totalStock.compareTo(BigDecimal.ZERO) <= 0) {
                        isCritical = true;
                        reason = "STOCK_ZERO";
                        deficit = BigDecimal.valueOf(minStock);
                    } else if (totalStock.compareTo(BigDecimal.valueOf(minStock)) < 0) {
                        isCritical = true;
                        reason = "BELOW_MIN";
                        deficit = BigDecimal.valueOf(minStock).subtract(totalStock);
                    }
                    
                    if (isCritical) {
                        criticalItems.add(CriticalInventoryResponse.builder()
                                .materialId(material.getId())
                                .materialSku(material.getSku())
                                .materialName(material.getName())
                                .locationId(null)
                                .locationCode(null)
                                .locationName("Todas las ubicaciones")
                                .currentStock(totalStock)
                                .minStock(minStock)
                                .maxStock(material.getMax())
                                .deficit(deficit)
                                .priority(priority)
                                .reason(reason)
                                .build());
                    }
                }
            }
        }

        return criticalItems;
    }

    /**
     * Obtiene el inventario agregado de múltiples ubicaciones (suma de todas)
     * Útil para "Todos los Kioskos" o inventarios generales
     */
    public List<InventoryLocationResponse> getAggregatedInventoryByCategory(String category) {
        // Obtener todas las ubicaciones de esta categoría
        List<LocationEntity> categoryLocations = locationRepository.findAll().stream()
                .filter(loc -> {
                    String catUpper = (loc.getCategoria() != null ? loc.getCategoria() : "").toUpperCase();
                    String nameUpper = (loc.getName() != null ? loc.getName() : "").toUpperCase();
                    
                    if ("KIOSKO".equals(category)) {
                        return catUpper.contains("KIOSKO") || catUpper.contains("KIOSK");
                    } else if ("BODEGA_MP".equals(category)) {
                        return (catUpper.contains("BODEGA") && catUpper.contains("MP")) ||
                               catUpper.contains("MATERIA PRIMA") ||
                               nameUpper.contains("MATERIA PRIMA") ||
                               nameUpper.contains("BODEGA MP") ||
                               nameUpper.contains("BODEGA-MP");
                    } else if ("BODEGA_PT".equals(category)) {
                        return (catUpper.contains("BODEGA") && catUpper.contains("PT")) ||
                               catUpper.contains("PRODUCTO TERMINADO") ||
                               nameUpper.contains("PRODUCTO TERMINADO") ||
                               nameUpper.contains("BODEGA PT") ||
                               nameUpper.contains("BODEGA-PT");
                    } else if ("VENDEDOR".equals(category)) {
                        return catUpper.contains("VENDEDOR") || nameUpper.contains("VENDEDOR");
                    } else if ("ONLINE".equals(category)) {
                        return catUpper.contains("ONLINE") || nameUpper.contains("ONLINE");
                    }
                    return false;
                })
                .collect(Collectors.toList());

        if (categoryLocations.isEmpty()) {
            // Si no hay ubicaciones, devolver todos los materiales con cantidad 0
            List<MaterialEntity> allMaterials = materialRepository.findAll();
            return allMaterials.stream()
                    .map(material -> createEmptyInventoryResponse(material.getId(), null, material, null))
                    .collect(Collectors.toList());
        }

        // Obtener todos los materiales
        List<MaterialEntity> allMaterials = materialRepository.findAll();
        
        // Obtener inventario de todas las ubicaciones de esta categoría
        List<Long> locationIds = categoryLocations.stream()
                .map(LocationEntity::getId)
                .collect(Collectors.toList());
        
        List<InventoryLocation> allInventory = inventoryLocationRepository.findAll().stream()
                .filter(inv -> locationIds.contains(inv.getLocationId()))
                .collect(Collectors.toList());
        
        // Agrupar por material y sumar cantidades
        java.util.Map<Long, BigDecimal> aggregatedQuantities = allInventory.stream()
                .collect(Collectors.groupingBy(
                    InventoryLocation::getMaterialId,
                    Collectors.reducing(
                        BigDecimal.ZERO,
                        InventoryLocation::getQuantity,
                        BigDecimal::add
                    )
                ));
        
        // Crear respuesta con cantidades agregadas
        return allMaterials.stream()
                .map(material -> {
                    BigDecimal totalQuantity = aggregatedQuantities.getOrDefault(material.getId(), BigDecimal.ZERO);
                    
                    // Usar la primera ubicación como referencia para nombre (o crear una genérica)
                    String locationName = categoryLocations.size() == 1 
                        ? categoryLocations.get(0).getName()
                        : getCategoryDisplayName(category);
                    String locationCode = categoryLocations.size() == 1 
                        ? categoryLocations.get(0).getCode()
                        : category;
                    
                    return InventoryLocationResponse.builder()
                            .id(null) // No tiene ID único porque es agregado
                            .materialId(material.getId())
                            .materialSku(material.getSku())
                            .materialName(material.getName())
                            .locationId(null) // No tiene locationId único porque es agregado
                            .locationCode(locationCode)
                            .locationName(locationName)
                            .quantity(totalQuantity)
                            .materialMin(material.getMin())
                            .materialMax(material.getMax())
                            .createdAt(null)
                            .createdBy(null)
                            .updatedAt(null)
                            .updatedBy(null)
                            .build();
                })
                .collect(Collectors.toList());
    }

    /**
     * Obtiene el inventario de una categoría específica (busca automáticamente la ubicación)
     */
    public List<InventoryLocationResponse> getInventoryByCategory(String category) {
        // Buscar la ubicación de esta categoría
        List<LocationEntity> categoryLocations = locationRepository.findAll().stream()
                .filter(loc -> {
                    String catUpper = (loc.getCategoria() != null ? loc.getCategoria() : "").toUpperCase();
                    String nameUpper = (loc.getName() != null ? loc.getName() : "").toUpperCase();
                    
                    if ("BODEGA_MP".equals(category)) {
                        return (catUpper.contains("BODEGA") && catUpper.contains("MP")) ||
                               catUpper.contains("MATERIA PRIMA") ||
                               nameUpper.contains("MATERIA PRIMA") ||
                               nameUpper.contains("BODEGA MP") ||
                               nameUpper.contains("BODEGA-MP");
                    } else if ("BODEGA_PT".equals(category)) {
                        return (catUpper.contains("BODEGA") && catUpper.contains("PT")) ||
                               catUpper.contains("PRODUCTO TERMINADO") ||
                               nameUpper.contains("PRODUCTO TERMINADO") ||
                               nameUpper.contains("BODEGA PT") ||
                               nameUpper.contains("BODEGA-PT");
                    } else if ("VENDEDOR".equals(category)) {
                        return catUpper.contains("VENDEDOR") || nameUpper.contains("VENDEDOR");
                    } else if ("ONLINE".equals(category)) {
                        return catUpper.contains("ONLINE") || nameUpper.contains("ONLINE");
                    }
                    return false;
                })
                .collect(Collectors.toList());

        if (categoryLocations.isEmpty()) {
            // Si no hay ubicación, devolver materiales con cantidad 0
            List<MaterialEntity> allMaterials = materialRepository.findAll();
            return allMaterials.stream()
                    .map(material -> {
                        LocationEntity dummyLocation = LocationEntity.builder()
                                .id(null)
                                .name(getCategoryDisplayName(category))
                                .code(category)
                                .build();
                        return createEmptyInventoryResponse(material.getId(), null, material, dummyLocation);
                    })
                    .collect(Collectors.toList());
        }

        // Si hay múltiples ubicaciones, usar la primera (o podrías sumarlas)
        Long locationId = categoryLocations.get(0).getId();
        return getInventoryByLocation(locationId);
    }

    private String getCategoryDisplayName(String category) {
        switch (category) {
            case "BODEGA_MP": return "Bodega Materia Prima";
            case "BODEGA_PT": return "Bodega Producto Terminado";
            case "VENDEDOR": return "Vendedor";
            case "ONLINE": return "Online";
            case "KIOSKO": return "Todos los Kioskos";
            default: return category;
        }
    }

    // ========== INITIALIZE INVENTORY ==========

    /**
     * Inicializa el inventario para todos los materiales que no tienen registro
     * Los materiales no tienen ubicación, solo se almacenan en la planta
     * Crea registros en la tabla material_inventory (sin location_id)
     */
    public int initializeMissingInventory(Long locationId) throws ResourceNotFoundException {
        // Obtener todos los materiales
        List<MaterialEntity> allMaterials = materialRepository.findAll();
        
        int createdCount = 0;
        
        // Crear registros en material_inventory (sin ubicación)
        for (MaterialEntity material : allMaterials) {
            // Verificar si ya existe registro para este material
            boolean exists = materialInventoryRepository.existsByMaterialId(material.getId());
            
            if (!exists) {
                // Crear registro con cantidad 0 (sin location_id)
                MaterialInventory newInventory = MaterialInventory.builder()
                        .materialId(material.getId())
                        .quantity(BigDecimal.ZERO)
                        .build();
                
                materialInventoryRepository.save(newInventory);
                createdCount++;
            }
        }
        
        return createdCount;
    }

    // ========== HELPER METHODS ==========

    private InventoryLocationResponse toInventoryLocationResponse(InventoryLocation entity) {
        MaterialEntity material = materialRepository.findById(entity.getMaterialId()).orElse(null);
        LocationEntity location = locationRepository.findById(entity.getLocationId()).orElse(null);

        return InventoryLocationResponse.builder()
                .id(entity.getId())
                .materialId(entity.getMaterialId())
                .materialSku(material != null ? material.getSku() : null)
                .materialName(material != null ? material.getName() : null)
                .locationId(entity.getLocationId())
                .locationCode(location != null ? location.getCode() : null)
                .locationName(location != null ? location.getName() : null)
                .quantity(entity.getQuantity())
                .materialMin(material != null ? material.getMin() : null)
                .materialMax(material != null ? material.getMax() : null)
                .createdAt(entity.getCreatedAt())
                .createdBy(entity.getCreatedBy())
                .updatedAt(entity.getUpdatedAt())
                .updatedBy(entity.getUpdatedBy())
                .build();
    }

    private InventoryKardexResponse toInventoryKardexResponse(InventoryKardexEntity entity) {
        MaterialEntity material = materialRepository.findById(entity.getMaterialId()).orElse(null);
        LocationEntity location = locationRepository.findById(entity.getLocationId()).orElse(null);

        return InventoryKardexResponse.builder()
                .id(entity.getId())
                .materialId(entity.getMaterialId())
                .materialSku(material != null ? material.getSku() : null)
                .materialName(material != null ? material.getName() : null)
                .locationId(entity.getLocationId())
                .locationCode(location != null ? location.getCode() : null)
                .locationName(location != null ? location.getName() : null)
                .movementType(entity.getMovementType())
                .quantity(entity.getQuantity())
                .quantityBefore(entity.getQuantityBefore())
                .quantityAfter(entity.getQuantityAfter())
                .unitCost(entity.getUnitCost())
                .totalCost(entity.getTotalCost())
                .referenceType(entity.getReferenceType())
                .referenceId(entity.getReferenceId())
                .referenceNumber(entity.getReferenceNumber())
                .description(entity.getDescription())
                .movementDate(entity.getMovementDate())
                .createdAt(entity.getCreatedAt())
                .createdBy(entity.getCreatedBy())
                .build();
    }

    private MaterialInventoryKardexResponse toMaterialInventoryKardexResponse(MaterialInventoryKardex entity) {
        MaterialEntity material = materialRepository.findById(entity.getMaterialId()).orElse(null);

        // Separar entradas y salidas para método FIFO
        BigDecimal cantidadEntrada = null;
        BigDecimal costoUnitarioEntrada = null;
        BigDecimal totalEntrada = null;
        BigDecimal cantidadSalida = null;
        BigDecimal costoUnitarioSalida = null;
        BigDecimal totalSalida = null;

        if (entity.getQuantity() != null) {
            if (entity.getQuantity().compareTo(BigDecimal.ZERO) >= 0) {
                // Es una entrada
                cantidadEntrada = entity.getQuantity();
                costoUnitarioEntrada = entity.getUnitCost();
                totalEntrada = entity.getTotalCost();
            } else {
                // Es una salida
                cantidadSalida = entity.getQuantity().abs();
                costoUnitarioSalida = entity.getUnitCost();
                totalSalida = entity.getTotalCost();
            }
        }

        // Obtener lotes FIFO disponibles después de este movimiento
        List<MaterialFifoBatch> availableBatches = materialFifoBatchRepository
                .findAvailableBatchesByMaterialIdOrderByEntryDate(entity.getMaterialId());
        
        List<MaterialInventoryKardexResponse.FifoBatchInfo> lotesFifo = availableBatches.stream()
                .map(batch -> MaterialInventoryKardexResponse.FifoBatchInfo.builder()
                        .fechaEntrada(batch.getEntryDate())
                        .cantidad(batch.getQuantityAvailable())
                        .costoUnitario(batch.getUnitCost())
                        .total(batch.getQuantityAvailable().multiply(batch.getUnitCost()))
                        .build())
                .collect(Collectors.toList());

        return MaterialInventoryKardexResponse.builder()
                .id(entity.getId())
                .materialId(entity.getMaterialId())
                .materialSku(material != null ? material.getSku() : null)
                .materialName(material != null ? material.getName() : null)
                .uomCode(material != null ? resolveMaterialUomCode(material) : null)
                .uomName(material != null ? resolveMaterialUomName(material) : null)
                .movementType(entity.getMovementType())
                .quantity(entity.getQuantity())
                .quantityBefore(entity.getQuantityBefore())
                .quantityAfter(entity.getQuantityAfter())
                .unitCost(entity.getUnitCost())
                .totalCost(entity.getTotalCost())
                .referenceType(entity.getReferenceType())
                .referenceId(entity.getReferenceId())
                .referenceNumber(entity.getReferenceNumber())
                .description(entity.getDescription())
                .movementDate(entity.getMovementDate())
                .createdAt(entity.getCreatedAt())
                .createdBy(entity.getCreatedBy())
                .cantidadEntrada(cantidadEntrada)
                .costoUnitarioEntrada(costoUnitarioEntrada)
                .totalEntrada(totalEntrada)
                .cantidadSalida(cantidadSalida)
                .costoUnitarioSalida(costoUnitarioSalida)
                .totalSalida(totalSalida)
                .lotesFifo(lotesFifo)
                .build();
    }

    private String resolveUomCode(Long uomId) {
        if (uomId == null) return null;
        return uomRepository.findById(uomId).map(UomEntity::getCode).orElse(null);
    }

    private String resolveUomName(Long uomId) {
        if (uomId == null) return null;
        return uomRepository.findById(uomId).map(UomEntity::getName).orElse(null);
    }

    private String resolveMaterialUomCode(MaterialEntity material) {
        if (material == null) return null;
        Long uomId = material.getManufacturingUomId() != null
                ? material.getManufacturingUomId()
                : material.getUomId();
        return resolveUomCode(uomId);
    }

    private String resolveMaterialUomName(MaterialEntity material) {
        if (material == null) return null;
        Long uomId = material.getManufacturingUomId() != null
                ? material.getManufacturingUomId()
                : material.getUomId();
        return resolveUomName(uomId);
    }

    private String buildConversionText(MaterialEntity material) {
        if (material == null || material.getPurchaseQuantity() == null) return null;
        String purchaseName = resolveUomName(material.getPurchaseUomId());
        String manufacturingName = resolveMaterialUomName(material);
        if (purchaseName == null || manufacturingName == null) return null;
        return "1 " + purchaseName + " = "
                + material.getPurchaseQuantity().stripTrailingZeros().toPlainString()
                + " " + manufacturingName;
    }

    /**
     * Crea una respuesta de inventario vacía (cantidad 0) para un material que no tiene registro
     */
    private InventoryLocationResponse createEmptyInventoryResponse(
            Long materialId, 
            Long locationId, 
            MaterialEntity material,
            LocationEntity location) {
        return InventoryLocationResponse.builder()
                .id(null) // No tiene ID porque no existe registro
                .materialId(materialId)
                .materialSku(material != null ? material.getSku() : null)
                .materialName(material != null ? material.getName() : null)
                .locationId(locationId)
                .locationCode(location != null ? location.getCode() : null)
                .locationName(location != null ? location.getName() : null)
                .quantity(BigDecimal.ZERO) // Cantidad 0
                .materialMin(material != null ? material.getMin() : null)
                .materialMax(material != null ? material.getMax() : null)
                .createdAt(null)
                .createdBy(null)
                .updatedAt(null)
                .updatedBy(null)
                .build();
    }

    // ========== INVENTORY TRANSFERS ==========

    /**
     * Crea una transferencia de inventario
     */
    public InventoryTransferResponse createTransfer(InventoryTransferRequest request) 
            throws ResourceNotFoundException, BusinessException {
        
        // Validar que las ubicaciones existen
        if (!locationRepository.existsById(request.getFromLocationId())) {
            throw new ResourceNotFoundException("Location", request.getFromLocationId());
        }
        if (!locationRepository.existsById(request.getToLocationId())) {
            throw new ResourceNotFoundException("Location", request.getToLocationId());
        }

        kioskInventoryGuard.assertSupervisorMayModifyKioskInventory(request.getFromLocationId());
        kioskInventoryGuard.assertSupervisorMayModifyKioskInventory(request.getToLocationId());

        // Validar que no sea la misma ubicación
        if (request.getFromLocationId().equals(request.getToLocationId())) {
            throw new BusinessException("No se puede transferir a la misma ubicación");
        }

        // Validar que se especifique material o producto, pero no ambos
        if (request.getMaterialId() == null && request.getProductId() == null) {
            throw new BusinessException("Debe especificar un material o un producto");
        }
        if (request.getMaterialId() != null && request.getProductId() != null) {
            throw new BusinessException("Debe especificar solo un material o un producto, no ambos");
        }

        // Validar que el material o producto existe
        if (request.getMaterialId() != null && !materialRepository.existsById(request.getMaterialId())) {
            throw new ResourceNotFoundException("Material", request.getMaterialId());
        }
        if (request.getProductId() != null && !productRepository.existsById(request.getProductId())) {
            throw new ResourceNotFoundException("Product", request.getProductId());
        }

        // Validar stock disponible en origen
        if (request.getMaterialId() != null) {
            InventoryLocation originInventory = inventoryLocationRepository
                    .findByMaterialIdAndLocationId(request.getMaterialId(), request.getFromLocationId())
                    .orElse(null);
            
            if (originInventory == null || originInventory.getQuantity().compareTo(request.getQuantity()) < 0) {
                BigDecimal available = originInventory != null ? originInventory.getQuantity() : BigDecimal.ZERO;
                throw new BusinessException("Stock insuficiente en ubicación origen. Disponible: " + available + ", Solicitado: " + request.getQuantity());
            }
        } else {
            // Para productos, SIEMPRE considerar colorId para soportar variantes de color
            ProductInventoryLocation originInventory = productInventoryLocationRepository
                    .findByProductIdAndLocationIdAndColorId(
                        request.getProductId(), 
                        request.getFromLocationId(),
                        request.getColorId()) // Puede ser null, pero se busca específicamente con null
                    .orElse(null);
            
            if (originInventory == null || originInventory.getQuantity().compareTo(request.getQuantity()) < 0) {
                BigDecimal available = originInventory != null ? originInventory.getQuantity() : BigDecimal.ZERO;
                throw new BusinessException("Stock insuficiente en ubicación origen. Disponible: " + available + ", Solicitado: " + request.getQuantity());
            }
        }

        // Obtener usuario actual
        Long currentUserId = securityUtil.getCurrentUserId();

        // Crear la transferencia
        InventoryTransfer transfer = InventoryTransfer.builder()
                .fromLocationId(request.getFromLocationId())
                .toLocationId(request.getToLocationId())
                .materialId(request.getMaterialId())
                .productId(request.getProductId())
                .colorId(request.getColorId()) // Incluir colorId para productos con variantes
                .quantity(request.getQuantity())
                .reason(request.getReason())
                .status("PENDING")
                .createdBy(currentUserId)
                .updatedBy(currentUserId)
                .build();

        InventoryTransfer saved = inventoryTransferRepository.save(transfer);
        
        // Actualizar updatedBy después de ejecutar la transferencia
        if (saved.getStatus().equals("COMPLETED")) {
            saved.setUpdatedBy(currentUserId);
            inventoryTransferRepository.save(saved);
        }

        // Ejecutar la transferencia (decrementar origen, incrementar destino)
        executeTransfer(saved);

        return toInventoryTransferResponse(saved);
    }

    /**
     * Crea una transferencia masiva de inventario
     */
    public List<InventoryTransferResponse> createBulkTransfer(BulkInventoryTransferRequest request) 
            throws ResourceNotFoundException, BusinessException {
        
        // Validar que las ubicaciones existen
        if (!locationRepository.existsById(request.getFromLocationId())) {
            throw new ResourceNotFoundException("Location", request.getFromLocationId());
        }
        if (!locationRepository.existsById(request.getToLocationId())) {
            throw new ResourceNotFoundException("Location", request.getToLocationId());
        }

        // Validar que no sea la misma ubicación
        if (request.getFromLocationId().equals(request.getToLocationId())) {
            throw new BusinessException("No se puede transferir a la misma ubicación");
        }

        List<InventoryTransferResponse> responses = new java.util.ArrayList<>();

        for (BulkInventoryTransferRequest.InventoryTransferItemRequest item : request.getItems()) {
            // Validar que se especifique material o producto
            if (item.getMaterialId() == null && item.getProductId() == null) {
                throw new BusinessException("Cada ítem debe especificar un material o un producto");
            }
            if (item.getMaterialId() != null && item.getProductId() != null) {
                throw new BusinessException("Cada ítem debe especificar solo un material o un producto, no ambos");
            }

            // Crear request individual
            InventoryTransferRequest transferRequest = InventoryTransferRequest.builder()
                    .fromLocationId(request.getFromLocationId())
                    .toLocationId(request.getToLocationId())
                    .materialId(item.getMaterialId())
                    .productId(item.getProductId())
                    .colorId(item.getColorId())
                    .quantity(item.getQuantity())
                    .reason(request.getReason())
                    .build();

            // Crear transferencia individual
            InventoryTransferResponse response = createTransfer(transferRequest);
            responses.add(response);
        }

        return responses;
    }

    /**
     * Ejecuta una transferencia (actualiza inventarios)
     */
    private void executeTransfer(InventoryTransfer transfer) 
            throws ResourceNotFoundException, BusinessException {

        if ("COMPLETED".equalsIgnoreCase(transfer.getStatus())) {
            return;
        }
        
        if (transfer.getMaterialId() != null) {
            // Transferencia de material
            // Obtener inventarios antes de modificar
            InventoryLocation originInventory = inventoryLocationRepository
                    .findByMaterialIdAndLocationId(transfer.getMaterialId(), transfer.getFromLocationId())
                    .orElse(null);
            InventoryLocation destInventory = inventoryLocationRepository
                    .findByMaterialIdAndLocationId(transfer.getMaterialId(), transfer.getToLocationId())
                    .orElse(null);

            BigDecimal originBefore = originInventory != null ? originInventory.getQuantity() : BigDecimal.ZERO;
            BigDecimal destBefore = destInventory != null ? destInventory.getQuantity() : BigDecimal.ZERO;

            // Decrementar origen
            decrementInventory(transfer.getMaterialId(), transfer.getFromLocationId(), transfer.getQuantity());
            
            // Incrementar destino
            incrementInventory(transfer.getMaterialId(), transfer.getToLocationId(), transfer.getQuantity());

            // Obtener inventarios después de modificar
            InventoryLocation originAfter = inventoryLocationRepository
                    .findByMaterialIdAndLocationId(transfer.getMaterialId(), transfer.getFromLocationId())
                    .orElse(null);
            InventoryLocation destAfter = inventoryLocationRepository
                    .findByMaterialIdAndLocationId(transfer.getMaterialId(), transfer.getToLocationId())
                    .orElse(null);

            BigDecimal originAfterQty = originAfter != null ? originAfter.getQuantity() : BigDecimal.ZERO;
            BigDecimal destAfterQty = destAfter != null ? destAfter.getQuantity() : BigDecimal.ZERO;

            LocationEntity toLocation = locationRepository.findById(transfer.getToLocationId()).orElse(null);
            LocationEntity fromLocation = locationRepository.findById(transfer.getFromLocationId()).orElse(null);

            recordMovement(
                    transfer.getMaterialId(),
                    transfer.getFromLocationId(),
                    "TRANSFER_OUT",
                    transfer.getQuantity().negate(),
                    originBefore,
                    originAfterQty,
                    null,
                    "TRANSFER",
                    transfer.getId(),
                    "TRF-" + transfer.getId(),
                    "Transferencia a " + (toLocation != null ? toLocation.getName() : "")
            );

            recordMovement(
                    transfer.getMaterialId(),
                    transfer.getToLocationId(),
                    "TRANSFER_IN",
                    transfer.getQuantity(),
                    destBefore,
                    destAfterQty,
                    null,
                    "TRANSFER",
                    transfer.getId(),
                    "TRF-" + transfer.getId(),
                    "Transferencia desde " + (fromLocation != null ? fromLocation.getName() : "")
            );
        } else {
            // Transferencia de producto
            // SIEMPRE usar el colorId de la transferencia para soportar variantes de color
            Long colorId = transfer.getColorId();
            
            // Obtener inventarios antes de modificar (considerando colorId)
            ProductInventoryLocation originInventory = productInventoryLocationRepository
                    .findByProductIdAndLocationIdAndColorId(
                        transfer.getProductId(), 
                        transfer.getFromLocationId(),
                        colorId) // Puede ser null, pero se busca específicamente con null
                    .orElse(null);
            ProductInventoryLocation destInventory = productInventoryLocationRepository
                    .findByProductIdAndLocationIdAndColorId(
                        transfer.getProductId(), 
                        transfer.getToLocationId(),
                        colorId) // Puede ser null, pero se busca específicamente con null
                    .orElse(null);

            BigDecimal originBefore = originInventory != null ? originInventory.getQuantity() : BigDecimal.ZERO;
            BigDecimal destBefore = destInventory != null ? destInventory.getQuantity() : BigDecimal.ZERO;

            // Obtener ubicaciones para la descripción
            LocationEntity toLocation = locationRepository.findById(transfer.getToLocationId()).orElse(null);
            LocationEntity fromLocation = locationRepository.findById(transfer.getFromLocationId()).orElse(null);
            
            // Calcular costo promedio FIFO antes de decrementar (para crear lote en destino)
            BigDecimal transferUnitCost = null;
            
            // Obtener lotes FIFO disponibles en el origen para calcular costo promedio
            List<ProductFifoBatch> availableBatches = productFifoBatchRepository
                    .findAvailableBatchesByProductAndLocationAndColor(
                            transfer.getProductId(), 
                            transfer.getFromLocationId(), 
                            colorId);
            
            BigDecimal remainingQuantity = transfer.getQuantity();
            BigDecimal totalCost = BigDecimal.ZERO;
            BigDecimal totalQuantityConsumed = BigDecimal.ZERO;
            
            for (ProductFifoBatch batch : availableBatches) {
                if (remainingQuantity.compareTo(BigDecimal.ZERO) <= 0) {
                    break;
                }
                
                BigDecimal quantityToConsume = remainingQuantity.min(batch.getQuantityAvailable());
                BigDecimal batchCost = quantityToConsume.multiply(batch.getUnitCost());
                
                totalCost = totalCost.add(batchCost);
                totalQuantityConsumed = totalQuantityConsumed.add(quantityToConsume);
                
                remainingQuantity = remainingQuantity.subtract(quantityToConsume);
            }
            
            if (totalQuantityConsumed.compareTo(BigDecimal.ZERO) > 0) {
                transferUnitCost = totalCost.divide(totalQuantityConsumed, 2, java.math.RoundingMode.HALF_UP);
            }
            // Si no hay lotes FIFO disponibles, transferUnitCost será null y no se creará lote FIFO en destino

            // Decrementar origen (esto consumirá los lotes FIFO si existen)
            // Pasar colorId para soportar variantes de color
            productInventoryService.decrementInventory(
                transfer.getProductId(), 
                transfer.getFromLocationId(), 
                colorId, // Pasar colorId de la transferencia
                transfer.getQuantity(),
                "TRANSFER",
                transfer.getId(),
                "TRF-" + transfer.getId(),
                "Transferencia a " + (toLocation != null ? toLocation.getName() : "")
            );
            
            // Incrementar destino con el costo FIFO calculado (esto creará un nuevo lote FIFO)
            // Solo crear lote FIFO si hay un costo válido
            if (transferUnitCost != null && transferUnitCost.compareTo(BigDecimal.ZERO) > 0) {
                productInventoryService.incrementInventory(
                        transfer.getProductId(), 
                        transfer.getToLocationId(), 
                        colorId, // Pasar colorId de la transferencia
                        transfer.getQuantity(),
                        transferUnitCost, // Pasar el costo FIFO calculado
                        "TRANSFER",
                        transfer.getId(),
                        "TRF-" + transfer.getId(),

                        "Transferencia desde " + (fromLocation != null ? fromLocation.getName() : "")
                );
            } else {
                // Si no hay costo FIFO, solo incrementar sin crear lote FIFO
                productInventoryService.incrementInventory(
                    transfer.getProductId(), 
                    transfer.getToLocationId(), 
                    colorId, // Pasar colorId de la transferencia
                    transfer.getQuantity());
            }

            // Obtener inventarios después de modificar (considerando colorId)
            ProductInventoryLocation originAfter = productInventoryLocationRepository
                    .findByProductIdAndLocationIdAndColorId(
                        transfer.getProductId(), 
                        transfer.getFromLocationId(),
                        colorId) // Puede ser null, pero se busca específicamente con null
                    .orElse(null);
            ProductInventoryLocation destAfter = productInventoryLocationRepository
                    .findByProductIdAndLocationIdAndColorId(
                        transfer.getProductId(), 
                        transfer.getToLocationId(),
                        colorId) // Puede ser null, pero se busca específicamente con null
                    .orElse(null);

            BigDecimal originAfterQty = originAfter != null ? originAfter.getQuantity() : BigDecimal.ZERO;
            BigDecimal destAfterQty = destAfter != null ? destAfter.getQuantity() : BigDecimal.ZERO;

            productInventoryService.recordProductMovementIfAbsent(
                    transfer.getProductId(),
                    transfer.getToLocationId(),
                    colorId,
                    "TRANSFER_IN",
                    transfer.getQuantity(),
                    destBefore,
                    destAfterQty,
                    null,
                    "TRANSFER",
                    transfer.getId(),
                    "TRF-" + transfer.getId(),
                    "Transferencia desde " + (fromLocation != null ? fromLocation.getName() : "")
            );

            if (kioskInventoryGuard.isKioskLocation(fromLocation) && kioskInventoryGuard.isKioskLocation(toLocation)) {
                kioscoInventoryService.registrarTrasladoDesdeIntegracion(
                        transfer.getFromLocationId(),
                        transfer.getToLocationId(),
                        transfer.getProductId(),
                        colorId,
                        transfer.getQuantity(),
                        transfer.getUpdatedBy()
                );
            }
        }

        // Marcar transferencia como completada
        Long currentUserId = securityUtil.getCurrentUserId();
        transfer.setStatus("COMPLETED");
        transfer.setUpdatedBy(currentUserId);
        inventoryTransferRepository.save(transfer);
    }

    /**
     * Obtiene todas las transferencias con filtros opcionales
     */
    public List<InventoryTransferResponse> getTransfers(
            String status, Long fromLocationId, Long toLocationId, Long materialId, Long productId) {
        
        List<InventoryTransfer> transfers;
        
        if (status != null) {
            transfers = inventoryTransferRepository.findByStatus(status);
        } else if (fromLocationId != null && toLocationId != null) {
            transfers = inventoryTransferRepository.findByFromLocationIdAndToLocationId(fromLocationId, toLocationId);
        } else if (fromLocationId != null) {
            transfers = inventoryTransferRepository.findByFromLocationId(fromLocationId);
        } else if (toLocationId != null) {
            transfers = inventoryTransferRepository.findByToLocationId(toLocationId);
        } else if (materialId != null) {
            transfers = inventoryTransferRepository.findByMaterialId(materialId);
        } else if (productId != null) {
            transfers = inventoryTransferRepository.findByProductId(productId);
        } else {
            transfers = inventoryTransferRepository.findAll();
        }

        return transfers.stream()
                .map(this::toInventoryTransferResponse)
                .collect(Collectors.toList());
    }

    /**
     * Obtiene una transferencia por ID
     */
    public InventoryTransferResponse getTransferById(Long id) throws ResourceNotFoundException {
        InventoryTransfer transfer = inventoryTransferRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Inventory Transfer", id));
        return toInventoryTransferResponse(transfer);
    }

    /**
     * Convierte entidad a DTO de respuesta
     */
    private InventoryTransferResponse toInventoryTransferResponse(InventoryTransfer entity) {
        LocationEntity fromLocation = locationRepository.findById(entity.getFromLocationId()).orElse(null);
        LocationEntity toLocation = locationRepository.findById(entity.getToLocationId()).orElse(null);
        MaterialEntity material = entity.getMaterialId() != null 
                ? materialRepository.findById(entity.getMaterialId()).orElse(null) 
                : null;
        ProductEntity product = entity.getProductId() != null 
                ? productRepository.findById(entity.getProductId()).orElse(null) 
                : null;
        ColorEntity color = entity.getColorId() != null
                ? colorRepository.findById(entity.getColorId()).orElse(null)
                : null;
        
        // Obtener nombres de usuarios
        UserEntity createdByUser = entity.getCreatedBy() != null 
                ? userRepository.findById(entity.getCreatedBy()).orElse(null) 
                : null;
        UserEntity updatedByUser = entity.getUpdatedBy() != null 
                ? userRepository.findById(entity.getUpdatedBy()).orElse(null) 
                : null;

        return InventoryTransferResponse.builder()
                .id(entity.getId())
                .fromLocationId(entity.getFromLocationId())
                .fromLocationCode(fromLocation != null ? fromLocation.getCode() : null)
                .fromLocationName(fromLocation != null ? fromLocation.getName() : null)
                .toLocationId(entity.getToLocationId())
                .toLocationCode(toLocation != null ? toLocation.getCode() : null)
                .toLocationName(toLocation != null ? toLocation.getName() : null)
                .materialId(entity.getMaterialId())
                .materialSku(material != null ? material.getSku() : null)
                .materialName(material != null ? material.getName() : null)
                .productId(entity.getProductId())
                .productCode(product != null ? product.getCode() : null)
                .productName(product != null ? product.getName() : null)
                .colorId(entity.getColorId())
                .colorName(color != null ? color.getName() : null)
                .quantity(entity.getQuantity())
                .reason(entity.getReason())
                .status(entity.getStatus())
                .transferDate(entity.getTransferDate())
                .completedAt(entity.getCompletedAt())
                .createdAt(entity.getCreatedAt())
                .createdBy(entity.getCreatedBy())
                .createdByName(createdByUser != null ? createdByUser.getUsername() : null)
                .updatedAt(entity.getUpdatedAt())
                .updatedBy(entity.getUpdatedBy())
                .updatedByName(updatedByUser != null ? updatedByUser.getUsername() : null)
                .build();
    }

    // ========== INVENTORY ADJUSTMENTS ==========

    /**
     * Crea un ajuste manual de inventario
     */
    public InventoryAdjustmentResponse createAdjustment(InventoryAdjustmentRequest request) 
            throws ResourceNotFoundException, BusinessException {
        
        // Validar que se especifique material o producto, pero no ambos
        if (request.getMaterialId() == null && request.getProductId() == null) {
            throw new BusinessException("Debe especificar un material o un producto");
        }
        if (request.getMaterialId() != null && request.getProductId() != null) {
            throw new BusinessException("Debe especificar solo un material o un producto, no ambos");
        }

        // Validar que el material o producto existe
        if (request.getMaterialId() != null && !materialRepository.existsById(request.getMaterialId())) {
            throw new ResourceNotFoundException("Material", request.getMaterialId());
        }
        if (request.getProductId() != null && !productRepository.existsById(request.getProductId())) {
            throw new ResourceNotFoundException("Product", request.getProductId());
        }

        // Validar ubicación solo para productos
        if (request.getProductId() != null) {
            if (request.getLocationId() == null) {
                throw new BusinessException("La ubicación es requerida para ajustes de productos");
            }
            if (!locationRepository.existsById(request.getLocationId())) {
                throw new ResourceNotFoundException("Location", request.getLocationId());
            }
            kioskInventoryGuard.assertSupervisorMayModifyKioskInventory(request.getLocationId());
        }

        boolean allowZeroDifference = Boolean.TRUE.equals(request.getAllowZeroDifference());
        if (request.getSystemStock().compareTo(request.getPhysicalStock()) == 0 && !allowZeroDifference) {
            throw new BusinessException("El stock del sistema y el stock físico son iguales. No se requiere ajuste.");
        }

        // Obtener usuario actual
        Long currentUserId = securityUtil.getCurrentUserId();

        // Calcular diferencia
        BigDecimal adjustmentQuantity = request.getPhysicalStock().subtract(request.getSystemStock());

        // Validar color solo para productos
        if (request.getProductId() != null && request.getColorId() != null) {
            if (!colorRepository.existsById(request.getColorId())) {
                throw new ResourceNotFoundException("Color", request.getColorId());
            }
        }

        String systemSizesJson = null;
        String physicalSizesJson = null;
        if (request.getProductId() != null) {
            ProductEntity prod = productRepository.findById(request.getProductId()).orElse(null);
            boolean hasSys = request.getSystemSizes() != null && !request.getSystemSizes().isEmpty();
            boolean hasPhy = request.getPhysicalSizes() != null && !request.getPhysicalSizes().isEmpty();
            if (CinchoProductUtils.isFossCinchoProduct(prod)) {
                if (!hasSys || !hasPhy) {
                    throw new BusinessException(
                            "Los productos cincho requieren desglose por talla: envie systemSizes y physicalSizes (use el ajuste por lote en el sistema si aplica).");
                }
                Map<String, BigDecimal> sysM = ProductInventorySizesJson.normalizeAdjustmentSizeMap(request.getSystemSizes());
                Map<String, BigDecimal> phyM = ProductInventorySizesJson.normalizeAdjustmentSizeMap(request.getPhysicalSizes());
                if (sysM.isEmpty() && phyM.isEmpty()) {
                    throw new BusinessException("Los mapas de tallas no pueden quedar vacios.");
                }
                java.util.TreeSet<String> union = new java.util.TreeSet<>();
                union.addAll(sysM.keySet());
                union.addAll(phyM.keySet());
                Map<String, BigDecimal> sysAligned = new java.util.LinkedHashMap<>();
                Map<String, BigDecimal> phyAligned = new java.util.LinkedHashMap<>();
                BigDecimal sumS = BigDecimal.ZERO;
                BigDecimal sumP = BigDecimal.ZERO;
                for (String k : union) {
                    BigDecimal s = sysM.getOrDefault(k, BigDecimal.ZERO);
                    BigDecimal p = phyM.getOrDefault(k, BigDecimal.ZERO);
                    sumS = sumS.add(s);
                    sumP = sumP.add(p);
                    sysAligned.put(k, s);
                    phyAligned.put(k, p);
                }
                if (!sumsMatchStock(sumS, request.getSystemStock())) {
                    throw new BusinessException("systemStock debe coincidir con la suma de systemSizes (verifique decimales).");
                }
                if (!sumsMatchStock(sumP, request.getPhysicalStock())) {
                    throw new BusinessException("physicalStock debe coincidir con la suma de physicalSizes (verifique decimales).");
                }
                systemSizesJson = ProductInventorySizesJson.serializeIncludingZeros(sysAligned);
                physicalSizesJson = ProductInventorySizesJson.serializeIncludingZeros(phyAligned);
            } else if (hasSys || hasPhy) {
                throw new BusinessException("systemSizes/physicalSizes solo aplican a productos cincho (codigo FOSS o nombre con cincho).");
            }
        }

        // Crear el ajuste
        InventoryAdjustment adjustment = InventoryAdjustment.builder()
                .locationId(request.getLocationId()) // null para materiales, requerido para productos
                .materialId(request.getMaterialId())
                .productId(request.getProductId())
                .colorId(request.getColorId()) // Opcional: solo para productos
                .systemSizesData(systemSizesJson)
                .physicalSizesData(physicalSizesJson)
                .systemStock(request.getSystemStock())
                .physicalStock(request.getPhysicalStock())
                .adjustmentQuantity(adjustmentQuantity)
                .reason(request.getReason())
                .createdBy(currentUserId)
                .updatedBy(currentUserId)
                .build();

        InventoryAdjustment saved = inventoryAdjustmentRepository.save(adjustment);

        // Ejecutar el ajuste (actualizar inventario)
        executeAdjustment(saved);

        return toInventoryAdjustmentResponse(saved);
    }

    /**
     * Ejecuta un ajuste (actualiza inventario)
     */
    private void executeAdjustment(InventoryAdjustment adjustment)
            throws ResourceNotFoundException, BusinessException {
        
        if (adjustment.getMaterialId() != null) {
            // Ajuste de material (sin ubicación - se almacena en MaterialInventory)
            // Obtener inventario actual del material (sin ubicación)
            MaterialInventory materialInventory = materialInventoryRepository
                    .findByMaterialId(adjustment.getMaterialId())
                    .orElse(null);

            BigDecimal quantityBefore = materialInventory != null ? materialInventory.getQuantity() : BigDecimal.ZERO;
            BigDecimal quantityAfter = adjustment.getPhysicalStock();

            if (materialInventory == null) {
                // Crear nuevo registro en MaterialInventory (sin ubicación)
                materialInventory = MaterialInventory.builder()
                        .materialId(adjustment.getMaterialId())
                        .quantity(adjustment.getPhysicalStock())
                        .build();
            } else {
                // Actualizar existente
                materialInventory.setQuantity(adjustment.getPhysicalStock());
            }

            MaterialInventory saved = materialInventoryRepository.save(materialInventory);

            // Actualizar quantity en MaterialEntity
            MaterialEntity material = materialRepository.findById(adjustment.getMaterialId()).orElse(null);
            if (material != null) {
                material.setQuantity(saved.getQuantity());
                materialRepository.save(material);
            }

            if (adjustment.getAdjustmentQuantity().compareTo(BigDecimal.ZERO) != 0) {
                String movementType = adjustment.getAdjustmentQuantity().compareTo(BigDecimal.ZERO) > 0
                        ? "ADJUSTMENT_IN"
                        : "ADJUSTMENT_OUT";

                recordMaterialMovement(
                        adjustment.getMaterialId(),
                        movementType,
                        adjustment.getAdjustmentQuantity(),
                        quantityBefore,
                        quantityAfter,
                        null,
                        "ADJUSTMENT",
                        adjustment.getId(),
                        "AJT-" + adjustment.getId(),
                        adjustment.getReason()
                );
            }
        } else {
            // Ajuste de producto
            ProductEntity productEntity = productRepository.findById(adjustment.getProductId()).orElse(null);
            boolean fossWithSizes = CinchoProductUtils.isFossCinchoProduct(productEntity)
                    && adjustment.getPhysicalSizesData() != null
                    && !adjustment.getPhysicalSizesData().isBlank();

            ProductInventoryLocation inventory;
            if (adjustment.getColorId() != null) {
                // Buscar por producto, ubicación y color
                inventory = productInventoryLocationRepository
                        .findByProductIdAndLocationIdAndColorId(
                                adjustment.getProductId(), 
                                adjustment.getLocationId(),
                                adjustment.getColorId())
                        .orElse(null);
            } else {
                // Buscar por producto y ubicación (sin color) - buscar específicamente con color_id = null
                inventory = productInventoryLocationRepository
                        .findByProductIdAndLocationIdAndColorId(
                                adjustment.getProductId(), 
                                adjustment.getLocationId(),
                                null) // Buscar específicamente con color_id = null
                        .orElse(null);
            }

            BigDecimal quantityBefore = inventory != null ? inventory.getQuantity() : BigDecimal.ZERO;
            BigDecimal quantityAfter = adjustment.getPhysicalStock();

            if (inventory == null) {
                ProductInventoryLocationRequest.ProductInventoryLocationRequestBuilder cr = ProductInventoryLocationRequest.builder()
                        .productId(adjustment.getProductId())
                        .locationId(adjustment.getLocationId())
                        .colorId(adjustment.getColorId())
                        .quantity(adjustment.getPhysicalStock());
                if (fossWithSizes) {
                    cr.sizes(ProductInventorySizesJson.parse(adjustment.getPhysicalSizesData()));
                }
                productInventoryService.createOrUpdateInventory(cr.build());
            } else {
                inventory.setQuantity(adjustment.getPhysicalStock());
                if (fossWithSizes) {
                    inventory.setSizesData(adjustment.getPhysicalSizesData());
                }
                productInventoryLocationRepository.save(inventory);
            }

            if (adjustment.getAdjustmentQuantity().compareTo(BigDecimal.ZERO) != 0) {
                String movementType = adjustment.getAdjustmentQuantity().compareTo(BigDecimal.ZERO) > 0
                        ? "ADJUSTMENT_IN"
                        : "ADJUSTMENT_OUT";

                productInventoryService.recordMovement(
                        adjustment.getProductId(),
                        adjustment.getLocationId(),
                        adjustment.getColorId(),
                        movementType,
                        adjustment.getAdjustmentQuantity(),
                        quantityBefore,
                        quantityAfter,
                        null,
                        "ADJUSTMENT",
                        adjustment.getId(),
                        "AJT-" + adjustment.getId(),
                        adjustment.getReason()
                );
            }
        }
    }

    /**
     * Obtiene todos los ajustes con filtros opcionales
     */
    public List<InventoryAdjustmentResponse> getAdjustments(
            Long materialId, Long productId, Long locationId, Long userId, 
            LocalDateTime startDate, LocalDateTime endDate) {
        
        List<InventoryAdjustment> adjustments;
        
        if (materialId != null && locationId != null) {
            adjustments = inventoryAdjustmentRepository.findByMaterialIdAndLocationId(materialId, locationId);
        } else if (productId != null && locationId != null) {
            adjustments = inventoryAdjustmentRepository.findByProductIdAndLocationId(productId, locationId);
        } else if (materialId != null) {
            adjustments = inventoryAdjustmentRepository.findByMaterialId(materialId);
        } else if (productId != null) {
            adjustments = inventoryAdjustmentRepository.findByProductId(productId);
        } else if (locationId != null) {
            adjustments = inventoryAdjustmentRepository.findByLocationId(locationId);
        } else if (userId != null) {
            adjustments = inventoryAdjustmentRepository.findByCreatedBy(userId);
        } else if (startDate != null && endDate != null) {
            adjustments = inventoryAdjustmentRepository.findByAdjustmentDateBetween(startDate, endDate);
        } else {
            adjustments = inventoryAdjustmentRepository.findAll();
        }

        return adjustments.stream()
                .map(this::toInventoryAdjustmentResponse)
                .collect(Collectors.toList());
    }

    /**
     * Obtiene un ajuste por ID
     */
    public InventoryAdjustmentResponse getAdjustmentById(Long id) throws ResourceNotFoundException {
        InventoryAdjustment adjustment = inventoryAdjustmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Inventory Adjustment", id));
        return toInventoryAdjustmentResponse(adjustment);
    }

    /**
     * Convierte entidad a DTO de respuesta
     */
    private Map<String, BigDecimal> adjustmentSizesOrNull(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        Map<String, BigDecimal> m = ProductInventorySizesJson.parse(json);
        return m.isEmpty() ? null : m;
    }

    private InventoryAdjustmentResponse toInventoryAdjustmentResponse(InventoryAdjustment entity) {
        LocationEntity location = entity.getLocationId() != null 
                ? locationRepository.findById(entity.getLocationId()).orElse(null) 
                : null;
        MaterialEntity material = entity.getMaterialId() != null 
                ? materialRepository.findById(entity.getMaterialId()).orElse(null) 
                : null;
        ProductEntity product = entity.getProductId() != null 
                ? productRepository.findById(entity.getProductId()).orElse(null) 
                : null;
        ColorEntity color = entity.getColorId() != null 
                ? colorRepository.findById(entity.getColorId()).orElse(null) 
                : null;
        
        // Obtener nombres de usuarios
        UserEntity createdByUser = entity.getCreatedBy() != null 
                ? userRepository.findById(entity.getCreatedBy()).orElse(null) 
                : null;
        UserEntity updatedByUser = entity.getUpdatedBy() != null 
                ? userRepository.findById(entity.getUpdatedBy()).orElse(null) 
                : null;

        return InventoryAdjustmentResponse.builder()
                .id(entity.getId())
                .locationId(entity.getLocationId())
                .locationCode(location != null ? location.getCode() : null)
                .locationName(location != null ? location.getName() : null)
                .materialId(entity.getMaterialId())
                .materialSku(material != null ? material.getSku() : null)
                .materialName(material != null ? material.getName() : null)
                .productId(entity.getProductId())
                .productCode(product != null ? product.getCode() : null)
                .productName(product != null ? product.getName() : null)
                .colorId(entity.getColorId())
                .colorName(color != null ? color.getName() : null)
                .systemSizes(adjustmentSizesOrNull(entity.getSystemSizesData()))
                .physicalSizes(adjustmentSizesOrNull(entity.getPhysicalSizesData()))
                .systemStock(entity.getSystemStock())
                .physicalStock(entity.getPhysicalStock())
                .adjustmentQuantity(entity.getAdjustmentQuantity())
                .reason(entity.getReason())
                .adjustmentDate(entity.getAdjustmentDate())
                .createdAt(entity.getCreatedAt())
                .createdBy(entity.getCreatedBy())
                .createdByName(createdByUser != null ? createdByUser.getUsername() : null)
                .updatedAt(entity.getUpdatedAt())
                .updatedBy(entity.getUpdatedBy())
                .updatedByName(updatedByUser != null ? updatedByUser.getUsername() : null)
                .build();
    }
}

