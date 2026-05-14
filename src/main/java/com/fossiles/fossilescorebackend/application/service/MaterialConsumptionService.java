package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.*;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

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
            List<BomEntity> boms = bomRepository.findByProductIdAndStatus(item.getProductId(), "A");
            BomEntity bom = boms.stream().findFirst().orElse(null);

            if (bom == null || bom.getItems() == null || bom.getItems().isEmpty()) continue;

            for (BomItemEntity bi : bom.getItems()) {
                BigDecimal needed = bi.getQuantity().multiply(BigDecimal.valueOf(item.getQuantity()));
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
     * Valida si hay material suficiente sin descontar
     */
    public Map<String, Object> validateMaterialAvailability(Long productionOrderId)
            throws ResourceNotFoundException {

        List<ProductionOrderItemEntity> items = productionOrderItemRepository
                .findByProductionOrderId(productionOrderId);

        Map<Long, BigDecimal> requirements = new LinkedHashMap<>();
        Map<Long, String> materialNames = new HashMap<>();
        int itemsWithBom = 0;

        for (ProductionOrderItemEntity item : items) {
            List<BomEntity> boms = bomRepository.findByProductIdAndStatus(item.getProductId(), "A");
            BomEntity bom = boms.stream().findFirst().orElse(null);
            if (bom == null || bom.getItems() == null) continue;
            itemsWithBom++;

            for (BomItemEntity bi : bom.getItems()) {
                BigDecimal needed = bi.getQuantity().multiply(BigDecimal.valueOf(item.getQuantity()));
                requirements.merge(bi.getMaterialId(), needed, BigDecimal::add);
            }
        }

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
                "itemsWithBom", itemsWithBom,
                "totalItems", items.size(),
                "materials", details
        );
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

    private Map<Long, BigDecimal> calculateTaskRequirements(TaskEntity task) {
        Map<Long, BigDecimal> requirements = new LinkedHashMap<>();

        List<TaskItemEntity> taskItems = taskItemRepository.findByTaskId(task.getId());
        if (!taskItems.isEmpty()) {
            for (TaskItemEntity item : taskItems) {
                if (item.getProductId() == null) continue;
                int qty = item.getQuantity() != null ? item.getQuantity() : 0;
                accumulateRequirementsForProduct(requirements, item.getProductId(), item.getColorId(), qty);
            }
            return requirements;
        }

        // Legacy fallback: single-product task
        if (task.getProductId() != null) {
            int qty = task.getQuantity() != null ? task.getQuantity() : 0;
            accumulateRequirementsForProduct(requirements, task.getProductId(), task.getColorId(), qty);
        }
        return requirements;
    }

    private Map<Long, BigDecimal> calculateTaskItemRequirements(TaskItemEntity item) {
        Map<Long, BigDecimal> requirements = new LinkedHashMap<>();
        if (item == null || item.getProductId() == null) {
            return requirements;
        }
        int qty = item.getQuantity() != null ? item.getQuantity() : 0;
        accumulateRequirementsForProduct(requirements, item.getProductId(), item.getColorId(), qty);
        return requirements;
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
        if (productId == null || quantity <= 0) return;
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

