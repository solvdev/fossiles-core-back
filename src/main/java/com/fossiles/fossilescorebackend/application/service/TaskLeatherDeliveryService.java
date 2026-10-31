package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LeatherInventoryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LeatherMovementEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.TaskItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LeatherInventoryRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LeatherMovementRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.SystemConfigRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.TaskRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Entrega de cuero de los productos de una tarea. Es el único camino para marcarlo: el clic por producto,
 * la entrega de toda la tarea y «Cortar todo» pasan por aquí.
 *
 * <p>Con el interruptor {@value #CONFIG_PER_PRODUCT} apagado solo se mueven las banderas, igual que antes.
 * Encendido, marcar un producto descuenta su receta del kardex de cuero con una salida ligada al producto
 * (y a la OP de su línea, no a la de la tarea, por las consolidadas); desmarcarlo la anula y el cuero vuelve.
 */
@Service
@RequiredArgsConstructor
public class TaskLeatherDeliveryService {

    public static final String CONFIG_PER_PRODUCT = "produccion.cuero.kardex_por_producto";
    private static final String SALIDA = "SALIDA";
    private static final String ANULADO = "ANULADO";

    private final SystemConfigRepository systemConfigRepository;
    private final LeatherMovementRepository movementRepository;
    private final LeatherInventoryRepository inventoryRepository;
    private final ProductRepository productRepository;
    private final ProductionOrderItemRepository productionOrderItemRepository;
    private final TaskRepository taskRepository;
    private final TaskItemRepository taskItemRepository;
    private final LeatherRequirementService leatherRequirementService;
    private final SecurityUtil securityUtil;

    public boolean perProductEnabled() {
        return systemConfigRepository.findByConfigKey(CONFIG_PER_PRODUCT)
                .map(config -> "true".equalsIgnoreCase(String.valueOf(config.getConfigValue()).trim()))
                .orElse(false);
    }

    /** Marca el cuero del producto como entregado; con el interruptor, primero lo descuenta del kardex. */
    @Transactional(rollbackFor = Exception.class)
    public void deliver(TaskEntity task, TaskItemEntity item) throws BusinessException {
        if (perProductEnabled()) {
            discount(task, item);
        }
        if (!Boolean.TRUE.equals(item.getLeatherDelivered())) {
            item.setLeatherDelivered(true);
            item.setLeatherDeliveredAt(GuatemalaDateTime.now());
            taskItemRepository.save(item);
        }
    }

    /**
     * Desmarca el cuero del producto. Si tiene salida propia en el kardex se anula y el cuero vuelve al
     * inventario, aunque el interruptor se haya apagado después de entregarlo.
     */
    @Transactional(rollbackFor = Exception.class)
    public void revert(TaskEntity task, TaskItemEntity item) throws BusinessException {
        var active = movementRepository.findFirstByTaskItemIdAndMovementType(item.getId(), SALIDA);
        if (active.isPresent()) {
            reverse(active.get(), "Desmarcado en la tarea " + task.getCode());
        }
        item.setLeatherDelivered(false);
        item.setLeatherDeliveredAt(null);
        taskItemRepository.save(item);
    }

    private void discount(TaskEntity task, TaskItemEntity item) throws BusinessException {
        // Ya entregado (con salida propia o con la bandera de antes del interruptor): no se descuenta otra vez.
        if (Boolean.TRUE.equals(item.getLeatherDelivered())
                || movementRepository.findFirstByTaskItemIdAndMovementType(item.getId(), SALIDA).isPresent()) {
            return;
        }
        var need = needOf(item);
        if (need == null || need.noneRequired()) {
            return;
        }
        if (need.blocked()) {
            throw new BusinessException(need.reason() + " (" + describe(item) + "). Complete la receta o registre "
                    + "la entrega de cuero por orden en el kardex.");
        }
        Long orderId = lineOrderId(task, item);
        // La bodega ya entregó ese cuero a la OP desde el kardex: el producto queda cubierto sin otra salida.
        if (orderId != null && hasOrderDelivery(orderId, need.materialId())) {
            return;
        }
        LeatherInventoryEntity inventory = inventoryRepository.findByMaterialIdForUpdate(need.materialId())
                .orElseThrow(() -> new BusinessException("No hay inventario del cuero que pide la receta de "
                        + describe(item) + "."));
        if (inventory.getQuantityAvailable().compareTo(need.qtyFt2()) < 0) {
            throw new BusinessException("Cuero insuficiente para " + describe(item) + ": se necesitan "
                    + need.qtyFt2() + " ft² y hay " + inventory.getQuantityAvailable() + " ft² en el kardex.");
        }
        inventory.setQuantityAvailable(inventory.getQuantityAvailable().subtract(need.qtyFt2()));
        inventory.setTotalDelivered(inventory.getTotalDelivered().add(need.qtyFt2()));
        inventoryRepository.save(inventory);
        movementRepository.save(LeatherMovementEntity.builder()
                .movementType(SALIDA)
                .materialId(need.materialId())
                .quantity(need.qtyFt2())
                .movementDate(GuatemalaDateTime.today())
                .productionOrderId(orderId)
                .taskItemId(item.getId())
                .observations("Entrega por producto: tarea " + task.getCode() + ", " + describe(item)
                        + " (" + nz(item.getQuantity()) + " u.)")
                .balanceAfter(inventory.getQuantityAvailable())
                .createdBy(securityUtil.getCurrentUserId())
                .build());
    }

    private void reverse(LeatherMovementEntity active, String reason) throws BusinessException {
        LeatherMovementEntity original = movementRepository.findByIdForUpdate(active.getId()).orElse(null);
        if (original == null || !SALIDA.equals(original.getMovementType())) {
            return;
        }
        LeatherInventoryEntity inventory = inventoryRepository.findByMaterialIdForUpdate(original.getMaterialId())
                .orElseThrow(() -> new BusinessException("No hay inventario para devolver el cuero."));
        inventory.setQuantityAvailable(inventory.getQuantityAvailable().add(original.getQuantity()));
        inventory.setTotalDelivered(inventory.getTotalDelivered().subtract(original.getQuantity()));
        inventoryRepository.save(inventory);
        original.setMovementType(ANULADO);
        original.setObservations(append(original.getObservations(), "ANULADO: " + reason));
        movementRepository.saveAndFlush(original);
        movementRepository.save(LeatherMovementEntity.builder()
                .movementType(ANULADO)
                .materialId(original.getMaterialId())
                .quantity(original.getQuantity())
                .movementDate(GuatemalaDateTime.today())
                .productionOrderId(original.getProductionOrderId())
                .taskItemId(original.getTaskItemId())
                .observations("ANULACIÓN de movimiento #" + original.getId() + " — " + reason)
                .balanceAfter(inventory.getQuantityAvailable())
                .createdBy(securityUtil.getCurrentUserId())
                .build());
    }

    // ─── Entrega por orden desde el kardex (solo con el interruptor) ──────────

    /**
     * Antes de registrar una entrega por orden: si ya salió cuero de ese material por producto para la OP,
     * la entrega no puede pasar de lo que les falta a los productos aún sin cuero.
     */
    public void assertOrderDeliveryFits(Long orderId, Long materialId, BigDecimal quantity) throws BusinessException {
        if (orderId == null || materialId == null || quantity == null) {
            return;
        }
        boolean byProduct = movementRepository.findByProductionOrderIdOrderByCreatedAtDesc(orderId).stream()
                .anyMatch(m -> SALIDA.equals(m.getMovementType()) && m.getTaskItemId() != null
                        && materialId.equals(m.getMaterialId()));
        if (!byProduct) {
            return;
        }
        BigDecimal pending = BigDecimal.ZERO;
        for (TaskItemEntity item : orderItems(orderId)) {
            if (Boolean.TRUE.equals(item.getLeatherDelivered())) continue;
            var need = needOf(item);
            if (need != null && !need.blocked() && materialId.equals(need.materialId())) {
                pending = pending.add(need.qtyFt2());
            }
        }
        if (quantity.compareTo(pending) > 0) {
            throw new BusinessException("Parte de este cuero ya salió por producto para la orden; a los productos "
                    + "que siguen sin cuero les faltan " + pending + " ft². Registre como máximo esa cantidad.");
        }
    }

    /**
     * Tras una entrega por orden abre los productos de esa OP (estén en la tarea que estén) que usan ese cuero,
     * los que no necesitan cuero y los que no tienen receta, que de otro modo no tendrían cómo abrirse.
     */
    @Transactional(rollbackFor = Exception.class)
    public void openOrderItems(Long orderId, Long materialId) {
        if (orderId == null) {
            return;
        }
        Map<Long, TaskEntity> touched = new LinkedHashMap<>();
        for (TaskItemEntity item : orderItems(orderId)) {
            if (Boolean.TRUE.equals(item.getLeatherDelivered())) continue;
            TaskEntity task = touched.computeIfAbsent(item.getTaskId(),
                    id -> taskRepository.findById(id).orElse(null));
            if (task == null || "CANCELLED".equals(task.getStatus())) continue;
            var need = needOf(item);
            boolean otherLeather = need != null && !need.blocked() && !need.noneRequired()
                    && !Objects.equals(need.materialId(), materialId);
            if (otherLeather) continue;
            item.setLeatherDelivered(true);
            item.setLeatherDeliveredAt(GuatemalaDateTime.now());
            taskItemRepository.save(item);
        }
        for (TaskEntity task : touched.values()) {
            if (task != null) refreshTaskFlag(task, false);
        }
    }

    // ─── Anulación desde el kardex de una salida por producto ─────────────────

    /** Una salida por producto solo se anula mientras ese producto no se haya troquelado ni empezado. */
    public void assertCanCancel(LeatherMovementEntity movement) throws BusinessException {
        if (movement.getTaskItemId() == null || !SALIDA.equals(movement.getMovementType())) {
            return;
        }
        TaskItemEntity item = taskItemRepository.findById(movement.getTaskItemId()).orElse(null);
        TaskEntity task = item == null ? null : taskRepository.findById(item.getTaskId()).orElse(null);
        if (item == null || task == null) {
            return;
        }
        if (Boolean.TRUE.equals(item.getDieCutReady()) || Boolean.TRUE.equals(task.getMaterialsDelivered())
                || "IN_PROGRESS".equals(task.getStatus()) || "COMPLETED".equals(task.getStatus())) {
            throw new BusinessException("No se puede anular: " + describe(item) + " de la tarea " + task.getCode()
                    + " ya se troqueló o avanzó en producción.");
        }
    }

    /** Tras anular una salida por producto, el producto vuelve a quedar sin cuero. */
    @Transactional(rollbackFor = Exception.class)
    public void afterCancel(LeatherMovementEntity movement) {
        if (movement.getTaskItemId() == null) {
            return;
        }
        taskItemRepository.findById(movement.getTaskItemId()).ifPresent(item -> {
            item.setLeatherDelivered(false);
            item.setLeatherDeliveredAt(null);
            taskItemRepository.save(item);
            taskRepository.findById(item.getTaskId()).ifPresent(task -> refreshTaskFlag(task, true));
        });
    }

    /** Productos con salida de cuero activa: borrarlos dejaría ese cuero fuera del inventario sin dueño. */
    public boolean hasActiveDeliveries(Collection<Long> taskItemIds) {
        return taskItemIds != null && !taskItemIds.isEmpty()
                && movementRepository.existsByTaskItemIdInAndMovementType(taskItemIds, SALIDA);
    }

    // ─── Apoyo ────────────────────────────────────────────────────────────────

    /** Líneas propias de la OP en cualquier tarea, más los productos sin línea de las tareas de esa OP. */
    private List<TaskItemEntity> orderItems(Long orderId) {
        List<TaskItemEntity> result = new ArrayList<>(taskItemRepository.findBaseLineItemsByProductionOrderId(orderId));
        for (TaskEntity task : taskRepository.findByProductionOrderId(orderId)) {
            for (TaskItemEntity item : taskItemRepository.findByTaskId(task.getId())) {
                if (item.getProductionOrderItemId() == null && !Boolean.TRUE.equals(item.getDaySaleExtra())) {
                    result.add(item);
                }
            }
        }
        return result;
    }

    private LeatherRequirementService.LeatherNeed needOf(TaskItemEntity item) {
        if (item.getProductId() == null) {
            return null;
        }
        var product = productRepository.findById(item.getProductId()).orElse(null);
        return product == null ? null : leatherRequirementService.resolveNeed(product, item.getColorId(), nz(item.getQuantity()));
    }

    private Long lineOrderId(TaskEntity task, TaskItemEntity item) {
        if (item.getProductionOrderItemId() != null) {
            var line = productionOrderItemRepository.findById(item.getProductionOrderItemId()).orElse(null);
            if (line != null) {
                return line.getProductionOrderId();
            }
        }
        return task.getProductionOrderId();
    }

    private boolean hasOrderDelivery(Long orderId, Long materialId) {
        return movementRepository.findByProductionOrderIdOrderByCreatedAtDesc(orderId).stream()
                .anyMatch(m -> SALIDA.equals(m.getMovementType()) && m.getTaskItemId() == null
                        && Objects.equals(m.getMaterialId(), materialId));
    }

    /** La bandera de la tarea es el Y de sus productos; abrir productos nunca la baja, anular sí. */
    private void refreshTaskFlag(TaskEntity task, boolean allowDowngrade) {
        List<TaskItemEntity> items = taskItemRepository.findByTaskId(task.getId());
        if (items.isEmpty()) {
            return;
        }
        boolean all = items.stream().allMatch(i -> Boolean.TRUE.equals(i.getLeatherDelivered()));
        if (all != Boolean.TRUE.equals(task.getLeatherDelivered()) && (all || allowDowngrade)) {
            task.setLeatherDelivered(all);
            task.setLeatherDeliveredAt(all ? GuatemalaDateTime.now() : null);
            taskRepository.save(task);
        }
    }

    private static String describe(TaskItemEntity item) {
        String code = item.getProductCode() == null ? "" : item.getProductCode().trim();
        String color = item.getColorName() == null ? "" : item.getColorName().trim();
        String label = code.isEmpty() ? "el producto" : code;
        return color.isEmpty() ? label : label + " " + color;
    }

    private static String append(String base, String extra) {
        return base == null || base.isBlank() ? extra : base + " | " + extra;
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
