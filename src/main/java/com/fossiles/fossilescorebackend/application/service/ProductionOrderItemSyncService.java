package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.ProductionOrderItemRequest;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ProductionOrderItemConflictException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderWarehouseUnitRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Sincroniza las líneas de una orden sin borrarlas y volverlas a crear.
 * Una línea con id se actualiza en su sitio. Una línea sin id reutiliza la única
 * línea suelta con el mismo producto, color y marca que se va a guardar; si hay
 * más de una, entra como línea nueva. Lo que el request ya no trae se borra solo
 * si nadie lo cita. Una línea emparejada (por id o por ese respaldo) tampoco puede
 * cambiar producto, color o marca de MARCAS si está citada, ni bajar la cantidad
 * por debajo de lo ya asignado a tareas o recibido en bodega.
 */
@Service
@RequiredArgsConstructor
public class ProductionOrderItemSyncService {

    private final ProductionOrderItemRepository itemRepository;
    private final TaskRepository taskRepository;
    private final TaskItemRepository taskItemRepository;
    private final ProductionOrderWarehouseUnitRepository warehouseUnitRepository;
    private final ProductRepository productRepository;
    private final ColorRepository colorRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional(rollbackFor = Exception.class)
    public void sync(Long productionOrderId, String orderType, List<ProductionOrderItemRequest> requests)
            throws BusinessException {
        if (requests == null) {
            return;
        }
        List<ProductionOrderItemEntity> existing = new ArrayList<>();
        List<ProductionOrderItemEntity> loaded = itemRepository.findByProductionOrderId(productionOrderId);
        if (loaded != null) {
            existing.addAll(loaded);
        }
        Map<Long, ProductionOrderItemEntity> byId = new HashMap<>();
        for (ProductionOrderItemEntity line : existing) {
            if (line.getId() != null) {
                byId.putIfAbsent(line.getId(), line);
            }
        }

        Map<ProductionOrderItemRequest, ProductionOrderItemEntity> matched = new IdentityHashMap<>();
        Set<Long> seenIds = new HashSet<>();
        for (ProductionOrderItemRequest request : requests) {
            if (request == null) {
                throw new BusinessException("La solicitud incluye una línea vacía.");
            }
            if (request.getId() == null) {
                continue;
            }
            if (!seenIds.add(request.getId())) {
                throw new BusinessException("La línea " + request.getId() + " está repetida en la solicitud.");
            }
            matched.put(request, requireOwnedLine(productionOrderId, request.getId(), byId, existing));
        }

        Set<Long> usedIds = new HashSet<>();
        for (ProductionOrderItemEntity line : matched.values()) {
            if (line.getId() != null) {
                usedIds.add(line.getId());
            }
        }
        for (ProductionOrderItemRequest request : requests) {
            if (request.getId() != null) {
                continue;
            }
            List<ProductionOrderItemEntity> candidates = new ArrayList<>();
            for (ProductionOrderItemEntity line : existing) {
                if (line.getId() != null && usedIds.contains(line.getId())) {
                    continue;
                }
                if (samePersistedKey(orderType, line, request)) {
                    candidates.add(line);
                }
            }
            if (candidates.size() == 1) {
                ProductionOrderItemEntity line = candidates.get(0);
                matched.put(request, line);
                if (line.getId() != null) {
                    usedIds.add(line.getId());
                }
            }
        }

        List<ProductionOrderItemEntity> toDelete = new ArrayList<>();
        for (ProductionOrderItemEntity line : existing) {
            if (line.getId() == null || !usedIds.contains(line.getId())) {
                toDelete.add(line);
            }
        }
        if (!toDelete.isEmpty()) {
            assertCanDelete(toDelete);
        }
        assertMatchedLinesCanChange(orderType, matched);

        List<ProductionOrderItemEntity> toSave = new ArrayList<>();
        for (ProductionOrderItemRequest request : requests) {
            ProductionOrderItemEntity line = matched.get(request);
            if (line == null) {
                toSave.add(newLine(productionOrderId, orderType, request));
            } else {
                apply(line, orderType, request);
                toSave.add(line);
            }
        }
        if (!toDelete.isEmpty()) {
            itemRepository.deleteAll(toDelete);
        }
        if (!toSave.isEmpty()) {
            itemRepository.saveAll(toSave);
        }
    }

