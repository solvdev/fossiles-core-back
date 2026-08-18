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
import com.fossiles.fossilescorebackend.infrastructure.util.CinchoProductUtils;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductInventorySizesJson;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(rollbackFor = Exception.class)
public class ProductInventoryService {

    public static final String MOVEMENT_SHIPMENT = "SHIPMENT";
    /** Salida de PT/Devoluciones al despachar una venta OPL a cliente. */
    public static final String MOVEMENT_ONLINE_SALE_DISPATCH = "ONLINE_SALE_DISPATCH";
    public static final String REF_ONLINE_SALE_PREPARE = "ONLINE_SALE_PREPARE";
    private static final String REVERSAL_SUFFIX = "_REVERSAL";
    /** Salidas de reenvío escritas por versiones anteriores; se netean junto a las de tipo SHIPMENT. */
    private static final String LEGACY_MOVEMENT_SHIPMENT_REDISPATCH = "SHIPMENT_REDISPATCH";

    private final ProductInventoryLocationRepository productInventoryLocationRepository;
    private final ProductInventoryKardexRepository productInventoryKardexRepository;
    private final ProductRepository productRepository;
    private final LocationRepository locationRepository;
    private final InventoryLocationTypeRepository inventoryLocationTypeRepository;
    private final ProductFifoBatchRepository productFifoBatchRepository;
    private final ColorRepository colorRepository;
    private final ProductCategoryRepository productCategoryRepository;
    private final com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil securityUtil;
    private final OnlineSaleReturnsWarehouseLocator returnsWarehouseLocator;
    private final KioskInventoryGuard kioskInventoryGuard;

    // ========== PRODUCT INVENTORY LOCATION ==========

