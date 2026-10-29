package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.*;
import com.fossiles.fossilescorebackend.application.exception.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.*;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;

/**
 * Crea, edita y anula recepciones de compra con la compra bloqueada; inventario, lotes y asientos se ajustan en la
 * misma transacción.
 */
@Service
@RequiredArgsConstructor
@Transactional(rollbackFor = Exception.class)
public class MaterialReceiptService {
    private final PurchaseOrderRepository orders;
    private final PurchaseOrderItemRepository orderItems;
    private final MaterialReceiptRepository receipts;
    private final MaterialReceiptItemRepository receiptItems;
    private final MaterialRepository materials;
    private final SupplierRepository suppliers;
    private final InventoryService inventory;
    private final AccountingService accounting;
    private final SecurityUtil security;

    public MaterialReceiptEntity create(MaterialReceiptRequest request) throws BusinessException, ResourceNotFoundException {
        var order = lockOrder(request.getPurchaseOrderId());
        if (!Set.of("CREADA", "PARCIALMENTE_RECIBIDA").contains(order.getStatus())) {
            throw new BusinessException("Solo se pueden recibir compras creadas o parcialmente recibidas.");
        }
        if (request.getItems() == null || request.getItems().isEmpty()
                || request.getItems().stream().noneMatch(i -> i != null && i.getQuantityReceived() != null && i.getQuantityReceived().signum() > 0)) {
            throw new BusinessException("Ingrese al menos una cantidad recibida positiva.");
        }
        var detail = validate(order, request, List.of());
        var receipt = receipts.save(MaterialReceiptEntity.builder().purchaseOrderId(order.getId())
                .receiptDate(request.getReceiptDate() == null ? GuatemalaDateTime.today() : request.getReceiptDate())
                .observations(request.getObservations()).createdBy(security.getCurrentUserId()).updatedBy(security.getCurrentUserId()).build());
        apply(order, receipt, detail);
        synchronize(order);
        return receipt;
    }

    public MaterialReceiptEntity update(Long id, MaterialReceiptRequest request) throws BusinessException, ResourceNotFoundException {
        var order = lockOrder(receipts.findOrderIdByReceiptId(id).orElseThrow(() -> new ResourceNotFoundException("Material Receipt", id)));
        var receipt = find(id);
        if (!Objects.equals(order.getId(), request.getPurchaseOrderId())) throw new BusinessException("No se puede cambiar la compra de una recepción.");
        if ("CANCELLED".equals(receipt.getStatus())) throw new BusinessException("No se puede editar una recepción anulada.");
        var existing = receiptItems.findByMaterialReceiptId(id);
        // Sin renglones, cada material se tomaría como nuevo y volvería a sumar el stock que el registro histórico ya sumó.
        if (existing.isEmpty()) throw new BusinessException("La recepción histórica no tiene detalle; concilie sus materiales antes de editarla.");
        var detail = validate(order, request, existing);
        if (request.getReceiptDate() != null) receipt.setReceiptDate(request.getReceiptDate());
        if (request.getObservations() != null) receipt.setObservations(request.getObservations());
        receipt.setUpdatedBy(security.getCurrentUserId());
        apply(order, receipt, detail);
        receipts.save(receipt);
        synchronize(order);
        return receipt;
    }

    public MaterialReceiptEntity cancel(Long id, String reason) throws BusinessException, ResourceNotFoundException {
        var order = lockOrder(receipts.findOrderIdByReceiptId(id).orElseThrow(() -> new ResourceNotFoundException("Material Receipt", id)));
        var receipt = find(id);
        if ("CANCELLED".equals(receipt.getStatus())) return receipt;
        if (reason == null || reason.isBlank() || reason.length() > 1000) throw new BusinessException("Indique un motivo de anulación de hasta 1000 caracteres.");
        var receivedLines = receiptItems.findByMaterialReceiptId(id);
        if (receivedLines.isEmpty()) throw new BusinessException("La recepción histórica no tiene detalle; concilie sus materiales antes de anularla.");
        for (var line : receivedLines.stream().sorted(Comparator.comparing(MaterialReceiptItemEntity::getMaterialId)).toList()) {
            inventory.reconcileReceiptInventory(line.getMaterialId(), id, order.getCode(), nz(line.getQuantityReceived()),
                    BigDecimal.ZERO, price(line, order), price(line, order));
        }
        receipt.setStatus("CANCELLED");
        receipt.setCancellationReason(reason.trim());
        receipt.setUpdatedBy(security.getCurrentUserId());
        receipts.save(receipt);
        synchronize(order);
        return receipt;
    }

