package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.LeatherMovementRequest;
import com.fossiles.fossilescorebackend.application.dto.response.LeatherInventoryResponse;
import com.fossiles.fossilescorebackend.application.dto.response.LeatherMovementResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.*;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class LeatherInventoryService {

    private final LeatherInventoryRepository inventoryRepository;
    private final LeatherMovementRepository movementRepository;
    private final MaterialRepository materialRepository;
    private final SupplierRepository supplierRepository;
    private final ProductionOrderRepository productionOrderRepository;
    private final ProductRepository productRepository;
    private final TaskRepository taskRepository;
    private final SecurityUtil securityUtil;
    private final ProductionAutoPlannerService productionAutoPlannerService;

    // ─── Inventario ──────────────────────────────────────────────────

    public List<LeatherInventoryResponse> getAllInventory() {
        return inventoryRepository.findAll().stream()
                .map(this::toInventoryResponse)
                .toList();
    }

    public LeatherInventoryResponse getInventoryByMaterial(Long materialId) throws ResourceNotFoundException {
        LeatherInventoryEntity inv = inventoryRepository.findByMaterialId(materialId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No hay inventario de cuero para material id: " + materialId));
        return toInventoryResponse(inv);
    }

    // ─── Movimientos ─────────────────────────────────────────────────

    public List<LeatherMovementResponse> getAllMovements() {
        return movementRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toMovementResponse)
                .toList();
    }

    public List<LeatherMovementResponse> getMovementsByMaterial(Long materialId) {
        return movementRepository.findByMaterialIdOrderByCreatedAtDesc(materialId).stream()
                .map(this::toMovementResponse)
                .toList();
    }

    public List<LeatherMovementResponse> getMovementsByDateRange(LocalDate from, LocalDate to) {
        return movementRepository.findByMovementDateBetweenOrderByCreatedAtDesc(from, to).stream()
                .map(this::toMovementResponse)
                .toList();
    }

    /**
     * Kardex: movimientos de un material en un rango de fechas, ordenados cronológicamente.
     */
    public List<LeatherMovementResponse> getKardex(Long materialId, LocalDate from, LocalDate to) {
        return movementRepository.findByMaterialIdAndMovementDateBetweenOrderByMovementDateAscCreatedAtAsc(
                materialId, from, to).stream()
                .map(this::toMovementResponse)
                .toList();
    }

    public List<LeatherMovementResponse> getMovementsByProductionOrder(Long productionOrderId) {
        return movementRepository.findByProductionOrderIdOrderByCreatedAtDesc(productionOrderId).stream()
                .map(this::toMovementResponse)
                .toList();
    }

    // ─── Inicializar inventario faltante ────────────────────────────

    @Transactional
    public int initializeMissingLeatherInventory() {
        List<MaterialEntity> allMaterials = materialRepository.findAll();
        int created = 0;
        for (MaterialEntity mat : allMaterials) {
            String n = (mat.getName() != null ? mat.getName() : "").toLowerCase();
            String s = (mat.getSku() != null ? mat.getSku() : "").toLowerCase();
            boolean isLeather = n.contains("cuero") || n.contains("piel") || n.contains("leather")
                    || s.contains("cue") || s.contains("piel");
            if (!isLeather) continue;

            boolean exists = inventoryRepository.findByMaterialId(mat.getId()).isPresent();
            if (!exists) {
                LeatherInventoryEntity inv = new LeatherInventoryEntity();
                inv.setMaterialId(mat.getId());
                inventoryRepository.save(inv);
                created++;
            }
        }
        return created;
    }

    // ─── Recepción (ENTRADA) ─────────────────────────────────────────

    @Transactional
    public LeatherMovementResponse createReception(LeatherMovementRequest req) throws BusinessException, ResourceNotFoundException {
        validateReception(req);

        MaterialEntity material = materialRepository.findById(req.getMaterialId())
                .orElseThrow(() -> new ResourceNotFoundException("Material no encontrado: " + req.getMaterialId()));

        // Obtener o crear inventario
        LeatherInventoryEntity inventory = inventoryRepository.findByMaterialId(req.getMaterialId())
                .orElseGet(() -> {
                    LeatherInventoryEntity newInv = new LeatherInventoryEntity();
                    newInv.setMaterialId(req.getMaterialId());
                    return newInv;
                });

        // Actualizar inventario
        inventory.setQuantityAvailable(inventory.getQuantityAvailable().add(req.getQuantity()));
        inventory.setTotalReceived(inventory.getTotalReceived().add(req.getQuantity()));
        inventoryRepository.save(inventory);

        // Crear movimiento
        LeatherMovementEntity movement = LeatherMovementEntity.builder()
                .movementType("ENTRADA")
                .materialId(req.getMaterialId())
                .quantity(req.getQuantity())
                .unitCost(req.getUnitCost())
                .movementDate(req.getMovementDate() != null ? req.getMovementDate() : LocalDate.now())
                .supplierId(req.getSupplierId())
                .purchaseDocument(req.getPurchaseDocument())
                .deliveredBy(req.getDeliveredBy())
                .receivedBy(req.getReceivedBy())
                .observations(req.getObservations())
                .balanceAfter(inventory.getQuantityAvailable())
                .createdBy(securityUtil.getCurrentUserId())
                .build();

        return toMovementResponse(movementRepository.save(movement));
    }

    // ─── Entrega a Producción (SALIDA) ───────────────────────────────

    @Transactional
    public LeatherMovementResponse createDelivery(LeatherMovementRequest req) throws BusinessException, ResourceNotFoundException {
        validateDelivery(req);

        MaterialEntity material = materialRepository.findById(req.getMaterialId())
                .orElseThrow(() -> new ResourceNotFoundException("Material no encontrado: " + req.getMaterialId()));

        LeatherInventoryEntity inventory = inventoryRepository.findByMaterialId(req.getMaterialId())
                .orElseThrow(() -> new BusinessException(
                        "No hay inventario de cuero para: " + material.getName()));

        // Validar stock suficiente
        if (inventory.getQuantityAvailable().compareTo(req.getQuantity()) < 0) {
            throw new BusinessException(
                    "Stock insuficiente de " + material.getName() +
                    ". Disponible: " + inventory.getQuantityAvailable() +
                    ", Solicitado: " + req.getQuantity());
        }

        // Validar que la orden de producción existe
        if (req.getProductionOrderId() != null) {
            productionOrderRepository.findById(req.getProductionOrderId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Orden de producción no encontrada: " + req.getProductionOrderId()));
        }

        // Actualizar inventario
        inventory.setQuantityAvailable(inventory.getQuantityAvailable().subtract(req.getQuantity()));
        inventory.setTotalDelivered(inventory.getTotalDelivered().add(req.getQuantity()));
        inventoryRepository.save(inventory);

        // Crear movimiento
        LeatherMovementEntity movement = LeatherMovementEntity.builder()
                .movementType("SALIDA")
                .materialId(req.getMaterialId())
                .quantity(req.getQuantity())
                .movementDate(req.getMovementDate() != null ? req.getMovementDate() : LocalDate.now())
                .productionOrderId(req.getProductionOrderId())
                .deliveredBy(req.getDeliveredBy())
                .receivedBy(req.getReceivedBy())
                .observations(buildDeliveryObservations(req.getObservations(), req.getDeliveryProducts(), req.getProductionOrderId()))
                .balanceAfter(inventory.getQuantityAvailable())
                .createdBy(securityUtil.getCurrentUserId())
                .build();

        LeatherMovementEntity savedMovement = movementRepository.save(movement);

        // Sync workflow gate: once leather is delivered for the PO, mark all pending tasks as leather delivered.
        markLeatherDeliveredForProductionOrder(req.getProductionOrderId());
        productionAutoPlannerService.planAllQuietly();

        return toMovementResponse(savedMovement);
    }

    // ─── Editar movimiento (solo datos descriptivos) ───────────────

    @Transactional
    public LeatherMovementResponse updateMovement(Long id, LeatherMovementRequest req) throws ResourceNotFoundException, BusinessException {
        LeatherMovementEntity mov = movementRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Movimiento no encontrado: " + id));

        if ("ANULADO".equals(mov.getMovementType())) {
            throw new BusinessException("No se puede editar un movimiento anulado.");
        }

        // Solo se actualizan campos descriptivos, NO cantidad ni material
        if (req.getDeliveredBy() != null) mov.setDeliveredBy(req.getDeliveredBy());
        if (req.getReceivedBy() != null) mov.setReceivedBy(req.getReceivedBy());
        if (req.getObservations() != null) mov.setObservations(req.getObservations());
        if (req.getPurchaseDocument() != null) mov.setPurchaseDocument(req.getPurchaseDocument());
        if (req.getSupplierId() != null) mov.setSupplierId(req.getSupplierId());
        if (req.getMovementDate() != null) mov.setMovementDate(req.getMovementDate());

        return toMovementResponse(movementRepository.save(mov));
    }

    // ─── Anular movimiento (reversa) ─────────────────────────────

    @Transactional
    public LeatherMovementResponse cancelMovement(Long id, String reason) throws ResourceNotFoundException, BusinessException {
        LeatherMovementEntity original = movementRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Movimiento no encontrado: " + id));

        if ("ANULADO".equals(original.getMovementType())) {
            throw new BusinessException("Este movimiento ya fue anulado.");
        }

        LeatherInventoryEntity inventory = inventoryRepository.findByMaterialId(original.getMaterialId())
                .orElseThrow(() -> new BusinessException("No se encontró inventario para este material."));

        // Revertir el efecto en inventario
        if ("ENTRADA".equals(original.getMovementType())) {
            // La entrada sumó al inventario → restar
            if (inventory.getQuantityAvailable().compareTo(original.getQuantity()) < 0) {
                throw new BusinessException(
                        "No se puede anular: el stock disponible (" + inventory.getQuantityAvailable() +
                        ") es menor a la cantidad de la entrada (" + original.getQuantity() +
                        "). Ya se entregó parte de este cuero.");
            }
            inventory.setQuantityAvailable(inventory.getQuantityAvailable().subtract(original.getQuantity()));
            inventory.setTotalReceived(inventory.getTotalReceived().subtract(original.getQuantity()));
        } else if ("SALIDA".equals(original.getMovementType())) {
            // La salida restó del inventario → devolver
            inventory.setQuantityAvailable(inventory.getQuantityAvailable().add(original.getQuantity()));
            inventory.setTotalDelivered(inventory.getTotalDelivered().subtract(original.getQuantity()));
        }
        inventoryRepository.save(inventory);

        // Crear movimiento de anulación (reversa)
        LeatherMovementEntity reversal = LeatherMovementEntity.builder()
                .movementType("ANULADO")
                .materialId(original.getMaterialId())
                .quantity(original.getQuantity())
                .unitCost(original.getUnitCost())
                .movementDate(LocalDate.now())
                .supplierId(original.getSupplierId())
                .purchaseDocument(original.getPurchaseDocument())
                .productionOrderId(original.getProductionOrderId())
                .deliveredBy(original.getDeliveredBy())
                .receivedBy(original.getReceivedBy())
                .observations("ANULACIÓN de movimiento #" + id
                        + (reason != null && !reason.isBlank() ? " — Motivo: " + reason : "")
                        + " | Original: " + original.getMovementType() + " " + original.getQuantity() + " ft²")
                .balanceAfter(inventory.getQuantityAvailable())
                .createdBy(securityUtil.getCurrentUserId())
                .build();
        movementRepository.save(reversal);

        // Marcar el original como anulado
        original.setMovementType("ANULADO");
        original.setObservations((original.getObservations() != null ? original.getObservations() + " | " : "")
                + "ANULADO" + (reason != null && !reason.isBlank() ? ": " + reason : ""));
        movementRepository.save(original);

        return toMovementResponse(reversal);
    }

    // ─── Validaciones ────────────────────────────────────────────────

    private void validateReception(LeatherMovementRequest req) throws BusinessException {
        if (req.getMaterialId() == null)
            throw new BusinessException("Debe seleccionar un tipo de cuero.");
        if (req.getQuantity() == null || req.getQuantity().compareTo(BigDecimal.ZERO) <= 0)
            throw new BusinessException("La cantidad debe ser mayor a 0.");
        if (req.getDeliveredBy() == null || req.getDeliveredBy().isBlank())
            throw new BusinessException("Debe indicar quién entrega el cuero.");
        if (req.getReceivedBy() == null || req.getReceivedBy().isBlank())
            throw new BusinessException("Debe indicar quién recibe el cuero.");
    }

    private void validateDelivery(LeatherMovementRequest req) throws BusinessException {
        if (req.getMaterialId() == null)
            throw new BusinessException("Debe seleccionar un tipo de cuero.");
        if (req.getQuantity() == null || req.getQuantity().compareTo(BigDecimal.ZERO) <= 0)
            throw new BusinessException("La cantidad debe ser mayor a 0.");
        if (req.getDeliveredBy() == null || req.getDeliveredBy().isBlank())
            throw new BusinessException("Debe indicar quién entrega el cuero.");
        if (req.getReceivedBy() == null || req.getReceivedBy().isBlank())
            throw new BusinessException("Debe indicar quién recibe el cuero.");

        if (req.getProductionOrderId() == null) {
            if (req.getDeliveryProducts() == null || req.getDeliveryProducts().isEmpty()) {
                throw new BusinessException("Para entrega sin orden de producción debe seleccionar al menos un producto destino.");
            }
        }

        if (req.getDeliveryProducts() != null && !req.getDeliveryProducts().isEmpty()) {
            for (LeatherMovementRequest.DeliveryProductItem item : req.getDeliveryProducts()) {
                if (item == null) {
                    throw new BusinessException("Detalle de productos inválido en entrega de cuero.");
                }
                if (item.getProductId() == null) {
                    throw new BusinessException("Cada detalle de entrega debe indicar producto.");
                }
                productRepository.findById(item.getProductId())
                        .orElseThrow(() -> new BusinessException("Producto no encontrado en detalle de entrega: " + item.getProductId()));

                if (item.getProductQuantity() == null || item.getProductQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                    throw new BusinessException("La cantidad de producto en detalle debe ser mayor a 0.");
                }
                if (item.getLeatherQuantity() == null || item.getLeatherQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                    throw new BusinessException("La cantidad de cuero por detalle debe ser mayor a 0.");
                }
            }
        }
    }

    private String buildDeliveryObservations(
            String baseObservations,
            List<LeatherMovementRequest.DeliveryProductItem> deliveryProducts,
            Long productionOrderId) {
        StringBuilder details = new StringBuilder();

        if (deliveryProducts != null && !deliveryProducts.isEmpty()) {
            details.append("Detalle productos: ");
            for (int i = 0; i < deliveryProducts.size(); i++) {
                LeatherMovementRequest.DeliveryProductItem item = deliveryProducts.get(i);
                if (item == null) continue;
                if (i > 0) details.append(" | ");

                String productName = item.getProductName();
                if (productName == null || productName.isBlank()) {
                    productName = productRepository.findById(item.getProductId())
                            .map(p -> p.getName() != null && !p.getName().isBlank() ? p.getName() : p.getCode())
                            .orElse("Producto #" + item.getProductId());
                }
                details.append(productName)
                        .append(" (cant: ").append(item.getProductQuantity())
                        .append(", cuero: ").append(item.getLeatherQuantity()).append(" ft²)");
            }
        }

        if (productionOrderId == null) {
            if (details.length() > 0) {
                details.insert(0, "Entrega sin OP. ");
            } else {
                details.append("Entrega sin OP.");
            }
        }

        if (baseObservations == null || baseObservations.isBlank()) {
            return details.length() > 0 ? details.toString() : null;
        }

        if (details.length() == 0) {
            return baseObservations;
        }
        return baseObservations + " | " + details;
    }

    private void markLeatherDeliveredForProductionOrder(Long productionOrderId) {
        if (productionOrderId == null) return;

        LocalDateTime now = LocalDateTime.now();
        List<TaskEntity> tasks = taskRepository.findByProductionOrderId(productionOrderId);
        for (TaskEntity task : tasks) {
            if ("CANCELLED".equals(task.getStatus())) continue;
            if (!Boolean.TRUE.equals(task.getLeatherDelivered())) {
                task.setLeatherDelivered(true);
                if (task.getLeatherDeliveredAt() == null) {
                    task.setLeatherDeliveredAt(now);
                }
                taskRepository.save(task);
            }
        }
    }

    // ─── Mappers ─────────────────────────────────────────────────────

    private LeatherInventoryResponse toInventoryResponse(LeatherInventoryEntity e) {
        String materialName = null;
        String materialSku = null;
        MaterialEntity m = e.getMaterial() != null ? e.getMaterial()
                : materialRepository.findById(e.getMaterialId()).orElse(null);
        if (m != null) {
            materialName = m.getName();
            materialSku = m.getSku();
        }
        return LeatherInventoryResponse.builder()
                .id(e.getId())
                .materialId(e.getMaterialId())
                .materialName(materialName)
                .materialSku(materialSku)
                .quantityAvailable(e.getQuantityAvailable())
                .totalReceived(e.getTotalReceived())
                .totalDelivered(e.getTotalDelivered())
                .updatedAt(e.getUpdatedAt())
                .build();
    }

    private LeatherMovementResponse toMovementResponse(LeatherMovementEntity e) {
        String materialName = null;
        String materialSku = null;
        MaterialEntity mat = materialRepository.findById(e.getMaterialId()).orElse(null);
        if (mat != null) {
            materialName = mat.getName();
            materialSku = mat.getSku();
        }

        String supplierName = null;
        if (e.getSupplierId() != null) {
            supplierName = supplierRepository.findById(e.getSupplierId())
                    .map(SupplierEntity::getName).orElse(null);
        }

        String productionOrderCode = null;
        if (e.getProductionOrderId() != null) {
            productionOrderCode = productionOrderRepository.findById(e.getProductionOrderId())
                    .map(ProductionOrderEntity::getCode).orElse(null);
        }

        return LeatherMovementResponse.builder()
                .id(e.getId())
                .movementType(e.getMovementType())
                .materialId(e.getMaterialId())
                .materialName(materialName)
                .materialSku(materialSku)
                .quantity(e.getQuantity())
                .unitCost(e.getUnitCost())
                .totalCost(e.getTotalCost())
                .movementDate(e.getMovementDate())
                .supplierId(e.getSupplierId())
                .supplierName(supplierName)
                .purchaseDocument(e.getPurchaseDocument())
                .productionOrderId(e.getProductionOrderId())
                .productionOrderCode(productionOrderCode)
                .deliveredBy(e.getDeliveredBy())
                .receivedBy(e.getReceivedBy())
                .observations(e.getObservations())
                .balanceAfter(e.getBalanceAfter())
                .createdAt(e.getCreatedAt())
                .createdBy(e.getCreatedBy())
                .build();
    }
}

