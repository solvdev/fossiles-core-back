package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.ProductInventoryLocationRequest;
import com.fossiles.fossilescorebackend.application.dto.request.ProductInventoryUpdateRequest;
import com.fossiles.fossilescorebackend.application.dto.response.CriticalProductInventoryResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ProductInventoryKardexResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ProductInventoryLocationResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class ProductInventoryService {

    private final ProductInventoryLocationRepository productInventoryLocationRepository;
    private final ProductInventoryKardexRepository productInventoryKardexRepository;
    private final ProductRepository productRepository;
    private final LocationRepository locationRepository;
    private final InventoryLocationTypeRepository inventoryLocationTypeRepository;
    private final ProductFifoBatchRepository productFifoBatchRepository;
    private final ColorRepository colorRepository;
    private final com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil securityUtil;

    // ========== PRODUCT INVENTORY LOCATION ==========

    /**
     * Obtiene el inventario de un producto en una ubicación específica
     */
    public ProductInventoryLocationResponse getInventoryByProductAndLocation(Long productId, Long locationId) 
            throws ResourceNotFoundException {
        // Para compatibilidad: cuando no se especifica color, buscar específicamente color_id = NULL
        return getInventoryByProductAndLocationAndColor(productId, locationId, null);
    }

    /**
     * Obtiene el inventario de un producto en una ubicación específica con color
     */
    public ProductInventoryLocationResponse getInventoryByProductAndLocationAndColor(
            Long productId, Long locationId, Long colorId) 
            throws ResourceNotFoundException {
        ProductInventoryLocation entity = productInventoryLocationRepository
                .findByProductIdAndLocationIdAndColorId(productId, locationId, colorId)
                .orElse(null);
        
        // Si no existe, retornar con cantidad 0
        if (entity == null) {
            ProductEntity product = productRepository.findById(productId).orElse(null);
            LocationEntity location = locationRepository.findById(locationId).orElse(null);
            return createEmptyProductInventoryResponse(productId, locationId, product, location);
        }
        
        return toProductInventoryLocationResponse(entity);
    }

    /**
     * Obtiene todo el inventario de un producto (suma de todas las ubicaciones)
     */
    public List<ProductInventoryLocationResponse> getInventoryByProduct(Long productId) {
        List<ProductInventoryLocation> entities = productInventoryLocationRepository.findByProductId(productId);
        return entities.stream()
                .map(this::toProductInventoryLocationResponse)
                .collect(Collectors.toList());
    }

    /**
     * Obtiene todo el inventario de una ubicación
     * Incluye todos los productos, incluso si no tienen stock (muestra 0)
     */
    public List<ProductInventoryLocationResponse> getInventoryByLocation(Long locationId) {
        // Obtener inventario existente para esta ubicación
        List<ProductInventoryLocation> existingInventory = productInventoryLocationRepository.findByLocationId(locationId);
        
        // Si NO hay registros, devolver lista vacía (hasta que se presione "Actualizar Inventario")
        if (existingInventory.isEmpty()) {
            return new java.util.ArrayList<>();
        }
        
        // Obtener todos los productos
        List<ProductEntity> allProducts = productRepository.findAll();
        
        // Para soportar variantes de color: puede haber múltiples registros por productId en la misma ubicación.
        // Esta vista devuelve 1 fila por producto (sumando todas sus variantes de color).
        java.util.Map<Long, BigDecimal> quantityByProductId = existingInventory.stream()
                .collect(Collectors.groupingBy(
                        ProductInventoryLocation::getProductId,
                        Collectors.mapping(
                                pil -> pil.getQuantity() != null ? pil.getQuantity() : BigDecimal.ZERO,
                                Collectors.reducing(BigDecimal.ZERO, BigDecimal::add)
                        )
                ));
        
        // Obtener la ubicación
        LocationEntity location = locationRepository.findById(locationId).orElse(null);
        
        // Crear respuesta incluyendo TODOS los productos
        // Si no tiene registro en inventario, mostrar con cantidad 0 (NO crear registro automáticamente)
        return allProducts.stream()
                .map(product -> {
                    BigDecimal qty = quantityByProductId.get(product.getId());
                    if (qty != null) {
                        // Producto tiene inventario (posiblemente por variantes). Devolver agregado.
                        ProductInventoryLocationResponse resp = createEmptyProductInventoryResponse(product.getId(), locationId, product, location);
                        resp.setQuantity(qty);
                        return resp;
                    } else {
                        // Producto no tiene inventario, mostrar con cantidad 0 (sin crear registro)
                        return createEmptyProductInventoryResponse(product.getId(), locationId, product, location);
                    }
                })
                .collect(Collectors.toList());
    }

    /**
     * Obtiene el inventario de una ubicación SIN AGRUPAR (incluye variantes por color).
     * Devuelve 1 fila por (productId, locationId, colorId).
     */
    public List<ProductInventoryLocationResponse> getInventoryByLocationVariants(Long locationId) {
        List<ProductInventoryLocation> existingInventory = productInventoryLocationRepository.findByLocationId(locationId);
        return existingInventory.stream()
                .map(this::toProductInventoryLocationResponse)
                .collect(Collectors.toList());
    }

    /**
     * Obtiene todo el inventario (todas las ubicaciones)
     * Incluye todos los productos en todas las ubicaciones, incluso si no tienen stock (muestra 0)
     */
    public List<ProductInventoryLocationResponse> getAllInventory() {
        // Obtener todos los productos
        List<ProductEntity> allProducts = productRepository.findAll();
        
        // Obtener todas las ubicaciones
        List<LocationEntity> allLocations = locationRepository.findAll();
        
        // Obtener todo el inventario existente
        List<ProductInventoryLocation> existingInventory = productInventoryLocationRepository.findAll();
        
        // Para soportar variantes de color: puede haber múltiples registros por (productId, locationId).
        // Esta vista devuelve 1 fila por (productId, locationId) (sumando todas sus variantes).
        java.util.Map<String, BigDecimal> quantityByProductLocation = existingInventory.stream()
                .collect(Collectors.groupingBy(
                        pil -> pil.getProductId() + "_" + pil.getLocationId(),
                        Collectors.mapping(
                                pil -> pil.getQuantity() != null ? pil.getQuantity() : BigDecimal.ZERO,
                                Collectors.reducing(BigDecimal.ZERO, BigDecimal::add)
                        )
                ));
        
        // Crear respuesta: todos los productos en todas las ubicaciones
        List<ProductInventoryLocationResponse> result = new java.util.ArrayList<>();
        
        for (ProductEntity product : allProducts) {
            for (LocationEntity location : allLocations) {
                String key = product.getId() + "_" + location.getId();
                BigDecimal qty = quantityByProductLocation.get(key);
                ProductInventoryLocationResponse resp = createEmptyProductInventoryResponse(product.getId(), location.getId(), product, location);
                if (qty != null) {
                    resp.setQuantity(qty);
                }
                result.add(resp);
            }
        }
        
        return result;
    }

    /**
     * Crea o actualiza el inventario de un producto en una ubicación
     */
    public ProductInventoryLocationResponse createOrUpdateInventory(ProductInventoryLocationRequest request) 
            throws ResourceNotFoundException {
        // Validar que el producto existe
        if (!productRepository.existsById(request.getProductId())) {
            throw new ResourceNotFoundException("Product", request.getProductId());
        }

        // Validar que la ubicación existe
        if (!locationRepository.existsById(request.getLocationId())) {
            throw new ResourceNotFoundException("Location", request.getLocationId());
        }

        // Buscar si ya existe - SIEMPRE considerar color_id (incluso si es null)
        // Esto es importante porque la restricción única incluye color_id
        Optional<ProductInventoryLocation> existing = productInventoryLocationRepository
                .findByProductIdAndLocationIdAndColorId(
                        request.getProductId(), 
                        request.getLocationId(),
                        request.getColorId()); // Puede ser null, pero se busca específicamente con null

        ProductInventoryLocation entity;
        if (existing.isPresent()) {
            // Actualizar
            entity = existing.get();
            entity.setQuantity(request.getQuantity());
            if (request.getColorId() != null) {
                entity.setColorId(request.getColorId());
            }
        } else {
            // Crear nuevo
            entity = ProductInventoryLocation.builder()
                    .productId(request.getProductId())
                    .locationId(request.getLocationId())
                    .colorId(request.getColorId())
                    .quantity(request.getQuantity())
                    .build();
        }

        ProductInventoryLocation saved = productInventoryLocationRepository.save(entity);
        return toProductInventoryLocationResponse(saved);
    }

    /**
     * Actualiza el inventario (incrementa o decrementa)
     * SIEMPRE considera colorId para soportar variantes de color
     */
    public ProductInventoryLocationResponse updateInventory(ProductInventoryUpdateRequest request) 
            throws ResourceNotFoundException {
        // SIEMPRE usar el método que considera colorId (incluso si es null)
        ProductInventoryLocation entity = productInventoryLocationRepository
                .findByProductIdAndLocationIdAndColorId(
                    request.getProductId(), 
                    request.getLocationId(),
                    request.getColorId()) // Puede ser null, pero se busca específicamente con null
                .orElseThrow(() -> new ResourceNotFoundException(
                    "Product Inventory Location", 
                    "Product: " + request.getProductId() + 
                    ", Location: " + request.getLocationId() + 
                    (request.getColorId() != null ? ", Color: " + request.getColorId() : ", Color: NULL")));

        entity.setQuantity(request.getQuantity());
        ProductInventoryLocation saved = productInventoryLocationRepository.save(entity);

        return toProductInventoryLocationResponse(saved);
    }

    /**
     * Incrementa el inventario de un producto en una ubicación
     */
    public ProductInventoryLocationResponse incrementInventory(Long productId, Long locationId, BigDecimal quantity) 
            throws ResourceNotFoundException {
        return incrementInventory(productId, locationId, null, quantity, null, null, null, null, null);
    }

    /**
     * Incrementa el inventario de un producto en una ubicación considerando variantes de color
     */
    public ProductInventoryLocationResponse incrementInventory(Long productId, Long locationId, Long colorId, BigDecimal quantity)
            throws ResourceNotFoundException {
        return incrementInventory(productId, locationId, colorId, quantity, null, null, null, null, null);
    }

    /**
     * Incrementa el inventario de un producto en una ubicación con FIFO
     */
    public ProductInventoryLocationResponse incrementInventory(
            Long productId, 
            Long locationId, 
            Long colorId,
            BigDecimal quantity,
            BigDecimal unitCost,
            String referenceType,
            Long referenceId,
            String referenceNumber,
            String description) 
            throws ResourceNotFoundException {
        // SIEMPRE buscar por (productId, locationId, colorId) incluso si colorId es null
        Optional<ProductInventoryLocation> existing =
                productInventoryLocationRepository.findByProductIdAndLocationIdAndColorId(productId, locationId, colorId);

        ProductInventoryLocation entity;
        if (existing.isPresent()) {
            entity = existing.get();
            entity.setQuantity(entity.getQuantity().add(quantity));
        } else {
            entity = ProductInventoryLocation.builder()
                    .productId(productId)
                    .locationId(locationId)
                    .colorId(colorId)
                    .quantity(quantity)
                    .build();
        }

        ProductInventoryLocation saved = productInventoryLocationRepository.save(entity);
        
        // Crear lote FIFO para esta entrada
        if (unitCost != null && quantity.compareTo(BigDecimal.ZERO) > 0) {
            Long currentUserId = securityUtil != null ? securityUtil.getCurrentUserId() : null;
            ProductFifoBatch fifoBatch = ProductFifoBatch.builder()
                    .productId(productId)
                    .locationId(locationId)
                    .colorId(colorId)
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
            productFifoBatchRepository.save(fifoBatch);
        }
        
        return toProductInventoryLocationResponse(saved);
    }

    /**
     * Decrementa el inventario de un producto en una ubicación
     */
    public ProductInventoryLocationResponse decrementInventory(Long productId, Long locationId, BigDecimal quantity) 
            throws ResourceNotFoundException, BusinessException {
        return decrementInventory(productId, locationId, null, quantity, null, null, null, null);
    }

    /**
     * Decrementa el inventario de un producto en una ubicación con FIFO
     */
    public ProductInventoryLocationResponse decrementInventory(
            Long productId, 
            Long locationId, 
            Long colorId,
            BigDecimal quantity,
            String referenceType,
            Long referenceId,
            String referenceNumber,
            String description) 
            throws ResourceNotFoundException, BusinessException {
        // SIEMPRE usar el método que considera colorId (incluso si es null)
        // Esto evita errores cuando hay múltiples registros con diferentes colores
        ProductInventoryLocation entity = productInventoryLocationRepository
                .findByProductIdAndLocationIdAndColorId(productId, locationId, colorId)
                .orElseThrow(() -> new ResourceNotFoundException(
                    "Product Inventory Location", 
                    "Product: " + productId + 
                    ", Location: " + locationId + 
                    (colorId != null ? ", Color: " + colorId : ", Color: NULL")));

        BigDecimal newQuantity = entity.getQuantity().subtract(quantity);
        if (newQuantity.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("No hay suficiente inventario. Disponible: " + entity.getQuantity() + ", Solicitado: " + quantity);
        }

        entity.setQuantity(newQuantity);
        ProductInventoryLocation saved = productInventoryLocationRepository.save(entity);

        // Consumir lotes FIFO (más antiguos primero) y calcular costo promedio
        BigDecimal remainingQuantity = quantity;
        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal totalQuantityConsumed = BigDecimal.ZERO;
        
        List<ProductFifoBatch> availableBatches = productFifoBatchRepository
                .findAvailableBatchesByProductAndLocationAndColor(productId, locationId, colorId);
        
        LocalDateTime oldestBatchDate = null;
        LocalDateTime newestBatchDate = null;
        
        for (ProductFifoBatch batch : availableBatches) {
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
            
            // Reducir cantidad disponible del lote
            batch.setQuantityAvailable(batch.getQuantityAvailable().subtract(quantityToConsume));
            productFifoBatchRepository.save(batch);
            
            remainingQuantity = remainingQuantity.subtract(quantityToConsume);
        }
        
        // Si no hay suficiente inventario en lotes FIFO, permitir la operación pero sin consumir lotes
        // Esto puede pasar en transferencias o ajustes donde el inventario existe pero no hay lotes FIFO registrados
        // En este caso, el costo se calculará como null y se registrará en el kardex sin costo FIFO
        if (remainingQuantity.compareTo(BigDecimal.ZERO) > 0) {
            // No lanzar error, solo registrar que no se pudo consumir completamente de lotes FIFO
            // El inventario se decrementa de todas formas
        }

        return toProductInventoryLocationResponse(saved);
    }

    // ========== KARDEX ==========

    /**
     * Registra un movimiento en el kardex de productos
     */
    public ProductInventoryKardexResponse recordMovement(
            Long productId,
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
        return recordMovement(productId, locationId, null, movementType, quantity, quantityBefore, quantityAfter, 
                unitCost, referenceType, referenceId, referenceNumber, description);
    }

    /**
     * Registra un movimiento en el kardex de productos (con color)
     */
    public ProductInventoryKardexResponse recordMovement(
            Long productId,
            Long locationId,
            Long colorId,
            String movementType,
            BigDecimal quantity,
            BigDecimal quantityBefore,
            BigDecimal quantityAfter,
            BigDecimal unitCost,
            String referenceType,
            Long referenceId,
            String referenceNumber,
            String description) throws ResourceNotFoundException {

        // Validar que el producto existe
        if (!productRepository.existsById(productId)) {
            throw new ResourceNotFoundException("Product", productId);
        }

        // Validar que la ubicación existe
        if (!locationRepository.existsById(locationId)) {
            throw new ResourceNotFoundException("Location", locationId);
        }

        // Si es una salida y no se proporciona unitCost, calcular usando FIFO
        // NOTA: Este método solo calcula el costo FIFO basado en lotes disponibles
        // Los lotes deben ser consumidos previamente por decrementInventory
        BigDecimal finalUnitCost = unitCost;
        String fifoDescription = description != null ? description : "";
        if (unitCost == null && quantity != null && quantity.compareTo(BigDecimal.ZERO) < 0) {
            // Es una salida, calcular costo FIFO basado en lotes disponibles
            // (Los lotes ya deberían haber sido consumidos por decrementInventory)
            BigDecimal remainingQuantity = quantity.abs();
            BigDecimal totalCost = BigDecimal.ZERO;
            BigDecimal totalQuantityConsumed = BigDecimal.ZERO;
            
            // Obtener lotes disponibles para calcular el costo promedio
            // Nota: Estos lotes pueden ya haber sido parcialmente consumidos
            List<ProductFifoBatch> availableBatches = productFifoBatchRepository
                    .findAvailableBatchesByProductAndLocationAndColor(productId, locationId, colorId);
            
            // Si no hay lotes disponibles, buscar todos los lotes (incluyendo agotados)
            // para calcular el costo basado en los lotes más antiguos que existían
            if (availableBatches.isEmpty()) {
                availableBatches = productFifoBatchRepository
                        .findByProductIdAndLocationIdAndColorIdOrderByEntryDateAscIdAsc(productId, locationId, colorId);
            }
            
            LocalDateTime oldestBatchDate = null;
            LocalDateTime newestBatchDate = null;
            
            for (ProductFifoBatch batch : availableBatches) {
                if (remainingQuantity.compareTo(BigDecimal.ZERO) <= 0) {
                    break;
                }
                
                BigDecimal quantityToConsume = remainingQuantity.min(
                    batch.getQuantityAvailable().max(BigDecimal.ZERO));
                if (quantityToConsume.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                
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
                
                remainingQuantity = remainingQuantity.subtract(quantityToConsume);
            }
            
            if (totalQuantityConsumed.compareTo(BigDecimal.ZERO) > 0) {
                finalUnitCost = totalCost.divide(totalQuantityConsumed, 2, java.math.RoundingMode.HALF_UP);
                
                // Agregar información FIFO a la descripción
                if (oldestBatchDate != null) {
                    String fifoInfo = String.format(" [FIFO: Lotes desde %s hasta %s]",
                        oldestBatchDate.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")),
                        newestBatchDate.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")));
                    fifoDescription = fifoDescription + (fifoDescription.isEmpty() ? "" : " ") + fifoInfo;
                }
            }
        }

        BigDecimal totalCost = null;
        if (finalUnitCost != null && quantity != null) {
            totalCost = finalUnitCost.multiply(quantity.abs());
        }

        ProductInventoryKardex entity = ProductInventoryKardex.builder()
                .productId(productId)
                .locationId(locationId)
                .movementType(movementType)
                .quantity(quantity)
                .quantityBefore(quantityBefore)
                .quantityAfter(quantityAfter)
                .unitCost(finalUnitCost)
                .totalCost(totalCost)
                .referenceType(referenceType)
                .referenceId(referenceId)
                .referenceNumber(referenceNumber)
                .description(fifoDescription)
                .movementDate(LocalDateTime.now())
                .build();

        ProductInventoryKardex saved = productInventoryKardexRepository.save(entity);
        return toProductInventoryKardexResponse(saved);
    }

    /**
     * Obtiene el kardex de un producto
     */
    public List<ProductInventoryKardexResponse> getKardexByProduct(Long productId) {
        return productInventoryKardexRepository.findByProductId(productId).stream()
                .map(this::toProductInventoryKardexResponse)
                .collect(Collectors.toList());
    }

    /**
     * Obtiene el kardex de una ubicación
     */
    public List<ProductInventoryKardexResponse> getKardexByLocation(Long locationId) {
        return productInventoryKardexRepository.findByLocationId(locationId).stream()
                .map(this::toProductInventoryKardexResponse)
                .collect(Collectors.toList());
    }

    /**
     * Obtiene el kardex de un producto en una ubicación específica
     */
    public List<ProductInventoryKardexResponse> getKardexByProductAndLocation(Long productId, Long locationId) {
        return productInventoryKardexRepository.findByProductIdAndLocationId(productId, locationId).stream()
                .map(this::toProductInventoryKardexResponse)
                .collect(Collectors.toList());
    }

    /**
     * Obtiene el kardex por tipo de movimiento
     */
    public List<ProductInventoryKardexResponse> getKardexByMovementType(String movementType) {
        return productInventoryKardexRepository.findByMovementType(movementType).stream()
                .map(this::toProductInventoryKardexResponse)
                .collect(Collectors.toList());
    }

    /**
     * Obtiene el kardex por referencia (ej: orden de producción, orden de venta)
     */
    public List<ProductInventoryKardexResponse> getKardexByReference(String referenceType, Long referenceId) {
        return productInventoryKardexRepository.findByReferenceTypeAndReferenceId(referenceType, referenceId).stream()
                .map(this::toProductInventoryKardexResponse)
                .collect(Collectors.toList());
    }

    // ========== CRITICAL INVENTORY ==========

    /**
     * Obtiene el inventario crítico (productos con stock bajo o sin stock)
     */
    public List<CriticalProductInventoryResponse> getCriticalInventory(Long locationId) {
        List<ProductInventoryLocation> inventoryList;
        
        if (locationId != null) {
            inventoryList = productInventoryLocationRepository.findByLocationId(locationId);
        } else {
            inventoryList = productInventoryLocationRepository.findAll();
        }

        return inventoryList.stream()
                .map(entity -> {
                    ProductEntity product = productRepository.findById(entity.getProductId()).orElse(null);
                    if (product == null) {
                        return null;
                    }

                    BigDecimal currentStock = entity.getQuantity();

                    // Verificar si es crítico (sin stock o stock muy bajo)
                    boolean isCritical = false;
                    String priority = "OK";
                    String reason = null;
                    BigDecimal deficit = BigDecimal.ZERO;

                    if (currentStock.compareTo(BigDecimal.ZERO) == 0) {
                        isCritical = true;
                        priority = "CRITICAL";
                        reason = "OUT_OF_STOCK";
                        deficit = BigDecimal.ONE; // Al menos 1 para poder vender
                    } else if (currentStock.compareTo(BigDecimal.valueOf(5)) < 0) {
                        // Stock muy bajo (menos de 5 unidades)
                        isCritical = true;
                        priority = "WARNING";
                        reason = "LOW_STOCK";
                        deficit = BigDecimal.valueOf(5).subtract(currentStock);
                    }

                    if (!isCritical) {
                        return null;
                    }

                    LocationEntity location = locationRepository.findById(entity.getLocationId()).orElse(null);

                    return CriticalProductInventoryResponse.builder()
                            .productId(entity.getProductId())
                            .productCode(product.getCode())
                            .productName(product.getName())
                            .locationId(entity.getLocationId())
                            .locationCode(location != null ? location.getCode() : null)
                            .locationName(location != null ? location.getName() : null)
                            .currentStock(currentStock)
                            .deficit(deficit)
                            .priority(priority)
                            .reason(reason)
                            .build();
                })
                .filter(item -> item != null)
                .collect(Collectors.toList());
    }

    /**
     * Obtiene el inventario agregado de múltiples ubicaciones (suma de todas)
     * Útil para "Todos los Kioskos" o inventarios generales
     */
    public List<ProductInventoryLocationResponse> getAggregatedInventoryByCategory(String category) {
        String categoryUpper = category.toUpperCase();
        List<LocationEntity> categoryLocations;
        
        // Para KIOSKO, usar TODAS las ubicaciones de locations (kioskos físicos)
        if ("KIOSKO".equals(categoryUpper)) {
            categoryLocations = locationRepository.findAll();
        } else {
            // Para BODEGA_PT, VENDEDOR, ONLINE: usar inventory_location_type
            LocationEntity location = getOrCreateInventoryLocation(categoryUpper);
            if (location == null) {
                categoryLocations = new java.util.ArrayList<>();
            } else {
                categoryLocations = java.util.Collections.singletonList(location);
            }
        }

        if (categoryLocations.isEmpty()) {
            // Si no hay ubicaciones, devolver lista vacía
            return new java.util.ArrayList<>();
        }
        
        // Obtener inventario de todas las ubicaciones de esta categoría
        List<Long> locationIds = categoryLocations.stream()
                .map(LocationEntity::getId)
                .collect(Collectors.toList());
        
        List<ProductInventoryLocation> allInventory = productInventoryLocationRepository.findAll().stream()
                .filter(inv -> locationIds.contains(inv.getLocationId()))
                .collect(Collectors.toList());
        
        // Si NO hay registros, devolver lista vacía (hasta que se presione "Actualizar Inventario")
        if (allInventory.isEmpty()) {
            return new java.util.ArrayList<>();
        }
        
        // Obtener TODOS los productos (base de datos de productos)
        List<ProductEntity> allProducts = productRepository.findAll();
        
        // Agrupar por producto y sumar cantidades (solo productos que tienen inventario)
        java.util.Map<Long, BigDecimal> aggregatedQuantities = allInventory.stream()
                .collect(Collectors.groupingBy(
                    ProductInventoryLocation::getProductId,
                    Collectors.reducing(
                        BigDecimal.ZERO,
                        ProductInventoryLocation::getQuantity,
                        BigDecimal::add
                    )
                ));
        
        // Usar la primera ubicación como referencia para nombre (o crear una genérica)
        String locationName = categoryLocations.size() == 1 
            ? categoryLocations.get(0).getName()
            : getCategoryDisplayName(category);
        String locationCode = categoryLocations.size() == 1 
            ? categoryLocations.get(0).getCode()
            : category;
        
        // Crear respuesta con TODOS los productos (incluso sin inventario, mostrar cantidad 0)
        // NO crear registros automáticamente, solo mostrar
        return allProducts.stream()
                .map(product -> {
                    BigDecimal totalQuantity = aggregatedQuantities.getOrDefault(product.getId(), BigDecimal.ZERO);
                    
                    return ProductInventoryLocationResponse.builder()
                            .id(null) // No tiene ID único porque es agregado
                            .productId(product.getId())
                            .productCode(product.getCode())
                            .productName(product.getName())
                            .locationId(null) // No tiene locationId único porque es agregado
                            .locationCode(locationCode)
                            .locationName(locationName)
                            .quantity(totalQuantity) // Si no tiene inventario, será 0
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
    public List<ProductInventoryLocationResponse> getInventoryByCategory(String category) {
        String categoryUpper = category.toUpperCase();
        
        // Para KIOSKO, usar todas las ubicaciones de locations
        if ("KIOSKO".equals(categoryUpper)) {
            // Para kioskos, devolver todas las ubicaciones (no filtramos por categoria porque A, B, C son categorías de kioskos)
            List<LocationEntity> allKiosks = locationRepository.findAll();
            if (allKiosks.isEmpty()) {
                return new java.util.ArrayList<>();
            }
            // Devolver inventario de la primera ubicación
            return getInventoryByLocation(allKiosks.get(0).getId());
        }
        
        // Para BODEGA_PT, VENDEDOR, ONLINE: usar inventory_location_type
        LocationEntity location = getOrCreateInventoryLocation(categoryUpper);
        
        if (location == null) {
            // Si no existe el tipo, devolver lista vacía
            return new java.util.ArrayList<>();
        }

        // Verificar si hay registros en product_inventory_location para esta ubicación
        List<ProductInventoryLocation> existingInventory = productInventoryLocationRepository.findByLocationId(location.getId());
        
        // Si NO hay registros, devolver lista vacía (hasta que se presione "Actualizar Inventario")
        if (existingInventory.isEmpty()) {
            return new java.util.ArrayList<>();
        }

        // Si hay registros, mostrar TODOS los productos (los que tienen inventario con su cantidad, los que no con 0)
        return getInventoryByLocation(location.getId());
    }

    /**
     * Obtiene o crea la ubicación en locations basándose en inventory_location_type
     * Para BODEGA_PT, VENDEDOR, ONLINE
     */
    private LocationEntity getOrCreateInventoryLocation(String categoryCode) {
        Optional<com.fossiles.fossilescorebackend.infrastructure.persistence.entity.InventoryLocationTypeEntity> locationType = inventoryLocationTypeRepository.findByCodeAndIsActiveTrue(categoryCode.toUpperCase());
        
        if (locationType.isEmpty()) {
            return null;
        }
        
        // Buscar si existe en locations
        Optional<LocationEntity> existing = locationRepository.findAll().stream()
                .filter(loc -> locationType.get().getCode().equals(loc.getCode()))
                .findFirst();
        
        if (existing.isPresent()) {
            return existing.get();
        }
        
        // Crear si no existe
        LocationEntity newLocation = LocationEntity.builder()
                .code(locationType.get().getCode())
                .name(locationType.get().getName())
                .categoria(categoryCode.toUpperCase())
                .build();
        return locationRepository.save(newLocation);
    }

    private String getCategoryDisplayName(String category) {
        switch (category) {
            case "BODEGA_PT": return "Bodega Producto Terminado";
            case "VENDEDOR": return "Vendedor";
            case "ONLINE": return "Online";
            case "KIOSKO": return "Todos los Kioskos";
            default: return category;
        }
    }

    // ========== INITIALIZE INVENTORY ==========

    /**
     * Inicializa el inventario para todos los productos que no tienen registro
     * Crea registros con cantidad 0 en las ubicaciones de la categoría especificada
     * OPTIMIZADO: Usa batch inserts y consultas optimizadas, filtra por categoría
     * 
     * Lógica:
     * - Si locationId != null: Solo inserta en esa ubicación específica (más rápido)
     * - Si locationId == null y category == "KIOSKO": Inserta en TODAS las ubicaciones
     * - Si locationId == null y category == "BODEGA_PT"/"VENDEDOR"/"ONLINE": Inserta solo en ubicaciones de esa categoría
     * 
     * @param category Categoría de ubicación (KIOSKO, BODEGA_PT, VENDEDOR, ONLINE) o null para todas
     * @param locationId ID de ubicación específica (opcional, si se proporciona SOLO usa esa ubicación - más rápido)
     * @return Número de registros creados
     */
    public int initializeMissingInventory(String category, Long locationId) throws ResourceNotFoundException {
        // Obtener todos los productos
        List<ProductEntity> allProducts = productRepository.findAll();
        
        // Obtener ubicaciones según la categoría o locationId
        List<LocationEntity> targetLocations;
        if (locationId != null) {
            // Si se proporciona locationId específico, usar solo esa
            LocationEntity location = locationRepository.findById(locationId).orElse(null);
            if (location == null) {
                throw new ResourceNotFoundException("Location", locationId);
            }
            targetLocations = java.util.Collections.singletonList(location);
        } else if (category != null && !category.trim().isEmpty()) {
            String categoryUpper = category.toUpperCase().trim();
            
            // Para KIOSKO, cuando locationId es null (todos los kioskos), 
            // usar TODAS las ubicaciones (ya que no todas tienen categoria=KIOSKO en la BD)
            if ("KIOSKO".equals(categoryUpper)) {
                targetLocations = locationRepository.findAll();
            } else {
                // Para BODEGA_PT, VENDEDOR, ONLINE: usar inventory_location_type
                LocationEntity location = getOrCreateInventoryLocation(categoryUpper);
                if (location != null) {
                    targetLocations = java.util.Collections.singletonList(location);
                } else {
                    // Si no existe el tipo, retornar lista vacía
                    targetLocations = new java.util.ArrayList<>();
                }
            }
        } else {
            // Si no hay categoría ni locationId, usar todas (comportamiento por defecto)
            targetLocations = locationRepository.findAll();
        }
        
        if (targetLocations.isEmpty()) {
            return 0; // No hay ubicaciones para procesar
        }
        
        // OPTIMIZACIÓN: Obtener solo los registros existentes de las ubicaciones objetivo
        List<Long> targetLocationIds = targetLocations.stream()
                .map(LocationEntity::getId)
                .collect(Collectors.toList());
        
        List<ProductInventoryLocation> existingInventory = productInventoryLocationRepository.findAll().stream()
                .filter(inv -> targetLocationIds.contains(inv.getLocationId()))
                .collect(Collectors.toList());
        
        // Crear un Set con las combinaciones existentes para búsqueda O(1)
        java.util.Set<String> existingCombinations = existingInventory.stream()
                .map(inv -> inv.getProductId() + "_" + inv.getLocationId())
                .collect(java.util.stream.Collectors.toSet());
        
        // Preparar lista de nuevos registros para insertar en batch
        java.util.List<ProductInventoryLocation> newInventories = new java.util.ArrayList<>();
        
        for (ProductEntity product : allProducts) {
            for (LocationEntity location : targetLocations) {
                String combination = product.getId() + "_" + location.getId();
                
                // Verificar si ya existe (búsqueda en Set es O(1))
                if (!existingCombinations.contains(combination)) {
                    // Crear registro con cantidad 0
                    ProductInventoryLocation newInventory = ProductInventoryLocation.builder()
                            .productId(product.getId())
                            .productName(product.getName())
                            .locationId(location.getId())
                            .quantity(BigDecimal.ZERO)
                            .build();
                    
                    newInventories.add(newInventory);
                }
            }
        }
        
        // OPTIMIZACIÓN: Insertar todos los registros en batch (mucho más rápido)
        if (!newInventories.isEmpty()) {
            productInventoryLocationRepository.saveAll(newInventories);
        }
        
        return newInventories.size();
    }

    // ========== HELPER METHODS ==========

    private ProductInventoryLocationResponse toProductInventoryLocationResponse(ProductInventoryLocation entity) {
        ProductEntity product = productRepository.findById(entity.getProductId()).orElse(null);
        LocationEntity location = locationRepository.findById(entity.getLocationId()).orElse(null);
        ColorEntity color = entity.getColorId() != null ? colorRepository.findById(entity.getColorId()).orElse(null) : null;

        return ProductInventoryLocationResponse.builder()
                .id(entity.getId())
                .productId(entity.getProductId())
                .productCode(product != null ? product.getCode() : null)
                .productName(product != null ? product.getName() : null)
                .min(entity.getMin())
                .locationId(entity.getLocationId())
                .locationCode(location != null ? location.getCode() : null)
                .locationName(location != null ? location.getName() : null)
                .colorId(entity.getColorId())
                .colorName(color != null ? color.getName() : null)
                .quantity(entity.getQuantity())
                .createdAt(entity.getCreatedAt())
                .createdBy(entity.getCreatedBy())
                .updatedAt(entity.getUpdatedAt())
                .updatedBy(entity.getUpdatedBy())
                .build();
    }

    private ProductInventoryKardexResponse toProductInventoryKardexResponse(ProductInventoryKardex entity) {
        ProductEntity product = productRepository.findById(entity.getProductId()).orElse(null);
        LocationEntity location = locationRepository.findById(entity.getLocationId()).orElse(null);

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
        // Necesitamos obtener el colorId si está disponible en el inventario
        ProductInventoryLocation inventory = productInventoryLocationRepository
                .findByProductIdAndLocationIdAndColorId(entity.getProductId(), entity.getLocationId(), null)
                .orElse(null);
        Long colorId = inventory != null ? inventory.getColorId() : null;
        
        List<ProductFifoBatch> availableBatches = productFifoBatchRepository
                .findAvailableBatchesByProductAndLocationAndColor(entity.getProductId(), entity.getLocationId(), colorId);
        
        List<ProductInventoryKardexResponse.FifoBatchInfo> lotesFifo = availableBatches.stream()
                .map(batch -> ProductInventoryKardexResponse.FifoBatchInfo.builder()
                        .fechaEntrada(batch.getEntryDate())
                        .cantidad(batch.getQuantityAvailable())
                        .costoUnitario(batch.getUnitCost())
                        .total(batch.getQuantityAvailable().multiply(batch.getUnitCost()))
                        .build())
                .collect(Collectors.toList());

        return ProductInventoryKardexResponse.builder()
                .id(entity.getId())
                .productId(entity.getProductId())
                .productCode(product != null ? product.getCode() : null)
                .productName(product != null ? product.getName() : null)
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
                .cantidadEntrada(cantidadEntrada)
                .costoUnitarioEntrada(costoUnitarioEntrada)
                .totalEntrada(totalEntrada)
                .cantidadSalida(cantidadSalida)
                .costoUnitarioSalida(costoUnitarioSalida)
                .totalSalida(totalSalida)
                .lotesFifo(lotesFifo)
                .build();
    }

    /**
     * Crea una respuesta de inventario vacía (cantidad 0) para un producto que no tiene registro
     */
    private ProductInventoryLocationResponse createEmptyProductInventoryResponse(
            Long productId, 
            Long locationId, 
            ProductEntity product,
            LocationEntity location) {
        return ProductInventoryLocationResponse.builder()
                .id(null) // No tiene ID porque no existe registro
                .productId(productId)
                .productCode(product != null ? product.getCode() : null)
                .productName(product != null ? product.getName() : null)
                .locationId(locationId)
                .locationCode(location != null ? location.getCode() : null)
                .locationName(location != null ? location.getName() : null)
                .colorId(null)
                .colorName(null)
                .quantity(BigDecimal.ZERO) // Cantidad 0
                .createdAt(null)
                .createdBy(null)
                .updatedAt(null)
                .updatedBy(null)
                .build();
    }
}