    private MaterialReceiptEntity find(Long id) throws ResourceNotFoundException {
        return receipts.findById(id).orElseThrow(() -> new ResourceNotFoundException("Material Receipt", id));
    }

    private PurchaseOrderEntity lockOrder(Long id) throws ResourceNotFoundException, BusinessException {
        var order = orders.findByIdForUpdate(id).orElseThrow(() -> new ResourceNotFoundException("Purchase Order", id));
        if ("CANCELADA".equals(order.getStatus())) throw new BusinessException("La compra está cancelada.");
        return order;
    }

    private record Change(MaterialReceiptItemEntity line, BigDecimal oldQuantity, BigDecimal oldPrice,
                          BigDecimal quantity, BigDecimal price, Long supplierId, java.time.LocalDate date) {}

    private List<Change> validate(PurchaseOrderEntity order, MaterialReceiptRequest request, List<MaterialReceiptItemEntity> existing)
            throws BusinessException, ResourceNotFoundException {
        Map<Long, List<PurchaseOrderItemEntity>> ordered = new HashMap<>();
        for (var line : orderItems.findByPurchaseOrderId(order.getId())) ordered.computeIfAbsent(line.getMaterialId(), key -> new ArrayList<>()).add(line);
        Map<Long, MaterialReceiptItemEntity> previous = new HashMap<>();
        for (var line : existing) if (previous.put(line.getMaterialId(), line) != null) {
            throw new BusinessException("La recepción tiene materiales duplicados; concilie sus renglones antes de editarla.");
        }
        Set<Long> seen = new HashSet<>();
        List<Change> changes = new ArrayList<>();
        for (var input : request.getItems() == null ? List.<MaterialReceiptItemRequest>of() : request.getItems()) {
            if (input == null || input.getMaterialId() == null || !ordered.containsKey(input.getMaterialId())) throw new BusinessException("El material recibido no pertenece a la compra.");
            if (!seen.add(input.getMaterialId())) throw new BusinessException("Cada material debe aparecer una sola vez en la recepción.");
            if (input.getQuantityReceived() == null || input.getQuantityReceived().signum() < 0
                    || input.getQuantityReceived().stripTrailingZeros().scale() > 3) throw new BusinessException("La cantidad recibida debe ser no negativa y tener hasta tres decimales.");
            if (!materials.existsById(input.getMaterialId())) throw new ResourceNotFoundException("Material", input.getMaterialId());
            var old = previous.get(input.getMaterialId());
            // Un material no marcado llega en cero: solo cuenta si corrige un renglón que ya existía.
            if (old == null && input.getQuantityReceived().signum() == 0) continue;
            var source = ordered.get(input.getMaterialId()).getFirst();
            BigDecimal oldPrice = old == null ? BigDecimal.ZERO : price(old, order);
            BigDecimal newPrice = input.getUnitPriceReceived() == null ? (old == null ? nz(source.getUnitPrice()) : oldPrice) : input.getUnitPriceReceived();
            if (newPrice.signum() < 0 || newPrice.stripTrailingZeros().scale() > 2) throw new BusinessException("El precio debe ser no negativo y tener hasta dos decimales.");
            if (old == null && input.getUnitPriceReceived() == null && ordered.get(input.getMaterialId()).stream()
                    .map(PurchaseOrderItemEntity::getUnitPrice).distinct().count() > 1) throw new BusinessException("Indique el precio recibido para el material con varios precios en la compra.");
            Long supplier = input.getSupplierId() == null ? (old == null ? source.getSupplierId() : old.getSupplierId()) : input.getSupplierId();
            if (supplier != null && !suppliers.existsById(supplier)) throw new ResourceNotFoundException("Supplier", supplier);
            changes.add(new Change(old == null ? MaterialReceiptItemEntity.builder().materialId(input.getMaterialId()).createdBy(security.getCurrentUserId()).build() : old,
                    old == null ? BigDecimal.ZERO : nz(old.getQuantityReceived()), oldPrice, input.getQuantityReceived(), newPrice, supplier,
                    input.getReceiptDate() == null ? (request.getReceiptDate() == null ? (old == null ? GuatemalaDateTime.today() : old.getReceiptDate()) : request.getReceiptDate()) : input.getReceiptDate()));
        }
        changes.sort(Comparator.comparing(change -> change.line().getMaterialId()));
        return changes;
    }

