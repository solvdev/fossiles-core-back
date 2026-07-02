package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.*;
import com.fossiles.fossilescorebackend.infrastructure.util.CinchoProductUtils;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductionOrderItemQuantityHelper;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MaterialConsumptionService {

    private final ProductionOrderRepository productionOrderRepository;
    private final ProductionOrderItemRepository productionOrderItemRepository;
    private final BomRepository bomRepository;
    private final MaterialConsumptionRepository materialConsumptionRepository;
    private final InventoryService inventoryService;
    private final MaterialRepository materialRepository;
    private final ProductRepository productRepository;
    private final TaskRepository taskRepository;
    private final TaskItemRepository taskItemRepository;
    private final SecurityUtil securityUtil;

    /**
     * Consume materiales según BOM para todos los items de una OP.
     * Valida disponibilidad antes de descontar.
     */
    @Transactional
    public Map<String, Object> consumeMaterialsForOrder(Long productionOrderId)
            throws ResourceNotFoundException, BusinessException {

        ProductionOrderEntity order = productionOrderRepository.findById(productionOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("Production Order", productionOrderId));

        if (Boolean.TRUE.equals(order.getMaterialsConsumed())) {
            throw new BusinessException("Los materiales ya fueron consumidos para esta orden.");
        }

        List<ProductionOrderItemEntity> items = productionOrderItemRepository
                .findByProductionOrderId(productionOrderId);

        if (items.isEmpty()) {
            throw new BusinessException("La orden no tiene productos.");
        }

        // 1. Calcular requerimientos totales
        Map<Long, BigDecimal> totalRequirements = new LinkedHashMap<>();
        Map<Long, List<BomUsage>> bomUsages = new LinkedHashMap<>();

        for (ProductionOrderItemEntity item : items) {
            BomEntity bom = resolveBomForOrderItem(item);

            if (bom == null || bom.getItems() == null || bom.getItems().isEmpty()) continue;

            int effQty = ProductionOrderItemQuantityHelper.effectiveQuantityForBom(item);
            for (BomItemEntity bi : bom.getItems()) {
                BigDecimal needed = bi.getQuantity().multiply(BigDecimal.valueOf(effQty));
                totalRequirements.merge(bi.getMaterialId(), needed, BigDecimal::add);

                bomUsages.computeIfAbsent(bi.getMaterialId(), k -> new ArrayList<>())
                        .add(new BomUsage(bom.getId(), item.getProductId(), bi.getMaterialId(), needed));
            }
        }

        if (totalRequirements.isEmpty()) {
            throw new BusinessException("Ningún producto tiene receta (BOM) activa. No se puede consumir materiales.");
        }

        // 2. Validar disponibilidad
        List<String> shortages = new ArrayList<>();
        for (Map.Entry<Long, BigDecimal> entry : totalRequirements.entrySet()) {
            Long materialId = entry.getKey();
            BigDecimal needed = entry.getValue();
            BigDecimal available = getBestAvailableQuantity(materialId);
            if (available.compareTo(needed) < 0) {
                MaterialEntity mat = materialRepository.findById(materialId).orElse(null);
                String name = mat != null ? mat.getName() : "Material #" + materialId;
                shortages.add(name + ": necesita " + needed + ", disponible " + available);
            }
        }

        if (!shortages.isEmpty()) {
            throw new BusinessException("Material insuficiente:\n• " + String.join("\n• ", shortages));
        }

        // 3. Descontar materiales
        Long userId = securityUtil.getCurrentUserId();
        List<MaterialConsumptionEntity> consumptions = new ArrayList<>();

        for (Map.Entry<Long, BigDecimal> entry : totalRequirements.entrySet()) {
            Long materialId = entry.getKey();
            BigDecimal qty = entry.getValue();
            syncMaterialInventoryIfNeeded(materialId, qty);

            inventoryService.decrementMaterialInventory(
                    materialId, qty, null,
                    "PRODUCTION_ORDER", productionOrderId,
                    order.getCode(),
                    "Consumo automático OP " + order.getCode());

            // Registro de consumo
            Long bomId = bomUsages.containsKey(materialId) && !bomUsages.get(materialId).isEmpty()
                    ? bomUsages.get(materialId).get(0).bomId : null;

            MaterialConsumptionEntity consumption = MaterialConsumptionEntity.builder()
                    .productionOrderId(productionOrderId)
                    .materialId(materialId)
                    .bomId(bomId)
                    .quantityConsumed(qty)
                    .status("CONSUMED")
                    .consumedBy(userId)
                    .notes("Consumo automático al iniciar producción")
                    .build();
            consumptions.add(materialConsumptionRepository.save(consumption));
        }

        // 4. Marcar OP
        order.setMaterialsConsumed(true);
        order.setMaterialsConsumedAt(LocalDateTime.now());
        order.setStatus("IN_PROGRESS");
        productionOrderRepository.save(order);

        return Map.of(
                "message", "Materiales consumidos exitosamente",
                "materialsConsumed", consumptions.size(),
                "productionOrderId", productionOrderId
        );
    }

    /**
     * Obtiene historial de consumos de una OP
     */
    public List<MaterialConsumptionEntity> getConsumptionsByOrder(Long productionOrderId) {
        return materialConsumptionRepository.findByProductionOrderId(productionOrderId);
    }

    /**
     * Valida si hay material suficiente sin descontar (incluye quantity + tallas; desglose por línea y cincho).
     */
    public Map<String, Object> validateMaterialAvailability(Long productionOrderId)
            throws ResourceNotFoundException {
        productionOrderRepository.findById(productionOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("Production Order", productionOrderId));
        List<ProductionOrderItemEntity> items = productionOrderItemRepository
                .findByProductionOrderId(productionOrderId);
        return buildMaterialAvailabilityResult(items);
    }

    /**
     * Valida stock de materiales al iniciar una OP de cinchos gestionada.
     * Las hebillas no bloquean el flujo OPC (se surten aparte).
     */
    public void assertManagedCinchoOrderMaterialsAvailable(Long productionOrderId)
            throws ResourceNotFoundException, BusinessException {
        productionOrderRepository.findById(productionOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("Production Order", productionOrderId));
        List<ProductionOrderItemEntity> items = productionOrderItemRepository
                .findByProductionOrderId(productionOrderId);
        Map<String, Object> result = buildMaterialAvailabilityResult(items);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> materials = (List<Map<String, Object>>) result.get("materials");
        List<String> blockingShortages = new ArrayList<>();
        if (materials != null) {
            for (Map<String, Object> m : materials) {
                if (Boolean.TRUE.equals(m.get("sufficient"))) {
                    continue;
                }
                String materialName = String.valueOf(m.getOrDefault("materialName", ""));
                Long materialId = m.get("materialId") instanceof Number n ? n.longValue() : null;
                MaterialEntity mat = materialId != null ? materialRepository.findById(materialId).orElse(null) : null;
                if (isBuckleMaterial(mat, materialName)) {
                    continue;
                }
                blockingShortages.add(
                        materialName + ": necesita " + m.get("required") + ", disponible " + m.get("available"));
            }
        }
        if (!blockingShortages.isEmpty()) {
            throw new BusinessException("Material insuficiente:\n• " + String.join("\n• ", blockingShortages));
        }
    }

    private static boolean isBuckleMaterial(MaterialEntity mat, String fallbackName) {
        String name = mat != null && mat.getName() != null ? mat.getName() : fallbackName;
        String sku = mat != null && mat.getSku() != null ? mat.getSku() : "";
        String nameNorm = name.toUpperCase(Locale.ROOT);
        String skuNorm = sku.toUpperCase(Locale.ROOT);
        if (nameNorm.contains("HEBILLA")) {
            return true;
        }
        return skuNorm.startsWith("HEB") || skuNorm.startsWith("AJUS");
    }

    /**
     * Resultado de validación (también para respuesta HTTP).
     */
    public Map<String, Object> buildMaterialAvailabilityResult(List<ProductionOrderItemEntity> items) {
        if (items == null) {
            items = List.of();
        }

        Map<Long, BigDecimal> requirements = new LinkedHashMap<>();
        Map<Long, Map<Long, BigDecimal>> neededByItemAndMaterial = new LinkedHashMap<>();

        int itemsWithBom = 0;
        for (ProductionOrderItemEntity item : items) {
            BomEntity bom = resolveBomForOrderItem(item);
            if (bom == null || bom.getItems() == null || bom.getItems().isEmpty()) {
                continue;
            }
            itemsWithBom++;
            int effQty = ProductionOrderItemQuantityHelper.effectiveQuantityForBom(item);
            Map<Long, BigDecimal> perItem = neededByItemAndMaterial.computeIfAbsent(
                    item.getId(), k -> new LinkedHashMap<>());
            for (BomItemEntity bi : bom.getItems()) {
                BigDecimal needed = bi.getQuantity().multiply(BigDecimal.valueOf(effQty));
                requirements.merge(bi.getMaterialId(), needed, BigDecimal::add);
                perItem.merge(bi.getMaterialId(), needed, BigDecimal::add);
            }
        }

        Map<Long, Map<String, Object>> materialDetailById = new LinkedHashMap<>();
        boolean allAvailable = true;
        for (Map.Entry<Long, BigDecimal> entry : requirements.entrySet()) {
            Long materialId = entry.getKey();
            BigDecimal needed = entry.getValue();
            BigDecimal available = getBestAvailableQuantity(materialId);
            MaterialEntity mat = materialRepository.findById(materialId).orElse(null);
            String name = mat != null ? mat.getName() : "Material #" + materialId;
            boolean sufficient = available.compareTo(needed) >= 0;
            if (!sufficient) {
                allAvailable = false;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("materialId", materialId);
            row.put("materialName", name);
            row.put("required", needed);
            row.put("available", available);
            row.put("sufficient", sufficient);
            materialDetailById.put(materialId, row);
        }

        List<Map<String, Object>> materials = new ArrayList<>(materialDetailById.values());

        List<Map<String, Object>> byOrderItem = new ArrayList<>();
        boolean cinchoAllAvailable = true;
        boolean nonCinchoAllAvailable = true;
        boolean anyCinchoWithBom = false;
        boolean anyNonCinchoWithBom = false;

        for (ProductionOrderItemEntity item : items) {
            BomEntity bom = resolveBomForOrderItem(item);
            ProductEntity product = item.getProductId() != null
                    ? productRepository.findById(item.getProductId()).orElse(null)
                    : null;
            boolean isCinchoLine = CinchoProductUtils.isFossCinchoProduct(product);
            String productCode = product != null ? product.getCode() : "";

            if (bom == null || bom.getItems() == null || bom.getItems().isEmpty()) {
                Map<String, Object> emptyRow = new LinkedHashMap<>();
                emptyRow.put("productionOrderItemId", item.getId());
                emptyRow.put("productId", item.getProductId());
                emptyRow.put("productCode", productCode);
                emptyRow.put("isCinchoLine", isCinchoLine);
                emptyRow.put("hasBom", false);
                emptyRow.put("allAvailable", true);
                emptyRow.put("materials", List.of());
                byOrderItem.add(emptyRow);
                continue;
            }

            boolean itemAllAvailable = true;
            List<Map<String, Object>> itemMats = new ArrayList<>();
            Map<Long, BigDecimal> perItem = neededByItemAndMaterial.getOrDefault(item.getId(), Map.of());
            for (BomItemEntity bi : bom.getItems()) {
                Long mid = bi.getMaterialId();
                BigDecimal req = perItem.getOrDefault(mid, BigDecimal.ZERO);
                Map<String, Object> glob = materialDetailById.get(mid);
                boolean suff = glob != null && Boolean.TRUE.equals(glob.get("sufficient"));
                if (!suff) {
                    itemAllAvailable = false;
                }
                Map<String, Object> matRow = new LinkedHashMap<>();
                matRow.put("materialId", mid);
                matRow.put("materialName", glob != null ? glob.get("materialName") : "");
                matRow.put("required", req);
                matRow.put("available", glob != null ? glob.get("available") : BigDecimal.ZERO);
                matRow.put("sufficient", suff);
                itemMats.add(matRow);
            }

            if (isCinchoLine) {
                anyCinchoWithBom = true;
                if (!itemAllAvailable) {
                    cinchoAllAvailable = false;
                }
            } else {
                anyNonCinchoWithBom = true;
                if (!itemAllAvailable) {
                    nonCinchoAllAvailable = false;
                }
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("productionOrderItemId", item.getId());
            row.put("productId", item.getProductId());
            row.put("productCode", productCode);
            row.put("isCinchoLine", isCinchoLine);
            row.put("hasBom", true);
            row.put("allAvailable", itemAllAvailable);
            row.put("materials", itemMats);
            byOrderItem.add(row);
        }

        if (!anyCinchoWithBom) {
            cinchoAllAvailable = true;
        }
        if (!anyNonCinchoWithBom) {
            nonCinchoAllAvailable = true;
        }

        String cinchoShortageMessage = null;
        if (!cinchoAllAvailable) {
            Set<String> cinchoShortageLines = new LinkedHashSet<>();
            for (Map<String, Object> row : byOrderItem) {
                if (!Boolean.TRUE.equals(row.get("isCinchoLine")) || !Boolean.TRUE.equals(row.get("hasBom"))) {
                    continue;
                }
                if (Boolean.TRUE.equals(row.get("allAvailable"))) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> mats = (List<Map<String, Object>>) row.get("materials");
                if (mats == null) {
                    continue;
                }
                for (Map<String, Object> m : mats) {
                    if (!Boolean.FALSE.equals(m.get("sufficient"))) {
                        continue;
                    }
                    cinchoShortageLines.add(
                            m.get("materialName") + ": necesita " + m.get("required") + ", disponible "
                                    + m.get("available"));
                }
            }
            if (!cinchoShortageLines.isEmpty()) {
                cinchoShortageMessage = "Material insuficiente en líneas cincho:\n• "
                        + String.join("\n• ", cinchoShortageLines);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("allAvailable", allAvailable);
        out.put("itemsWithBom", itemsWithBom);
        out.put("totalItems", items.size());
        out.put("materials", materials);
        out.put("byOrderItem", byOrderItem);
        out.put("cinchoAllAvailable", cinchoAllAvailable);
        out.put("nonCinchoAllAvailable", nonCinchoAllAvailable);
        if (cinchoShortageMessage != null) {
            out.put("cinchoShortageMessage", cinchoShortageMessage);
        }
        if (!allAvailable) {
            out.put("shortageMessage", formatMaterialShortageMessage(materials));
        }
        return out;
    }

    private static String formatMaterialShortageMessage(List<Map<String, Object>> materials) {
        String body = materials.stream()
                .filter(m -> !Boolean.TRUE.equals(m.get("sufficient")))
                .map(m -> m.get("materialName") + ": necesita " + m.get("required") + ", disponible " + m.get("available"))
                .collect(Collectors.joining("\n• "));
        if (body.isEmpty()) {
            return "Material insuficiente para la orden.";
        }
        return "Material insuficiente para la orden:\n• " + body;
    }

    private BomEntity resolveBomForOrderItem(ProductionOrderItemEntity item) {
        if (item == null || item.getProductId() == null) {
            return null;
        }
        List<BomEntity> boms = bomRepository.findByProductIdAndStatus(item.getProductId(), "A");
        if (boms.isEmpty()) {
            return null;
        }
        Long colorId = item.getColorId();
        return boms.stream()
                .filter(b -> colorId != null && colorId.equals(b.getColorId()))
                .findFirst()
                .orElseGet(() -> boms.stream()
                        .filter(b -> b.getColorId() == null)
                        .findFirst()
                        .orElseGet(() -> boms.stream().findFirst().orElse(null)));
    }

    /**
     * Valida disponibilidad de materiales para UNA tarea específica.
     */
    public Map<String, Object> validateMaterialAvailabilityForTask(Long taskId)
            throws ResourceNotFoundException {
        TaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task", taskId));

        Map<Long, BigDecimal> requirements = calculateTaskRequirements(task);

        List<Map<String, Object>> details = new ArrayList<>();
        boolean allAvailable = true;

        for (Map.Entry<Long, BigDecimal> entry : requirements.entrySet()) {
            Long materialId = entry.getKey();
            BigDecimal needed = entry.getValue();
            BigDecimal available = getBestAvailableQuantity(materialId);

            MaterialEntity mat = materialRepository.findById(materialId).orElse(null);
            String name = mat != null ? mat.getName() : "Material #" + materialId;

            boolean sufficient = available.compareTo(needed) >= 0;
            if (!sufficient) allAvailable = false;

            details.add(Map.of(
                    "materialId", materialId,
                    "materialName", name,
                    "required", needed,
                    "available", available,
                    "sufficient", sufficient
            ));
        }

        return Map.of(
                "allAvailable", allAvailable,
                "taskId", taskId,
                "materials", details
        );
    }

    /**
     * Consume materiales para UNA tarea específica (no toda la OP).
     */
    @Transactional
    public Map<String, Object> consumeMaterialsForTask(Long taskId)
            throws ResourceNotFoundException, BusinessException {
        return consumeMaterialsForTask(taskId, false);
    }

    @Transactional
    public Map<String, Object> consumeMaterialsForTask(Long taskId, boolean force)
            throws ResourceNotFoundException, BusinessException {
        TaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task", taskId));

        Map<Long, BigDecimal> requirements = calculateTaskRequirements(task);
        if (requirements.isEmpty()) {
            if (!taskRequiresMaterialsConsumption(task)) {
                return Map.of(
                        "message", "Tarea sin materiales de bodega (solo cuero)",
                        "taskId", taskId,
                        "materialsConsumed", 0
                );
            }
            throw new BusinessException("La tarea no tiene receta (BOM) activa para consumir materiales.");
        }

        List<String> shortages = new ArrayList<>();
        for (Map.Entry<Long, BigDecimal> entry : requirements.entrySet()) {
            Long materialId = entry.getKey();
            BigDecimal needed = entry.getValue();
            BigDecimal available = getBestAvailableQuantity(materialId);
            if (available.compareTo(needed) < 0) {
                MaterialEntity mat = materialRepository.findById(materialId).orElse(null);
                String name = mat != null ? mat.getName() : "Material #" + materialId;
                shortages.add(name + ": necesita " + needed + ", disponible " + available);
            }
        }
        if (!shortages.isEmpty() && !force) {
            throw new BusinessException("Material insuficiente para la tarea:\n• " + String.join("\n• ", shortages));
        }
        boolean allowNegative = force && !shortages.isEmpty();
        String noteSuffix = allowNegative ? " | ENTREGA_FORZADA_FALTA_STOCK" : "";

        Long userId = securityUtil.getCurrentUserId();
        int consumedLines = 0;
        for (Map.Entry<Long, BigDecimal> entry : requirements.entrySet()) {
            Long materialId = entry.getKey();
            BigDecimal qty = entry.getValue();
            syncMaterialInventoryIfNeeded(materialId, qty);

            inventoryService.decrementMaterialInventory(
                    materialId, qty, null,
                    "TASK", taskId,
                    task.getCode(),
                    "Consumo por entrega de materiales tarea " + task.getCode() + noteSuffix,
                    allowNegative);

            MaterialConsumptionEntity consumption = MaterialConsumptionEntity.builder()
                    .productionOrderId(task.getProductionOrderId())
                    .materialId(materialId)
                    .quantityConsumed(qty)
                    .status("CONSUMED")
                    .consumedBy(userId)
                    .notes("Consumo por tarea " + task.getCode() + noteSuffix)
                    .build();
            materialConsumptionRepository.save(consumption);
            consumedLines++;
        }

        return Map.of(
                "message", "Materiales consumidos para tarea",
                "taskId", taskId,
                "materialsConsumed", consumedLines
        );
    }

    /**
     * Valida disponibilidad para un item específico dentro de una tarea.
     */
    public Map<String, Object> validateMaterialAvailabilityForTaskItem(Long taskId, Long taskItemId)
            throws ResourceNotFoundException {
        TaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task", taskId));
        TaskItemEntity item = taskItemRepository.findById(taskItemId)
                .orElseThrow(() -> new ResourceNotFoundException("Task Item", taskItemId));

        if (!Objects.equals(item.getTaskId(), task.getId())) {
            throw new ResourceNotFoundException("Task Item", taskItemId);
        }

        Map<Long, BigDecimal> requirements = calculateTaskItemRequirements(item);
        List<Map<String, Object>> details = new ArrayList<>();
        boolean allAvailable = true;

        for (Map.Entry<Long, BigDecimal> entry : requirements.entrySet()) {
            Long materialId = entry.getKey();
            BigDecimal needed = entry.getValue();
            BigDecimal available = getBestAvailableQuantity(materialId);

            MaterialEntity mat = materialRepository.findById(materialId).orElse(null);
            String name = mat != null ? mat.getName() : "Material #" + materialId;

            boolean sufficient = available.compareTo(needed) >= 0;
            if (!sufficient) allAvailable = false;

            details.add(Map.of(
                    "materialId", materialId,
                    "materialName", name,
                    "required", needed,
                    "available", available,
                    "sufficient", sufficient
            ));
        }

        return Map.of(
                "allAvailable", allAvailable,
                "taskId", taskId,
                "taskItemId", taskItemId,
                "materials", details
        );
    }

    /**
     * Consume materiales para un item específico de tarea.
     */
    @Transactional
    public Map<String, Object> consumeMaterialsForTaskItem(Long taskId, Long taskItemId)
            throws ResourceNotFoundException, BusinessException {
        return consumeMaterialsForTaskItem(taskId, taskItemId, false);
    }

    @Transactional
    public Map<String, Object> consumeMaterialsForTaskItem(Long taskId, Long taskItemId, boolean force)
            throws ResourceNotFoundException, BusinessException {
        TaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task", taskId));
        TaskItemEntity item = taskItemRepository.findById(taskItemId)
                .orElseThrow(() -> new ResourceNotFoundException("Task Item", taskItemId));

        if (!Objects.equals(item.getTaskId(), task.getId())) {
            throw new ResourceNotFoundException("Task Item", taskItemId);
        }

        if (!productRequiresMaterials(item.getProductId())) {
            return Map.of(
                    "message", "Producto solo cuero: sin consumo de materiales de bodega",
                    "taskId", taskId,
                    "taskItemId", taskItemId,
                    "materialsConsumed", 0
            );
        }

        Map<Long, BigDecimal> requirements = calculateTaskItemRequirements(item);
        if (requirements.isEmpty()) {
            throw new BusinessException("Este producto no tiene receta (BOM) activa para consumir materiales.");
        }

        List<String> shortages = new ArrayList<>();
        for (Map.Entry<Long, BigDecimal> entry : requirements.entrySet()) {
            Long materialId = entry.getKey();
            BigDecimal needed = entry.getValue();
            BigDecimal available = getBestAvailableQuantity(materialId);
            if (available.compareTo(needed) < 0) {
                MaterialEntity mat = materialRepository.findById(materialId).orElse(null);
                String name = mat != null ? mat.getName() : "Material #" + materialId;
                shortages.add(name + ": necesita " + needed + ", disponible " + available);
            }
        }
        if (!shortages.isEmpty() && !force) {
            throw new BusinessException("Material insuficiente para el producto:\n• " + String.join("\n• ", shortages));
        }
        boolean allowNegative = force && !shortages.isEmpty();
        String noteSuffix = allowNegative ? " | ENTREGA_FORZADA_FALTA_STOCK" : "";

        Long userId = securityUtil.getCurrentUserId();
        int consumedLines = 0;
        for (Map.Entry<Long, BigDecimal> entry : requirements.entrySet()) {
            Long materialId = entry.getKey();
            BigDecimal qty = entry.getValue();
            syncMaterialInventoryIfNeeded(materialId, qty);

            inventoryService.decrementMaterialInventory(
                    materialId, qty, null,
                    "TASK_ITEM", taskItemId,
                    task.getCode(),
                    "Consumo por entrega de materiales item " + taskItemId + " de tarea " + task.getCode() + noteSuffix,
                    allowNegative);

            MaterialConsumptionEntity consumption = MaterialConsumptionEntity.builder()
                    .productionOrderId(task.getProductionOrderId())
                    .materialId(materialId)
                    .quantityConsumed(qty)
                    .status("CONSUMED")
                    .consumedBy(userId)
                    .notes("Consumo por item " + taskItemId + " de tarea " + task.getCode() + noteSuffix)
                    .build();
            materialConsumptionRepository.save(consumption);
            consumedLines++;
        }

        return Map.of(
                "message", "Materiales consumidos para item de tarea",
                "taskId", taskId,
                "taskItemId", taskItemId,
                "materialsConsumed", consumedLines
        );
    }

    /**
     * True si ya hay consumos registrados para este ítem de tarea (notas de entrega por producto).
     */
    public boolean hasConsumptionForTaskItem(Long productionOrderId, Long taskItemId) {
        if (productionOrderId == null || taskItemId == null) {
            return false;
        }
        String pattern = "%item " + taskItemId + " %";
        return materialConsumptionRepository.existsByProductionOrderIdAndNotesLike(productionOrderId, pattern);
    }

    private Map<Long, BigDecimal> calculateTaskRequirements(TaskEntity task) {
        Map<Long, BigDecimal> requirements = new LinkedHashMap<>();

        List<TaskItemEntity> taskItems = taskItemRepository.findByTaskId(task.getId());
        if (!taskItems.isEmpty()) {
            for (TaskItemEntity item : taskItems) {
                if (item.getProductId() == null || !productRequiresMaterials(item.getProductId())) continue;
                int qty = item.getQuantity() != null ? item.getQuantity() : 0;
                accumulateRequirementsForProduct(requirements, item.getProductId(), item.getColorId(), qty);
            }
            return requirements;
        }

        // Legacy fallback: single-product task
        if (task.getProductId() != null && productRequiresMaterials(task.getProductId())) {
            int qty = task.getQuantity() != null ? task.getQuantity() : 0;
            accumulateRequirementsForProduct(requirements, task.getProductId(), task.getColorId(), qty);
        }
        return requirements;
    }

    private boolean taskRequiresMaterialsConsumption(TaskEntity task) {
        List<TaskItemEntity> taskItems = taskItemRepository.findByTaskId(task.getId());
        if (!taskItems.isEmpty()) {
            return taskItems.stream().anyMatch(i -> productRequiresMaterials(i.getProductId()));
        }
        return productRequiresMaterials(task.getProductId());
    }

    private Map<Long, BigDecimal> calculateTaskItemRequirements(TaskItemEntity item) {
        Map<Long, BigDecimal> requirements = new LinkedHashMap<>();
        if (item == null || item.getProductId() == null || !productRequiresMaterials(item.getProductId())) {
            return requirements;
        }
        int qty = resolveQuantityForTaskItemBom(item);
        accumulateRequirementsForProduct(requirements, item.getProductId(), item.getColorId(), qty);
        return requirements;
    }

    private int resolveQuantityForTaskItemBom(TaskItemEntity item) {
        if (item.getProductionOrderItemId() != null) {
            return productionOrderItemRepository.findById(item.getProductionOrderItemId())
                    .map(ProductionOrderItemQuantityHelper::effectiveQuantityForBom)
                    .orElse(item.getQuantity() != null ? item.getQuantity() : 1);
        }
        int qty = item.getQuantity() != null ? item.getQuantity() : 0;
        return qty > 0 ? qty : 1;
    }

    public boolean productRequiresMaterials(Long productId) {
        if (productId == null) return true;
        ProductEntity product = productRepository.findById(productId).orElse(null);
        if (product == null) return true;
        return !Boolean.FALSE.equals(product.getRequiresMaterials());
    }

    private void accumulateRequirementsForProduct(
            Map<Long, BigDecimal> requirements,
            Long productId,
            Long colorId,
            int quantity) {
        if (productId == null || quantity <= 0 || !productRequiresMaterials(productId)) return;
        List<BomEntity> boms = bomRepository.findByProductIdAndStatus(productId, "A");
        BomEntity bom = boms.stream()
                .filter(b -> colorId != null && colorId.equals(b.getColorId()))
                .findFirst()
                .orElse(boms.stream()
                        .filter(b -> b.getColorId() == null)
                        .findFirst()
                        .orElse(boms.stream().findFirst().orElse(null)));
        if (bom == null || bom.getItems() == null) return;

        for (BomItemEntity bi : bom.getItems()) {
            BigDecimal needed = bi.getQuantity().multiply(BigDecimal.valueOf(quantity));
            requirements.merge(bi.getMaterialId(), needed, BigDecimal::add);
        }
    }

    private BigDecimal getBestAvailableQuantity(Long materialId) {
        BigDecimal inventoryQty = BigDecimal.ZERO;
        try {
            inventoryQty = inventoryService.getMaterialInventory(materialId).getTotalQuantity();
        } catch (ResourceNotFoundException ignored) {}

        BigDecimal materialQty = materialRepository.findById(materialId)
                .map(m -> m.getQuantity() != null ? m.getQuantity() : BigDecimal.ZERO)
                .orElse(BigDecimal.ZERO);

        // Use the highest known quantity to tolerate legacy desync between material and material_inventory.
        return inventoryQty.max(materialQty);
    }

    private void syncMaterialInventoryIfNeeded(Long materialId, BigDecimal requiredQty) throws ResourceNotFoundException {
        BigDecimal inventoryQty = BigDecimal.ZERO;
        boolean hasInventoryRecord = true;
        try {
            inventoryQty = inventoryService.getMaterialInventory(materialId).getTotalQuantity();
        } catch (ResourceNotFoundException ignored) {
            hasInventoryRecord = false;
        }

        BigDecimal materialQty = materialRepository.findById(materialId)
                .map(m -> m.getQuantity() != null ? m.getQuantity() : BigDecimal.ZERO)
                .orElse(BigDecimal.ZERO);

        BigDecimal targetQty = inventoryQty.max(materialQty);
        if (!hasInventoryRecord && targetQty.compareTo(BigDecimal.ZERO) > 0) {
            inventoryService.incrementMaterialInventory(
                    materialId,
                    targetQty,
                    null,
                    "MATERIAL_SYNC",
                    materialId,
                    "SYNC-" + materialId,
                    "Sincronizacion inicial inventario material");
            return;
        }

        if (hasInventoryRecord && targetQty.compareTo(inventoryQty) > 0) {
            BigDecimal diff = targetQty.subtract(inventoryQty);
            inventoryService.incrementMaterialInventory(
                    materialId,
                    diff,
                    null,
                    "MATERIAL_SYNC",
                    materialId,
                    "SYNC-" + materialId,
                    "Sincronizacion por desalineacion historica");
        }
    }

    private record BomUsage(Long bomId, Long productId, Long materialId, BigDecimal quantity) {}
}