    private ProductionOrderItemEntity requireOwnedLine(
            Long productionOrderId,
            Long itemId,
            Map<Long, ProductionOrderItemEntity> byId,
            List<ProductionOrderItemEntity> existing) throws BusinessException {
        ProductionOrderItemEntity line = byId.get(itemId);
        if (line != null) {
            return line;
        }
        line = itemRepository.findById(itemId).orElse(null);
        if (line == null) {
            throw new BusinessException("La línea " + itemId + " no existe.");
        }
        if (!productionOrderId.equals(line.getProductionOrderId())) {
            throw new BusinessException("La línea " + itemId + " no pertenece a esta orden de producción.");
        }
        byId.put(line.getId(), line);
        existing.add(line);
        return line;
    }

    private void assertCanDelete(List<ProductionOrderItemEntity> toDelete) {
        List<Long> ids = idsOf(toDelete);
        if (ids.isEmpty()) {
            return;
        }
        LineRefs refs = referencesOf(ids);
        List<ProductionOrderItemEntity> blocked = new ArrayList<>();
        for (ProductionOrderItemEntity line : toDelete) {
            if (refs.blocks(line.getId())) {
                blocked.add(line);
            }
        }
        if (blocked.isEmpty()) {
            return;
        }
        Map<Long, ProductEntity> products = loadProducts(blocked);
        Map<Long, ColorEntity> colors = loadColors(blocked);
        String message = blocked.stream()
                .map(line -> "No se puede quitar la línea " + lineLabel(line, products, colors)
                        + " " + because(refs.tasks.contains(line.getId()), refs.units.contains(line.getId())) + ".")
                .collect(Collectors.joining(" "));
        throw new ProductionOrderItemConflictException(message);
    }

    /**
     * Revisa las líneas ya emparejadas, venga el match del id o del respaldo
     * producto + color + marca. No escribe: si algo no se puede, lanza antes.
     */
    private void assertMatchedLinesCanChange(
            String orderType,
            Map<ProductionOrderItemRequest, ProductionOrderItemEntity> matched) {
        List<Map.Entry<ProductionOrderItemRequest, ProductionOrderItemEntity>> identityChanges = new ArrayList<>();
        List<Long> matchedIds = new ArrayList<>();
        for (Map.Entry<ProductionOrderItemRequest, ProductionOrderItemEntity> entry : matched.entrySet()) {
            ProductionOrderItemEntity line = entry.getValue();
            if (line.getId() != null) {
                matchedIds.add(line.getId());
            }
            if (identityChanges(orderType, line, entry.getKey())) {
                identityChanges.add(entry);
            }
        }

        List<String> messages = new ArrayList<>();
        if (!identityChanges.isEmpty()) {
            LineRefs refs = referencesOf(identityChanges.stream().map(entry -> entry.getValue().getId()).toList());
            List<ProductionOrderItemEntity> blocked = new ArrayList<>();
            for (Map.Entry<ProductionOrderItemRequest, ProductionOrderItemEntity> entry : identityChanges) {
                if (refs.blocks(entry.getValue().getId())) {
                    blocked.add(entry.getValue());
                }
            }
            if (!blocked.isEmpty()) {
                Map<Long, ProductEntity> products = loadProducts(blocked);
                Map<Long, ColorEntity> colors = loadColors(blocked);
                for (ProductionOrderItemEntity line : blocked) {
                    messages.add("No se puede cambiar la línea " + lineLabel(line, products, colors)
                            + " " + because(refs.tasks.contains(line.getId()), refs.units.contains(line.getId())) + ".");
                }
            }
        }

        if (!matchedIds.isEmpty()) {
            Map<Long, Integer> assigned = taskItemRepository.assignedQuantityMap(matchedIds);
            if (assigned == null) {
                assigned = Map.of();
            }
            List<ProductionOrderItemEntity> tooLow = new ArrayList<>();
            Map<Long, Integer> minimumByLine = new HashMap<>();
            for (Map.Entry<ProductionOrderItemRequest, ProductionOrderItemEntity> entry : matched.entrySet()) {
                Integer requested = entry.getKey().getQuantity();
                if (requested == null || entry.getValue().getId() == null) {
                    continue;
                }
                int minimum = quantityFloor(entry.getValue(), assigned.get(entry.getValue().getId()));
                if (requested < minimum) {
                    tooLow.add(entry.getValue());
                    minimumByLine.put(entry.getValue().getId(), minimum);
                }
            }
            if (!tooLow.isEmpty()) {
                Map<Long, ProductEntity> products = loadProducts(tooLow);
                Map<Long, ColorEntity> colors = loadColors(tooLow);
                for (ProductionOrderItemEntity line : tooLow) {
                    messages.add("No se puede bajar la cantidad de la línea " + lineLabel(line, products, colors)
                            + " por debajo de " + minimumByLine.get(line.getId()) + ".");
                }
            }
        }

        if (!messages.isEmpty()) {
            throw new ProductionOrderItemConflictException(String.join(" ", messages));
        }
    }