    private void apply(PurchaseOrderEntity order, MaterialReceiptEntity receipt, List<Change> changes)
            throws ResourceNotFoundException, BusinessException {
        for (var change : changes) {
            inventory.reconcileReceiptInventory(change.line().getMaterialId(), receipt.getId(), order.getCode(),
                    change.oldQuantity(), change.quantity(), change.oldPrice(), change.price());
            var line = change.line();
            line.setMaterialReceiptId(receipt.getId());
            line.setQuantityReceived(change.quantity());
            line.setUnitPriceReceived(change.price());
            line.setSupplierId(change.supplierId());
            line.setReceiptDate(change.date());
            line.setUpdatedBy(security.getCurrentUserId());
            receiptItems.save(line);
        }
    }

    // Las recepciones activas consumen el valor acordado una sola vez; los excesos ajustan la deuda al proveedor.
    private void synchronize(PurchaseOrderEntity order) throws BusinessException {
        var ordered = orderItems.findByPurchaseOrderId(order.getId()).stream().sorted(Comparator.comparing(PurchaseOrderItemEntity::getId)).toList();
        Map<Long, BigDecimal> used = new HashMap<>();
        Map<Long, BigDecimal> totalOrdered = new HashMap<>();
        BigDecimal cumulativeExpected = BigDecimal.ZERO;
        boolean legacyWithoutDetail = false;
        ordered.forEach(line -> totalOrdered.merge(line.getMaterialId(), nz(line.getQuantity()), BigDecimal::add));
        for (var receipt : receipts.findByPurchaseOrderId(order.getId()).stream().sorted(Comparator.comparing(MaterialReceiptEntity::getId)).toList()) {
            BigDecimal actual = BigDecimal.ZERO;
            BigDecimal expected = BigDecimal.ZERO;
            if (!"CANCELLED".equals(receipt.getStatus())) {
                var detail = receiptItems.findByMaterialReceiptId(receipt.getId());
                // Una recepción histórica sin renglones conserva sus asientos; no hay cantidades para conciliarla.
                if (detail.isEmpty()) {
                    legacyWithoutDetail = true;
                    continue;
                }
                for (var received : detail) {
                    BigDecimal quantity = nz(received.getQuantityReceived());
                    actual = actual.add(quantity.multiply(price(received, order)));
                    BigDecimal already = used.getOrDefault(received.getMaterialId(), BigDecimal.ZERO);
                    BigDecimal remaining = quantity;
                    for (var line : ordered) {
                        if (!Objects.equals(line.getMaterialId(), received.getMaterialId())) continue;
                        BigDecimal skip = already.min(nz(line.getQuantity()));
                        already = already.subtract(skip);
                        BigDecimal covered = remaining.min(nz(line.getQuantity()).subtract(skip));
                        expected = expected.add(covered.multiply(nz(line.getUnitPrice())));
                        remaining = remaining.subtract(covered);
                    }
                    used.merge(received.getMaterialId(), quantity, BigDecimal::add);
                }
            }
            // El redondeo acumulado evita descargar centavos de más al dividir una compra en recepciones.
            BigDecimal expectedRounded = cumulativeExpected.add(expected).setScale(2, java.math.RoundingMode.HALF_UP)
                    .subtract(cumulativeExpected.setScale(2, java.math.RoundingMode.HALF_UP));
            cumulativeExpected = cumulativeExpected.add(expected);
            accounting.reconcileMaterialReceiptEntries(receipt.getId(), order.getCode(), actual, expectedRounded, order.getCostCenterId());
        }
        boolean any = used.values().stream().anyMatch(q -> q.signum() > 0);
        boolean complete = !totalOrdered.isEmpty() && totalOrdered.entrySet().stream()
                .allMatch(e -> used.getOrDefault(e.getKey(), BigDecimal.ZERO).compareTo(e.getValue()) >= 0);
        // Con una recepción histórica sin detalle el acumulado está incompleto: solo puede avanzar el estado.
        if (legacyWithoutDetail) any = true;
        if (!legacyWithoutDetail || complete) order.setStatus(!any ? "CREADA" : complete ? "RECIBIDA" : "PARCIALMENTE_RECIBIDA");
        else if ("CREADA".equals(order.getStatus())) order.setStatus("PARCIALMENTE_RECIBIDA");
        order.setUpdatedBy(security.getCurrentUserId());
        orders.save(order);
    }

    private BigDecimal price(MaterialReceiptItemEntity line, PurchaseOrderEntity order) {
        return line.getUnitPriceReceived() == null ? orderItems.findByPurchaseOrderId(order.getId()).stream()
                .filter(o -> Objects.equals(o.getMaterialId(), line.getMaterialId())).map(PurchaseOrderItemEntity::getUnitPrice)
                .filter(Objects::nonNull).findFirst().orElse(BigDecimal.ZERO) : line.getUnitPriceReceived();
    }
    private static BigDecimal nz(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
}