    /**
     * Cantidad disponible en una ubicación para una línea de producto (FOSS con sizes_data por talla).
     */
    @Transactional(readOnly = true)
    public BigDecimal getAvailableQuantity(Long productId, Long locationId, Long colorId, String sizeLabel) {
        Optional<ProductInventoryLocation> opt =
                productInventoryLocationRepository.findByProductIdAndLocationIdAndColorId(productId, locationId, colorId);
        if (opt.isEmpty()) {
            return BigDecimal.ZERO;
        }
        ProductInventoryLocation pil = opt.get();
        ProductEntity p = productRepository.findById(productId).orElse(null);
        if (!CinchoProductUtils.isFossCinchoProduct(p)) {
            return pil.getQuantity() != null ? pil.getQuantity() : BigDecimal.ZERO;
        }
        Map<String, BigDecimal> bySize = ProductInventorySizesJson.parse(pil.getSizesData());
        if (bySize.isEmpty()) {
            return pil.getQuantity() != null ? pil.getQuantity() : BigDecimal.ZERO;
        }
        String sk = ProductInventorySizesJson.normalizeKey(sizeLabel);
        if (sk.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal q = bySize.get(sk);
        return q != null ? q : BigDecimal.ZERO;
    }

    /**
     * Bodegas de despacho PT: Devoluciones primero, luego Bodega PT (misma regla que venta en línea).
     */
    @Transactional(readOnly = true)
    public List<LocationEntity> getDispatchSourceWarehouses() throws BusinessException {
        List<LocationEntity> warehouses = new ArrayList<>();
        returnsWarehouseLocator.find().ifPresent(warehouses::add);
        LocationEntity bodegaPt = findBodegaPtLocation();
        if (bodegaPt == null) {
            throw new BusinessException("No existe una ubicacion configurada para BODEGA_PT.");
        }
        boolean ptAlreadyListed = warehouses.stream()
                .anyMatch(loc -> Objects.equals(loc.getId(), bodegaPt.getId()));
        if (!ptAlreadyListed) {
            warehouses.add(bodegaPt);
        }
        return warehouses;
    }

    @Transactional(readOnly = true)
    public BigDecimal getAvailableQuantityAcrossDispatchWarehouses(
            Long productId, Long colorId, String sizeLabel) throws BusinessException {
        BigDecimal total = BigDecimal.ZERO;
        for (LocationEntity loc : getDispatchSourceWarehouses()) {
            total = total.add(getAvailableQuantity(productId, loc.getId(), colorId, sizeLabel));
        }
        return total;
    }

    /**
     * Tipos de movimiento que participan del neto de una salida: la salida original, su reversión y
     * las variantes históricas de reenvío. Las salidas son negativas y las reversiones positivas,
     * de modo que el neto es lo que sigue realmente descargado del inventario.
     */
    private List<String> movementFamily(String movementType) {
        List<String> family = new ArrayList<>();
        family.add(movementType);
        family.add(reversalMovementType(movementType));
        if (MOVEMENT_SHIPMENT.equals(movementType)) {
            family.add(LEGACY_MOVEMENT_SHIPMENT_REDISPATCH);
            family.add(reversalMovementType(LEGACY_MOVEMENT_SHIPMENT_REDISPATCH));
        }
        return family;
    }

    public static String reversalMovementType(String movementType) {
        return movementType + REVERSAL_SUFFIX;
    }

    /**
     * Cantidad de una línea de documento que sigue descargada del inventario, ya descontadas las
     * reversiones. {@code locationId} null netea sobre todas las ubicaciones.
     */
    @Transactional(readOnly = true)
    public BigDecimal getNetConsumedForLine(
            String referenceType,
            Long referenceId,
            String movementType,
            Long productId,
            Long locationId,
            Long colorId,
            Long referenceLineId) {
        if (referenceType == null || referenceId == null || movementType == null || productId == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal signedSum = productInventoryKardexRepository.sumSignedQuantityForLine(
                referenceType, referenceId, movementFamily(movementType), productId,
                locationId, colorId, referenceLineId);
        if (signedSum == null) {
            return BigDecimal.ZERO;
        }
        // Las salidas se guardan en negativo: el neto consumido es su opuesto.
        return signedSum.negate().max(BigDecimal.ZERO);
    }

    /**
     * @deprecated usar {@link #getNetConsumedForLine} indicando la línea del documento; sin línea no
     *             se distinguen dos tallas del mismo producto+color dentro del mismo documento.
     */
    @Deprecated
    @Transactional(readOnly = true)
    public BigDecimal getConsumedQuantityForReference(
            String referenceType,
            Long referenceId,
            String movementType,
            Long productId,
            Long colorId) {
        if (referenceType == null || referenceId == null || movementType == null || productId == null) {
            return BigDecimal.ZERO;
        }
        List<String> family = movementFamily(movementType);
        BigDecimal signedSum = productInventoryKardexRepository
                .findByReferenceTypeAndReferenceId(referenceType, referenceId).stream()
                .filter(k -> family.contains(k.getMovementType()))
                .filter(k -> productId.equals(k.getProductId()))
                .filter(k -> Objects.equals(colorId, k.getColorId()))
                .map(k -> k.getQuantity() != null ? k.getQuantity() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return signedSum.negate().max(BigDecimal.ZERO);
    }

    public void decrementFromDispatchWarehouses(
            Long productId,
            Long colorId,
            String sizeLabel,
            BigDecimal quantity,
            String referenceType,
            Long referenceId,
            String referenceNumber,
            String description,
            String kardexMovementType) throws BusinessException {
        decrementFromDispatchWarehouses(productId, colorId, sizeLabel, quantity, referenceType,
                referenceId, referenceNumber, description, kardexMovementType, null);
    }

    /**
     * Descuenta inventario para envío/distribución: consume primero Devoluciones y luego Bodega PT.
     * <p>
     * {@code referenceLineId} identifica la línea del documento y es lo que hace idempotente la
     * operación sin colisionar entre tallas del mismo producto+color. Omitirlo mantiene el
     * comportamiento antiguo (una sola línea por producto+color) y no debería usarse en código nuevo.
     */
    public void decrementFromDispatchWarehouses(
            Long productId,
            Long colorId,
            String sizeLabel,
            BigDecimal quantity,
            String referenceType,
            Long referenceId,
            String referenceNumber,
            String description,
            String kardexMovementType,
            Long referenceLineId) throws BusinessException {
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        String movementType = (kardexMovementType != null && !kardexMovementType.isBlank())
                ? kardexMovementType
                : referenceType;

        assertSizeProvidedWhenRequired(productId, sizeLabel);
        Long effectiveColorId = resolveDispatchColorId(productId, colorId);

        BigDecimal alreadyConsumed = getNetConsumedForLine(
                referenceType, referenceId, movementType, productId, null, effectiveColorId, referenceLineId);
        BigDecimal remaining = quantity.subtract(alreadyConsumed);
        if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        for (LocationEntity loc : getDispatchSourceWarehouses()) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            BigDecimal available = getAvailableQuantity(productId, loc.getId(), effectiveColorId, sizeLabel);
            if (available.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal toConsume = available.min(remaining);
            BigDecimal consumed;
            try {
                consumed = applyDecrementToLocation(
                        productId,
                        loc.getId(),
                        effectiveColorId,
                        toConsume,
                        referenceType,
                        referenceId,
                        referenceNumber,
                        description,
                        sizeLabel,
                        movementType,
                        referenceLineId);
            } catch (ResourceNotFoundException e) {
                throw new BusinessException("Sin inventario registrado para producto en ubicacion " + loc.getName());
            }
            // Solo se descuenta lo realmente aplicado: si el movimiento ya existía, consumed es cero
            // y la línea sigue buscando en la siguiente bodega en lugar de darse por cumplida.
            remaining = remaining.subtract(consumed);
        }

        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            throw new BusinessException(
                    "Stock insuficiente en Devoluciones / Bodega PT (faltan " + remaining + " unidades).");
        }
    }

    /**
     * True si ya hubo salida neta para esa referencia (p. ej. venta preparada desde inventario).
     */
    public boolean hasNetOutboundForReference(String referenceType, Long referenceId) {
        if (referenceType == null || referenceId == null) {
            return false;
        }
        return productInventoryKardexRepository.findByReferenceTypeAndReferenceId(referenceType, referenceId).stream()
                .map(k -> k.getQuantity() != null ? k.getQuantity() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .compareTo(BigDecimal.ZERO) < 0;
    }

    /**
     * Devuelve al inventario las salidas de un documento y deja constancia en el kardex.
     * <p>
     * Es idempotente por neto: una línea ya revertida queda en cero y se ignora en llamadas
     * posteriores, de modo que editar dos veces un envío en tránsito no acredita el stock dos veces.
     *
     * @return unidades devueltas al inventario
     */
    public BigDecimal reverseDispatchOutflows(
            String referenceType,
            Long referenceId,
            String movementType,
            String referenceNumber,
            String description) throws BusinessException {
        if (referenceType == null || referenceId == null || movementType == null) {
            return BigDecimal.ZERO;
        }
        List<ProductInventoryKardex> rows = productInventoryKardexRepository
                .findByReferenceAndMovementTypes(referenceType, referenceId, movementFamily(movementType));

        // Una línea puede haber salido de varias bodegas: se revierte por (producto, ubicación,
        // color, talla, línea) para devolver cada unidad exactamente a donde salió.
        Map<String, ProductInventoryKardex> groups = new LinkedHashMap<>();
        for (ProductInventoryKardex row : rows) {
            groups.putIfAbsent(reversalGroupKey(row), row);
        }

        BigDecimal restoredTotal = BigDecimal.ZERO;
        for (ProductInventoryKardex sample : groups.values()) {
            BigDecimal net = getNetConsumedForLine(
                    referenceType,
                    referenceId,
                    movementType,
                    sample.getProductId(),
                    sample.getLocationId(),
                    sample.getColorId(),
                    sample.getReferenceLineId());
            if (net.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            try {
                BigDecimal before = readQuantity(sample.getProductId(), sample.getLocationId(), sample.getColorId());
                incrementInventory(
                        sample.getProductId(),
                        sample.getLocationId(),
                        sample.getColorId(),
                        net,
                        null,
                        referenceType,
                        referenceId,
                        referenceNumber,
                        description,
                        sample.getSizeLabel());
                BigDecimal after = readQuantity(sample.getProductId(), sample.getLocationId(), sample.getColorId());
                recordMovement(
                        sample.getProductId(),
                        sample.getLocationId(),
                        sample.getColorId(),
                        reversalMovementType(movementType),
                        net,
                        before,
                        after,
                        null,
                        referenceType,
                        referenceId,
                        referenceNumber,
                        description,
                        sample.getSizeLabel(),
                        sample.getReferenceLineId());
                restoredTotal = restoredTotal.add(net);
            } catch (ResourceNotFoundException e) {
                throw new BusinessException("No se pudo revertir inventario del documento: " + e.getMessage());
            }
        }
        return restoredTotal;
    }

    /**
     * Revierte egresos de una venta online acreditando el stock en una bodega destino
     * (típicamente Devoluciones), sin devolver a la ubicación de origen.
     * El neto global de la referencia queda en cero (idempotente).
     */
    public BigDecimal reverseDispatchOutflowsToLocation(
            String referenceType,
            Long referenceId,
            String movementType,
            Long targetLocationId,
            String referenceNumber,
            String description) throws BusinessException {
        if (referenceType == null || referenceId == null || movementType == null || targetLocationId == null) {
            return BigDecimal.ZERO;
        }
        if (!hasNetOutboundForReference(referenceType, referenceId)) {
            return BigDecimal.ZERO;
        }
        List<ProductInventoryKardex> rows = productInventoryKardexRepository
                .findByReferenceAndMovementTypes(referenceType, referenceId, movementFamily(movementType));

        Map<String, ProductInventoryKardex> groups = new LinkedHashMap<>();
        for (ProductInventoryKardex row : rows) {
            if (row.getQuantity() == null || row.getQuantity().compareTo(BigDecimal.ZERO) >= 0) {
                continue;
            }
            groups.putIfAbsent(reversalGroupKey(row), row);
        }

        BigDecimal restoredTotal = BigDecimal.ZERO;
        for (ProductInventoryKardex sample : groups.values()) {
            BigDecimal net = getNetConsumedForLine(
                    referenceType,
                    referenceId,
                    movementType,
                    sample.getProductId(),
                    sample.getLocationId(),
                    sample.getColorId(),
                    sample.getReferenceLineId());
            if (net.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            try {
                BigDecimal before = readQuantity(sample.getProductId(), targetLocationId, sample.getColorId());
                incrementInventory(
                        sample.getProductId(),
                        targetLocationId,
                        sample.getColorId(),
                        net,
                        null,
                        referenceType,
                        referenceId,
                        referenceNumber,
                        description,
                        sample.getSizeLabel());
                BigDecimal after = readQuantity(sample.getProductId(), targetLocationId, sample.getColorId());
                recordMovement(
                        sample.getProductId(),
                        targetLocationId,
                        sample.getColorId(),
                        reversalMovementType(movementType),
                        net,
                        before,
                        after,
                        null,
                        referenceType,
                        referenceId,
                        referenceNumber,
                        description,
                        sample.getSizeLabel(),
                        sample.getReferenceLineId());
                restoredTotal = restoredTotal.add(net);
            } catch (ResourceNotFoundException e) {
                throw new BusinessException("No se pudo devolver inventario a la bodega destino: " + e.getMessage());
            }
        }
        return restoredTotal;
    }

    private String reversalGroupKey(ProductInventoryKardex row) {
        return row.getProductId() + "|" + row.getLocationId() + "|" + row.getColorId()
                + "|" + ProductInventorySizesJson.normalizeKey(row.getSizeLabel())
                + "|" + row.getReferenceLineId();
    }

    private BigDecimal readQuantity(Long productId, Long locationId, Long colorId) {
        return productInventoryLocationRepository
                .findByProductIdAndLocationIdAndColorId(productId, locationId, colorId)
                .map(pil -> pil.getQuantity() != null ? pil.getQuantity() : BigDecimal.ZERO)
                .orElse(BigDecimal.ZERO);
    }

    /**
     * Los cinchos FOSS con desglose por talla no se pueden descargar sin talla: sin ella la
     * disponibilidad calculada es cero y la salida se perdería en silencio.
     */
    private void assertSizeProvidedWhenRequired(Long productId, String sizeLabel) throws BusinessException {
        if (!ProductInventorySizesJson.normalizeKey(sizeLabel).isEmpty()) {
            return;
        }
        ProductEntity product = productRepository.findById(productId).orElse(null);
        if (!CinchoProductUtils.isFossCinchoProduct(product)) {
            return;
        }
        boolean hasBreakdown = productInventoryLocationRepository.findByProductId(productId).stream()
                .anyMatch(pil -> !ProductInventorySizesJson.parse(pil.getSizesData()).isEmpty());
        if (hasBreakdown) {
            String name = product != null && product.getCode() != null ? product.getCode() : "#" + productId;
            throw new BusinessException("El producto " + name
                    + " maneja inventario por talla: el documento debe indicar la talla para descargar bodega.");
        }
    }

    /**
     * Resuelve la variante de color cuando el documento no la trae (envíos legacy) y el producto
     * tiene una única variante en las bodegas de despacho. Con varias variantes se respeta el null
     * recibido y la descarga fallará de forma visible en vez de descontar la fila equivocada.
     */
    public Long resolveDispatchColorId(Long productId, Long colorId) throws BusinessException {
        if (colorId != null) {
            return colorId;
        }
        List<Long> dispatchLocationIds = getDispatchSourceWarehouses().stream()
                .map(LocationEntity::getId)
                .collect(Collectors.toList());
        List<ProductInventoryLocation> rows = productInventoryLocationRepository.findByProductId(productId).stream()
                .filter(pil -> dispatchLocationIds.contains(pil.getLocationId()))
                .collect(Collectors.toList());
        if (rows.stream().anyMatch(pil -> pil.getColorId() == null)) {
            return null;
        }
        List<Long> distinctColors = rows.stream()
                .map(ProductInventoryLocation::getColorId)
                .distinct()
                .collect(Collectors.toList());
        return distinctColors.size() == 1 ? distinctColors.get(0) : null;
    }

    private LocationEntity findBodegaPtLocation() {
        Optional<InventoryLocationTypeEntity> bodegaType =
                inventoryLocationTypeRepository.findByCodeAndIsActiveTrue("BODEGA_PT");
        if (bodegaType.isEmpty()) {
            return null;
        }
        return locationRepository.findAll().stream()
                .filter(loc -> "BODEGA_PT".equalsIgnoreCase(loc.getCode()))
                .findFirst()
                .orElse(null);
    }

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
            throws ResourceNotFoundException, BusinessException {
        // Validar que el producto existe
        if (!productRepository.existsById(request.getProductId())) {
            throw new ResourceNotFoundException("Product", request.getProductId());
        }

        // Validar que la ubicación existe
        if (!locationRepository.existsById(request.getLocationId())) {
            throw new ResourceNotFoundException("Location", request.getLocationId());
        }

        kioskInventoryGuard.assertSupervisorMayModifyKioskInventory(request.getLocationId());

        // Buscar si ya existe - SIEMPRE considerar color_id (incluso si es null)
        // Esto es importante porque la restricción única incluye color_id
        Optional<ProductInventoryLocation> existing = productInventoryLocationRepository
                .findByProductIdAndLocationIdAndColorId(
                        request.getProductId(), 
                        request.getLocationId(),
                        request.getColorId()); // Puede ser null, pero se busca específicamente con null

        ProductInventoryLocation entity;
        if (existing.isPresent()) {
            entity = existing.get();
            if (request.getSizes() != null && !request.getSizes().isEmpty()) {
                ProductEntity product = productRepository.findById(request.getProductId()).orElse(null);
                if (!CinchoProductUtils.isFossCinchoProduct(product)) {
                    throw new BusinessException("El desglose por tallas solo aplica a productos cincho FOSS.");
                }
                Map<String, BigDecimal> m = normalizeSizesMap(request.getSizes());
                entity.setSizesData(ProductInventorySizesJson.serialize(m));
                entity.setQuantity(ProductInventorySizesJson.sum(m));
            } else {
                entity.setQuantity(request.getQuantity());
            }
            if (request.getColorId() != null) {
                entity.setColorId(request.getColorId());
            }
        } else {
            if (request.getSizes() != null && !request.getSizes().isEmpty()) {
                ProductEntity product = productRepository.findById(request.getProductId()).orElse(null);
                if (!CinchoProductUtils.isFossCinchoProduct(product)) {
                    throw new BusinessException("El desglose por tallas solo aplica a productos cincho FOSS.");
                }
                Map<String, BigDecimal> m = normalizeSizesMap(request.getSizes());
                entity = ProductInventoryLocation.builder()
                        .productId(request.getProductId())
                        .locationId(request.getLocationId())
                        .colorId(request.getColorId())
                        .quantity(ProductInventorySizesJson.sum(m))
                        .sizesData(ProductInventorySizesJson.serialize(m))
                        .build();
            } else {
                entity = ProductInventoryLocation.builder()
                        .productId(request.getProductId())
                        .locationId(request.getLocationId())
                        .colorId(request.getColorId())
                        .quantity(request.getQuantity())
                        .build();
            }
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
            throws ResourceNotFoundException, BusinessException {
        return incrementInventory(productId, locationId, null, quantity, null, null, null, null, null);
    }

    /**
     * Incrementa el inventario de un producto en una ubicación considerando variantes de color
     */
    public ProductInventoryLocationResponse incrementInventory(Long productId, Long locationId, Long colorId, BigDecimal quantity)
            throws ResourceNotFoundException, BusinessException {
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
            throws ResourceNotFoundException, BusinessException {
        return incrementInventory(productId, locationId, colorId, quantity, unitCost, referenceType, referenceId, referenceNumber, description, null);
    }

    /**
     * Incrementa inventario; para cinchos FOSS con desglose por talla use {@code sizeKey}.
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
            String description,
            String sizeKey) 
            throws ResourceNotFoundException, BusinessException {
        if (!productRepository.existsById(productId)) {
            throw new ResourceNotFoundException("Product", productId);
        }
        ProductEntity product = productRepository.findById(productId).orElse(null);
        Optional<ProductInventoryLocation> existing =
                productInventoryLocationRepository.findByProductIdAndLocationIdAndColorId(productId, locationId, colorId);

        ProductInventoryLocation entity;
        if (existing.isPresent()) {
            entity = existing.get();
            applyFossIncrementToEntity(entity, product, quantity, sizeKey);
        } else {
            entity = ProductInventoryLocation.builder()
                    .productId(productId)
                    .locationId(locationId)
                    .colorId(colorId)
                    .quantity(BigDecimal.ZERO)
                    .build();
            applyFossIncrementToEntity(entity, product, quantity, sizeKey);
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
        return decrementInventory(productId, locationId, null, quantity, null, null, null, null, null);
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
        return decrementInventory(productId, locationId, colorId, quantity, referenceType, referenceId, referenceNumber, description, null);
    }

    public ProductInventoryLocationResponse decrementInventory(
            Long productId, 
            Long locationId, 
            Long colorId,
            BigDecimal quantity,
            String referenceType,
            Long referenceId,
            String referenceNumber,
            String description,
            String sizeKey) 
            throws ResourceNotFoundException, BusinessException {
        return decrementInventory(productId, locationId, colorId, quantity, referenceType, referenceId,
                referenceNumber, description, sizeKey, null);
    }

    public ProductInventoryLocationResponse decrementInventory(
            Long productId,
            Long locationId,
            Long colorId,
            BigDecimal quantity,
            String referenceType,
            Long referenceId,
            String referenceNumber,
            String description,
            String sizeKey,
            String kardexMovementType)
            throws ResourceNotFoundException, BusinessException {
        return decrementInventory(productId, locationId, colorId, quantity, referenceType, referenceId,
                referenceNumber, description, sizeKey, kardexMovementType, null);
    }

    /**
     * Decrementa inventario en una ubicación. {@code referenceLineId} identifica la línea del
     * documento y evita que dos tallas del mismo producto+color se confundan entre sí.
     */
    public ProductInventoryLocationResponse decrementInventory(
            Long productId,
            Long locationId,
            Long colorId,
            BigDecimal quantity,
            String referenceType,
            Long referenceId,
            String referenceNumber,
            String description,
            String sizeKey,
            String kardexMovementType,
            Long referenceLineId)
            throws ResourceNotFoundException, BusinessException {
        applyDecrementToLocation(productId, locationId, colorId, quantity, referenceType, referenceId,
                referenceNumber, description, sizeKey, kardexMovementType, referenceLineId);
        return toProductInventoryLocationResponse(productInventoryLocationRepository
                .findByProductIdAndLocationIdAndColorId(productId, locationId, colorId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Product Inventory Location",
                        "Product: " + productId +
                        ", Location: " + locationId +
                        (colorId != null ? ", Color: " + colorId : ", Color: NULL"))));
    }

    /**
     * Aplica el decremento y devuelve las unidades realmente descontadas: cero cuando el movimiento
     * de esa línea ya estaba registrado en esta ubicación. Quien llama debe usar este valor en lugar
     * de asumir que se consumió todo lo solicitado.
     */
    private BigDecimal applyDecrementToLocation(
            Long productId,
            Long locationId,
            Long colorId,
            BigDecimal quantity,
            String referenceType,
            Long referenceId,
            String referenceNumber,
            String description,
            String sizeKey,
            String kardexMovementType,
            Long referenceLineId)
            throws ResourceNotFoundException, BusinessException {
        ProductInventoryLocation entity = productInventoryLocationRepository
                .findByProductIdAndLocationIdAndColorId(productId, locationId, colorId)
                .orElseThrow(() -> new ResourceNotFoundException(
                    "Product Inventory Location",
                    "Product: " + productId +
                    ", Location: " + locationId +
                    (colorId != null ? ", Color: " + colorId : ", Color: NULL")));

        String movementType = (kardexMovementType != null && !kardexMovementType.isBlank())
                ? kardexMovementType
                : referenceType;

        if (quantity != null && quantity.compareTo(BigDecimal.ZERO) > 0
                && referenceType != null && !referenceType.isBlank()
                && referenceId != null) {
            // Neto y no mera existencia: tras revertir un envío el neto vuelve a cero y la línea
            // puede volver a descargarse, cosa que un simple "ya existe el movimiento" impedía.
            BigDecimal alreadyHere = getNetConsumedForLine(
                    referenceType, referenceId, movementType, productId, locationId, colorId, referenceLineId);
            if (alreadyHere.compareTo(quantity) >= 0) {
                return BigDecimal.ZERO;
            }
        }

        ProductEntity product = productRepository.findById(productId).orElse(null);
        BigDecimal quantityBefore = entity.getQuantity() != null ? entity.getQuantity() : BigDecimal.ZERO;

        applyFossDecrementToEntity(entity, product, quantity, sizeKey);

        BigDecimal quantityAfter = entity.getQuantity() != null ? entity.getQuantity() : BigDecimal.ZERO;
        productInventoryLocationRepository.save(entity);

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

        // Registrar kardex (salida = cantidad negativa). El candado de idempotencia ya se evaluó
        // arriba, así que aquí siempre se escribe: si la fila faltara, el neto de la línea quedaría
        // en cero y un reintento volvería a descontar el mismo stock.
        if (quantity != null && quantity.compareTo(BigDecimal.ZERO) > 0
                && referenceType != null && !referenceType.isBlank()
                && referenceId != null) {
            try {
                recordMovement(
                        productId,
                        locationId,
                        colorId,
                        movementType,
                        quantity.negate(),
                        quantityBefore,
                        quantityAfter,
                        null,
                        referenceType,
                        referenceId,
                        referenceNumber,
                        description,
                        sizeKey,
                        referenceLineId
                );
            } catch (ResourceNotFoundException e) {
                throw new BusinessException(
                        "No se pudo registrar el movimiento de kardex de la salida; se revierte la operación: "
                                + e.getMessage());
            }
        }

        return quantity;
    }

    public boolean hasProductKardexMovement(
            String referenceType,
            Long referenceId,
            String movementType,
            Long productId,
            Long locationId,
            Long colorId) {
        if (referenceType == null || referenceId == null || movementType == null) {
            return false;
        }
        return productInventoryKardexRepository.existsMovement(
                referenceType, referenceId, movementType, productId, locationId, colorId);
    }

    public boolean hasProductKardexMovement(
            String referenceType,
            Long referenceId,
            String movementType,
            Long productId,
            Long locationId,
            Long colorId,
            String referenceNumber) {
        if (referenceNumber == null || referenceNumber.isBlank()) {
            return hasProductKardexMovement(referenceType, referenceId, movementType, productId, locationId, colorId);
        }
        return productInventoryKardexRepository.existsMovementWithReferenceNumber(
                referenceType, referenceId, movementType, productId, locationId, colorId, referenceNumber);
    }

    public void recordProductMovementIfAbsent(
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
        if (referenceType != null && referenceId != null
                && hasProductKardexMovement(referenceType, referenceId, movementType, productId, locationId, colorId)) {
            return;
        }
        recordMovement(productId, locationId, colorId, movementType, quantity, quantityBefore, quantityAfter,
                unitCost, referenceType, referenceId, referenceNumber, description);
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
        return recordMovement(productId, locationId, colorId, movementType, quantity, quantityBefore,
                quantityAfter, unitCost, referenceType, referenceId, referenceNumber, description, null, null);
    }

    /**
     * Registra un movimiento en el kardex dejando trazada la talla y la línea del documento, que es
     * lo que permite revertir la salida exacta y distinguir tallas dentro del mismo documento.
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
            String description,
            String sizeLabel,
            Long referenceLineId) throws ResourceNotFoundException {

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

        Long createdBy = securityUtil != null ? securityUtil.getCurrentUserId() : null;

        String normalizedSize = ProductInventorySizesJson.normalizeKey(sizeLabel);

        ProductInventoryKardex entity = ProductInventoryKardex.builder()
                .productId(productId)
                .locationId(locationId)
                .colorId(colorId)
                .sizeLabel(normalizedSize.isEmpty() ? null : normalizedSize)
                .referenceLineId(referenceLineId)
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
                .createdBy(createdBy)
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
        
        // Para KIOSKO, usar solo ubicaciones marcadas como kiosko.
        if ("KIOSKO".equals(categoryUpper)) {
            categoryLocations = getKioskLocations();
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
        
        // Para KIOSKO, esta ruta devuelve el agregado de todas las ubicaciones kiosko.
        // Para una ubicación específica, usar /product-inventory/location/{locationId}.
        if ("KIOSKO".equals(categoryUpper)) {
            return getAggregatedInventoryByCategory(categoryUpper);
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

    private List<LocationEntity> getKioskLocations() {
        return locationRepository.findByCategoriaIgnoreCaseOrderByNameAsc("KIOSKO");
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
     * - Si locationId == null y category == "KIOSKO": Inserta en TODAS las ubicaciones con categoria KIOSKO
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
            // usar solo ubicaciones con categoria=KIOSKO.
            if ("KIOSKO".equals(categoryUpper)) {
                targetLocations = getKioskLocations();
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

    private Map<String, BigDecimal> sizesMapForResponse(ProductEntity product, ProductInventoryLocation entity) {
        if (!CinchoProductUtils.isFossCinchoProduct(product)) {
            return null;
        }
        Map<String, BigDecimal> m = ProductInventorySizesJson.parse(entity.getSizesData());
        return m.isEmpty() ? null : new LinkedHashMap<>(m);
    }

    private Map<String, BigDecimal> normalizeSizesMap(Map<String, BigDecimal> raw) {
        return ProductInventorySizesJson.normalizeIncomingMap(raw);
    }

    private void applyFossIncrementToEntity(ProductInventoryLocation entity, ProductEntity product,
            BigDecimal quantity, String sizeKey) throws BusinessException {
        if (quantity == null) {
            throw new BusinessException("Cantidad invalida");
        }
        if (!CinchoProductUtils.isFossCinchoProduct(product)) {
            BigDecimal base = entity.getQuantity() != null ? entity.getQuantity() : BigDecimal.ZERO;
            entity.setQuantity(base.add(quantity));
            return;
        }
        Map<String, BigDecimal> m = ProductInventorySizesJson.parse(entity.getSizesData());
        boolean breakdown = !m.isEmpty();
        String k = ProductInventorySizesJson.normalizeKey(sizeKey);
        if (breakdown || !k.isEmpty()) {
            if (k.isEmpty()) {
                throw new BusinessException("Indique la talla para el ingreso de inventario del cincho FOSS.");
            }
            m.merge(k, quantity, BigDecimal::add);
            ProductInventorySizesJson.removeZeroEntries(m);
            entity.setSizesData(ProductInventorySizesJson.serialize(m));
            entity.setQuantity(ProductInventorySizesJson.sum(m));
            return;
        }
        BigDecimal base = entity.getQuantity() != null ? entity.getQuantity() : BigDecimal.ZERO;
        entity.setQuantity(base.add(quantity));
    }

    private void applyFossDecrementToEntity(ProductInventoryLocation entity, ProductEntity product,
            BigDecimal quantity, String sizeKey) throws BusinessException {
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("Cantidad a descontar invalida");
        }
        if (!CinchoProductUtils.isFossCinchoProduct(product)) {
            BigDecimal cur = entity.getQuantity() != null ? entity.getQuantity() : BigDecimal.ZERO;
            BigDecimal newQ = cur.subtract(quantity);
            if (newQ.compareTo(BigDecimal.ZERO) < 0) {
                throw new BusinessException("No hay suficiente inventario. Disponible: " + cur + ", Solicitado: " + quantity);
            }
            entity.setQuantity(newQ);
            return;
        }
        Map<String, BigDecimal> m = ProductInventorySizesJson.parse(entity.getSizesData());
        if (!m.isEmpty()) {
            String k = ProductInventorySizesJson.normalizeKey(sizeKey);
            if (k.isEmpty()) {
                throw new BusinessException("Indique la talla del cincho FOSS para descontar inventario.");
            }
            BigDecimal have = m.getOrDefault(k, BigDecimal.ZERO);
            if (have.compareTo(quantity) < 0) {
                throw new BusinessException("Stock insuficiente de talla " + k + ". Disponible: " + have.stripTrailingZeros().toPlainString()
                        + ", solicitado: " + quantity.stripTrailingZeros().toPlainString());
            }
            m.put(k, have.subtract(quantity));
            ProductInventorySizesJson.removeZeroEntries(m);
            entity.setSizesData(ProductInventorySizesJson.serialize(m));
            entity.setQuantity(ProductInventorySizesJson.sum(m));
            return;
        }
        BigDecimal cur = entity.getQuantity() != null ? entity.getQuantity() : BigDecimal.ZERO;
        BigDecimal newQ = cur.subtract(quantity);
        if (newQ.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("No hay suficiente inventario. Disponible: " + cur + ", Solicitado: " + quantity);
        }
        entity.setQuantity(newQ);
    }

    private ProductInventoryLocationResponse toProductInventoryLocationResponse(ProductInventoryLocation entity) {
        ProductEntity product = productRepository.findById(entity.getProductId()).orElse(null);
        LocationEntity location = locationRepository.findById(entity.getLocationId()).orElse(null);
        ColorEntity color = entity.getColorId() != null ? colorRepository.findById(entity.getColorId()).orElse(null) : null;

        return enrichProductCategory(product,
                ProductInventoryLocationResponse.builder()
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
                .sizes(sizesMapForResponse(product, entity))
                .createdAt(entity.getCreatedAt())
                .createdBy(entity.getCreatedBy())
                .updatedAt(entity.getUpdatedAt())
                .updatedBy(entity.getUpdatedBy())
        ).build();
    }

    private ProductInventoryLocationResponse.ProductInventoryLocationResponseBuilder enrichProductCategory(
            ProductEntity product,
            ProductInventoryLocationResponse.ProductInventoryLocationResponseBuilder builder
    ) {
        if (product == null || product.getCategoryId() == null) {
            return builder.productCategoryId(null).productCategoryName(null);
        }
        Long cid = product.getCategoryId();
        return builder.productCategoryId(cid)
                .productCategoryName(productCategoryRepository.findById(cid)
                        .map(ProductCategoryEntity::getName).orElse(null));
    }

    /**
     * Color guardado en la fila de kardex, o —si es histórico sin color— la única fila de inventario
     * producto+ubicación (evita adivinar si hay varias variantes).
     */
    private Long resolveKardexColorId(ProductInventoryKardex entity) {
        if (entity.getColorId() != null) {
            return entity.getColorId();
        }
        List<ProductInventoryLocation> rows = productInventoryLocationRepository
                .findAllByProductIdAndLocationId(entity.getProductId(), entity.getLocationId());
        if (rows.size() == 1) {
            return rows.get(0).getColorId();
        }
        return null;
    }

    private ProductInventoryKardexResponse toProductInventoryKardexResponse(ProductInventoryKardex entity) {
        ProductEntity product = productRepository.findById(entity.getProductId()).orElse(null);
        LocationEntity location = locationRepository.findById(entity.getLocationId()).orElse(null);

        Long colorIdForFifoAndName = resolveKardexColorId(entity);
        ColorEntity color = colorIdForFifoAndName != null
                ? colorRepository.findById(colorIdForFifoAndName).orElse(null)
                : null;

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

        // Lotes FIFO alineados con el color del movimiento (o inferencia conservadora en histórico)
        List<ProductFifoBatch> availableBatches = productFifoBatchRepository
                .findAvailableBatchesByProductAndLocationAndColor(
                        entity.getProductId(), entity.getLocationId(), colorIdForFifoAndName);
        
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
                .colorId(entity.getColorId())
                .colorName(color != null ? color.getName() : null)
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
        return enrichProductCategory(product,
                ProductInventoryLocationResponse.builder()
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
                .sizes(null)
                .createdAt(null)
                .createdBy(null)
                .updatedAt(null)
                .updatedBy(null)
        ).build();
    }
}