    /** Máximo entre lo asignado a tareas no canceladas y lo ya recibido en bodega. */
    private static int quantityFloor(ProductionOrderItemEntity line, Integer assigned) {
        int assignedQty = assigned == null ? 0 : assigned;
        int received = line.getWarehouseReceivedQty() == null ? 0 : line.getWarehouseReceivedQty();
        return Math.max(assignedQty, received);
    }

    private static boolean identityChanges(
            String orderType, ProductionOrderItemEntity line, ProductionOrderItemRequest request) {
        if (!Objects.equals(line.getProductId(), request.getProductId())
                || !Objects.equals(line.getColorId(), request.getColorId())) {
            return true;
        }
        return "MARCAS".equals(orderType)
                && !Objects.equals(storedBrand(orderType, line.getBrandName()), storedBrand(orderType, request.getBrandName()));
    }

    private LineRefs referencesOf(Collection<Long> ids) {
        List<Long> present = new ArrayList<>();
        for (Long id : ids) {
            if (id != null && !present.contains(id)) {
                present.add(id);
            }
        }
        if (present.isEmpty()) {
            return new LineRefs(Set.of(), Set.of());
        }
        Set<Long> tasks = idsOf(taskRepository.findNonCancelledItemIds(present));
        tasks.addAll(idsOf(taskItemRepository.findNonCancelledItemIds(present)));
        return new LineRefs(tasks, idsOf(warehouseUnitRepository.findReferencedItemIds(present)));
    }

    private static List<Long> idsOf(List<ProductionOrderItemEntity> lines) {
        List<Long> ids = new ArrayList<>();
        for (ProductionOrderItemEntity line : lines) {
            if (line.getId() != null) {
                ids.add(line.getId());
            }
        }
        return ids;
    }

    private static String because(boolean hasTasks, boolean hasUnits) {
        if (hasTasks && hasUnits) {
            return "porque tiene tareas y unidades de bodega";
        }
        if (hasTasks) {
            return "porque tiene tareas";
        }
        return "porque tiene unidades de bodega";
    }

    private record LineRefs(Set<Long> tasks, Set<Long> units) {
        boolean blocks(Long id) {
            return id != null && (tasks.contains(id) || units.contains(id));
        }
    }

    private static String lineLabel(
            ProductionOrderItemEntity line,
            Map<Long, ProductEntity> products,
            Map<Long, ColorEntity> colors) {
        ProductEntity product = line.getProductId() == null ? null : products.get(line.getProductId());
        String productLabel;
        if (product != null && product.getCode() != null && !product.getCode().isBlank()) {
            productLabel = product.getCode();
        } else if (line.getProductId() != null) {
            productLabel = "producto #" + line.getProductId();
        } else {
            productLabel = "línea #" + line.getId();
        }
        if (line.getColorId() == null) {
            return productLabel;
        }
        ColorEntity color = colors.get(line.getColorId());
        String colorLabel = color != null && color.getName() != null && !color.getName().isBlank()
                ? color.getName()
                : "color #" + line.getColorId();
        return productLabel + " / " + colorLabel;
    }

    private Map<Long, ProductEntity> loadProducts(List<ProductionOrderItemEntity> lines) {
        Set<Long> ids = lines.stream()
                .map(ProductionOrderItemEntity::getProductId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<ProductEntity> found = productRepository.findAllById(ids);
        if (found == null) {
            return Map.of();
        }
        Map<Long, ProductEntity> byId = new HashMap<>();
        for (ProductEntity product : found) {
            if (product.getId() != null) {
                byId.putIfAbsent(product.getId(), product);
            }
        }
        return byId;
    }

    private Map<Long, ColorEntity> loadColors(List<ProductionOrderItemEntity> lines) {
        Set<Long> ids = lines.stream()
                .map(ProductionOrderItemEntity::getColorId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<ColorEntity> found = colorRepository.findAllById(ids);
        if (found == null) {
            return Map.of();
        }
        Map<Long, ColorEntity> byId = new HashMap<>();
        for (ColorEntity color : found) {
            if (color.getId() != null) {
                byId.putIfAbsent(color.getId(), color);
            }
        }
        return byId;
    }

    private static Set<Long> idsOf(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return new HashSet<>();
        }
        Set<Long> out = new HashSet<>();
        for (Long id : ids) {
            if (id != null) {
                out.add(id);
            }
        }
        return out;
    }

    /**
     * Misma clave con la que la línea queda guardada: producto, color y marca.
     * En MARCAS la marca se normaliza a mayúsculas; en los demás tipos no se guarda.
     */
    private static boolean samePersistedKey(
            String orderType, ProductionOrderItemEntity line, ProductionOrderItemRequest request) {
        return Objects.equals(line.getProductId(), request.getProductId())
                && Objects.equals(line.getColorId(), request.getColorId())
                && Objects.equals(storedBrand(orderType, line.getBrandName()), storedBrand(orderType, request.getBrandName()));
    }

    /** Igual que {@code ProductionOrderController}: la marca solo se persiste en órdenes MARCAS. */
    private static String storedBrand(String orderType, String brandName) {
        if (!"MARCAS".equals(orderType)) {
            return null;
        }
        String normalized = String.valueOf(brandName == null ? "" : brandName).trim().toUpperCase();
        return normalized.isEmpty() ? null : normalized;
    }

    private ProductionOrderItemEntity newLine(
            Long productionOrderId, String orderType, ProductionOrderItemRequest request) {
        return ProductionOrderItemEntity.builder()
                .productionOrderId(productionOrderId)
                .productId(request.getProductId())
                .colorId(request.getColorId())
                .brandName(storedBrand(orderType, request.getBrandName()))
                .quantity(request.getQuantity())
                .warehouseReceivedQty(0)
                .sizesData(sizesJson(request.getSizes()))
                .observations(request.getObservations())
                .unitPrice(request.getUnitPrice())
                .unitPricesJson(unitPricesJson(request.getUnitPrices()))
                .build();
    }

    private void apply(ProductionOrderItemEntity line, String orderType, ProductionOrderItemRequest request) {
        line.setProductId(request.getProductId());
        line.setColorId(request.getColorId());
        line.setBrandName(storedBrand(orderType, request.getBrandName()));
        line.setQuantity(request.getQuantity());
        line.setSizesData(sizesJson(request.getSizes()));
        line.setObservations(request.getObservations());
        line.setUnitPrice(request.getUnitPrice());
        line.setUnitPricesJson(unitPricesJson(request.getUnitPrices()));
    }

    private String sizesJson(Map<String, Integer> sizes) {
        if (sizes == null) {
            return null;
        }
        return writeJson(sizes);
    }

    private String unitPricesJson(Map<String, BigDecimal> unitPrices) {
        if (unitPrices == null || unitPrices.isEmpty()) {
            return null;
        }
        return writeJson(unitPrices);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }
}
