package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.LfPackingDtos.Box;
import com.fossiles.fossilescorebackend.application.dto.LfPackingDtos.BoxLine;
import com.fossiles.fossilescorebackend.application.dto.LfPackingDtos.BoxLineRequest;
import com.fossiles.fossilescorebackend.application.dto.LfPackingDtos.CreateBoxRequest;
import com.fossiles.fossilescorebackend.application.dto.LfPackingDtos.DispatchRequest;
import com.fossiles.fossilescorebackend.application.dto.LfPackingDtos.DispatchResult;
import com.fossiles.fossilescorebackend.application.dto.LfPackingDtos.LineRow;
import com.fossiles.fossilescorebackend.application.dto.LfPackingDtos.OrderDetail;
import com.fossiles.fossilescorebackend.application.dto.LfPackingDtos.OrderSummary;
import com.fossiles.fossilescorebackend.application.dto.LfPackingDtos.ShipmentRef;
import com.fossiles.fossilescorebackend.application.dto.LfPackingDtos.UpdateBoxRequest;
import com.fossiles.fossilescorebackend.application.dto.request.PartialReleaseLineRequest;
import com.fossiles.fossilescorebackend.application.dto.request.PartialReleaseUpsertRequest;
import com.fossiles.fossilescorebackend.application.dto.request.ProductShipmentRequest;
import com.fossiles.fossilescorebackend.application.dto.response.PartialReleaseLineResponse;
import com.fossiles.fossilescorebackend.application.dto.response.PartialReleaseResponse;
import com.fossiles.fossilescorebackend.application.dto.response.ProductShipmentResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.LfPackingConflictException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LfPackingBoxEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LfPackingBoxLineEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductShipmentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderWarehouseUnitEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LfPackingBoxLineRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LfPackingBoxRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductShipmentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderWarehouseUnitRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductionPlanningConstants;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Cajas de armado de Bodega PT para las OPV y OPC de Luis Felipe.
 * <p>
 * Bodega ve la orden desde antes de liberarla, planea cajas por partes y despacha las cajas listas juntas.
 * Armar cajas solo planea: no mueve inventario, ni numeración, ni precios. El despacho reutiliza la liberación
 * parcial y el envío del ERP ({@link ProductionOrderPartialReleaseService#generateShipment}) y lo manda con la misma
 * lógica del envío móvil ({@link ProductDistributionService#sendShipment}), así que la numeración (ENVP / OPC-ENV),
 * el congelado de precios, la salida de reserva y el cargo de cartera no se duplican.
 * <p>
 * Todas las escrituras toman {@code SELECT ... FOR UPDATE} sobre la orden: dos teléfonos armando o despachando la
 * misma orden se serializan y el segundo ve lo que dejó el primero.
 */
@Service
@RequiredArgsConstructor
public class LfPackingService {

    private static final int LIST_LIMIT = 80;
    private static final String RECEIVED = "RECEIVED";

    private final ProductionOrderRepository orderRepository;
    private final ProductionOrderWarehouseUnitRepository unitRepository;
    private final ProductShipmentRepository shipmentRepository;
    private final LfPackingBoxRepository boxRepository;
    private final LfPackingBoxLineRepository boxLineRepository;
    private final ProductionOrderPartialReleaseService partialReleaseService;
    private final ProductDistributionService distributionService;
    private final LfPackingAccessGuard accessGuard;
    private final SecurityUtil securityUtil;

    // ───────────────────────────── Lecturas ─────────────────────────────

    /** OPV y OPC de Luis Felipe con la recepción abierta (o que coincidan con el envío buscado). */
    @Transactional(readOnly = true)
    public List<OrderSummary> listOrders(String query) {
        accessGuard.requireUser();
        String needle = query == null ? "" : query.trim().toUpperCase(Locale.ROOT);

        Map<Long, ProductionOrderEntity> orders = new LinkedHashMap<>();
        for (ProductionOrderEntity po : orderRepository.findLuisFelipeOrdersWithOpenWarehouseReceipt()) {
            if (isReserveOrder(po)) {
                orders.put(po.getId(), po);
            }
        }
        if (!needle.isEmpty()) {
            // Un envío buscado puede pertenecer a una orden con la recepción ya cerrada: igual se muestra.
            Set<Long> byShipment = shipmentRepository.searchByShipmentNumberContaining(needle).stream()
                    .map(ProductShipmentEntity::getProductionOrderId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            Set<Long> notListed = new LinkedHashSet<>(byShipment);
            notListed.removeAll(orders.keySet());
            if (!notListed.isEmpty()) {
                orderRepository.findAllById(notListed).stream()
                        .filter(LfPackingService::isReserveOrder)
                        .forEach(po -> orders.put(po.getId(), po));
            }
            orders.values().removeIf(po -> !matches(po, needle, byShipment));
        }
        List<ProductionOrderEntity> page = orders.values().stream().limit(LIST_LIMIT).collect(Collectors.toList());
        if (page.isEmpty()) {
            return List.of();
        }
        Set<Long> ids = page.stream().map(ProductionOrderEntity::getId).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, Integer> receivedByOrder = new HashMap<>();
        for (Object[] row : unitRepository.countReceivedByOrderIds(ids)) {
            receivedByOrder.put(((Number) row[0]).longValue(), ((Number) row[1]).intValue());
        }
        Map<Long, List<LfPackingBoxEntity>> boxesByOrder = boxRepository
                .findByProductionOrderIdInOrderByProductionOrderIdAscBoxNoAsc(ids).stream()
                .collect(Collectors.groupingBy(LfPackingBoxEntity::getProductionOrderId));
        List<ProductShipmentEntity> pageShipments = shipmentRepository.findByProductionOrderIdIn(ids);
        Set<Long> historical = partialReleaseService.unsentHistoricalShipmentIds(pageShipments);
        Map<Long, List<ProductShipmentEntity>> shipmentsByOrder = pageShipments.stream()
                .collect(Collectors.groupingBy(ProductShipmentEntity::getProductionOrderId));

        List<OrderSummary> out = new ArrayList<>();
        for (ProductionOrderEntity po : page) {
            List<PartialReleaseLineResponse> availability = partialReleaseService.availabilityRowsForOrder(po.getId());
            out.add(summarize(
                    po,
                    availability,
                    receivedByOrder.getOrDefault(po.getId(), 0),
                    boxesByOrder.getOrDefault(po.getId(), List.of()),
                    visibleShipments(shipmentsByOrder.getOrDefault(po.getId(), List.of()), historical)));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public OrderDetail getOrder(Long productionOrderId) throws ResourceNotFoundException, BusinessException {
        accessGuard.requireUser();
        ProductionOrderEntity po = orderRepository.findById(productionOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductionOrder", productionOrderId));
        requireReserveOrder(po);
        return buildDetail(po);
    }

    // ───────────────────────────── Cajas ─────────────────────────────

    @Transactional(rollbackFor = Exception.class)
    public OrderDetail createBox(Long productionOrderId, CreateBoxRequest request)
            throws ResourceNotFoundException, BusinessException {
        accessGuard.requireUser();
        ProductionOrderEntity po = lockOrder(productionOrderId);
        int next = boxRepository.findTopByProductionOrderIdOrderByBoxNoDesc(productionOrderId)
                .map(b -> b.getBoxNo() + 1)
                .orElse(1);
        Long userId = securityUtil.getCurrentUserId();
        boxRepository.saveAndFlush(LfPackingBoxEntity.builder()
                .productionOrderId(productionOrderId)
                .boxNo(next)
                .status(LfPackingBoxEntity.STATUS_OPEN)
                .notes(request == null ? null : trimToNull(request.getNotes()))
                .createdBy(userId)
                .updatedBy(userId)
                .build());
        return buildDetail(po);
    }

    @Transactional(rollbackFor = Exception.class)
    public OrderDetail updateBox(Long productionOrderId, Long boxId, UpdateBoxRequest request)
            throws ResourceNotFoundException, BusinessException {
        accessGuard.requireUser();
        ProductionOrderEntity po = lockOrder(productionOrderId);
        LfPackingBoxEntity box = loadBox(productionOrderId, boxId);
        if (LfPackingBoxEntity.STATUS_DISPATCHED.equals(box.getStatus())) {
            throw dispatchedBox(box);
        }
        box.setNotes(request == null ? null : trimToNull(request.getNotes()));
        box.setUpdatedBy(securityUtil.getCurrentUserId());
        boxRepository.saveAndFlush(box);
        return buildDetail(po);
    }

    @Transactional(rollbackFor = Exception.class)
    public OrderDetail deleteBox(Long productionOrderId, Long boxId) throws ResourceNotFoundException, BusinessException {
        accessGuard.requireUser();
        ProductionOrderEntity po = lockOrder(productionOrderId);
        LfPackingBoxEntity box = loadBox(productionOrderId, boxId);
        if (LfPackingBoxEntity.STATUS_DISPATCHED.equals(box.getStatus())) {
            throw dispatchedBox(box);
        }
        boxLineRepository.deleteByBoxId(box.getId());
        boxRepository.delete(box);
        boxRepository.flush();
        return buildDetail(po);
    }

    /**
     * Cambia las piezas de una línea/talla dentro de una caja abierta. Se guarda al instante; la cantidad en cajas
     * nunca pasa de pedido − liberado/enviado − lo que ya tienen las otras cajas.
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderDetail changeLine(Long productionOrderId, Long boxId, BoxLineRequest request)
            throws ResourceNotFoundException, BusinessException {
        accessGuard.requireUser();
        ProductionOrderEntity po = lockOrder(productionOrderId);
        LfPackingBoxEntity box = loadBox(productionOrderId, boxId);
        requireStatus(box, LfPackingBoxEntity.STATUS_OPEN, "BOX_NOT_OPEN",
                "La caja " + box.getBoxNo() + " no está abierta; reábrela para cambiar sus piezas.");

        if (request == null || request.getProductionOrderItemId() == null) {
            throw new BusinessException("Indica la línea de la orden.");
        }
        if ((request.getQuantity() == null) == (request.getDelta() == null)) {
            throw new BusinessException("Indica la cantidad nueva (quantity) o el cambio (delta), solo uno de los dos.");
        }
        Map<Long, PartialReleaseLineResponse> rows = rowsByItem(partialReleaseService.availabilityRowsForOrder(productionOrderId));
        PartialReleaseLineResponse row = rows.get(request.getProductionOrderItemId());
        if (row == null) {
            throw new BusinessException("La línea no pertenece a la orden " + po.getCode() + ".");
        }
        String size = canonicalSize(row, request.getSizeKey());

        LfPackingBoxLineEntity line = boxLineRepository
                .findByBoxIdAndProductionOrderItemIdAndSizeKey(box.getId(), row.getProductionOrderItemId(), size)
                .orElse(null);
        int current = line == null ? 0 : line.getQuantity();
        int target;
        if (request.getQuantity() != null) {
            if (request.getQuantity() < 0) {
                throw new BusinessException("La cantidad no puede ser negativa.");
            }
            target = request.getQuantity();
        } else {
            target = Math.max(0, current + request.getDelta());
        }
        int increase = target - current;
        if (increase > 0) {
            int available = pick(row.getPendingSizes(), row.getPendingTotal(), size);
            if (increase > available) {
                throw new LfPackingConflictException(
                        "QTY_UNAVAILABLE",
                        "Solo quedan " + available + " pieza(s) disponibles para cajas en " + describe(row, size)
                                + " (pidió " + increase + " más).",
                        List.of(describe(row, size) + ": disponible " + available + ", pedido " + increase));
            }
        }
        if (target == 0) {
            if (line != null) {
                boxLineRepository.delete(line);
            }
        } else if (line == null) {
            boxLineRepository.save(LfPackingBoxLineEntity.builder()
                    .boxId(box.getId())
                    .productionOrderItemId(row.getProductionOrderItemId())
                    .sizeKey(size)
                    .quantity(target)
                    .build());
        } else {
            line.setQuantity(target);
            boxLineRepository.save(line);
        }
        box.setUpdatedBy(securityUtil.getCurrentUserId());
        boxRepository.saveAndFlush(box);
        return buildDetail(po);
    }

    @Transactional(rollbackFor = Exception.class)
    public OrderDetail closeBox(Long productionOrderId, Long boxId) throws ResourceNotFoundException, BusinessException {
        accessGuard.requireUser();
        ProductionOrderEntity po = lockOrder(productionOrderId);
        LfPackingBoxEntity box = loadBox(productionOrderId, boxId);
        if (LfPackingBoxEntity.STATUS_CLOSED.equals(box.getStatus())) {
            return buildDetail(po);
        }
        requireStatus(box, LfPackingBoxEntity.STATUS_OPEN, "BOX_NOT_OPEN", "La caja " + box.getBoxNo() + " ya fue despachada.");
        if (boxLineRepository.findByBoxId(box.getId()).isEmpty()) {
            throw new LfPackingConflictException("BOX_EMPTY", "La caja " + box.getBoxNo() + " está vacía; agrega piezas antes de cerrarla.");
        }
        box.setStatus(LfPackingBoxEntity.STATUS_CLOSED);
        box.setUpdatedBy(securityUtil.getCurrentUserId());
        boxRepository.saveAndFlush(box);
        return buildDetail(po);
    }

    /** Una caja CERRADA se puede reabrir mientras no se despache. */
    @Transactional(rollbackFor = Exception.class)
    public OrderDetail reopenBox(Long productionOrderId, Long boxId) throws ResourceNotFoundException, BusinessException {
        accessGuard.requireUser();
        ProductionOrderEntity po = lockOrder(productionOrderId);
        LfPackingBoxEntity box = loadBox(productionOrderId, boxId);
        if (LfPackingBoxEntity.STATUS_OPEN.equals(box.getStatus())) {
            return buildDetail(po);
        }
        requireStatus(box, LfPackingBoxEntity.STATUS_CLOSED, "BOX_DISPATCHED",
                "La caja " + box.getBoxNo() + " ya fue despachada y no se puede reabrir.");
        box.setStatus(LfPackingBoxEntity.STATUS_OPEN);
        box.setUpdatedBy(securityUtil.getCurrentUserId());
        boxRepository.saveAndFlush(box);
        return buildDetail(po);
    }

    // ───────────────────────────── Despacho ─────────────────────────────

    /**
     * Despacha juntas las cajas CERRADAS seleccionadas de una orden, en una sola transacción: valida que todo esté
     * recibido en reserva, crea la liberación parcial (sin aprobación en el ERP), genera y confirma el envío (un solo
     * ENVP / OPC-ENV con todas las cajas), lo pasa a SENT y marca las cajas DISPATCHED. Si algo falla no queda nada.
     */
    @Transactional(rollbackFor = Exception.class)
    public DispatchResult dispatch(Long productionOrderId, DispatchRequest request)
            throws ResourceNotFoundException, BusinessException {
        accessGuard.requireUser();
        ProductionOrderEntity po = lockOrder(productionOrderId);

        List<Long> boxIds = request == null || request.getBoxIds() == null
                ? List.of()
                : request.getBoxIds().stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
        if (boxIds.isEmpty()) {
            throw new BusinessException("Selecciona al menos una caja para despachar.");
        }
        Map<Long, LfPackingBoxEntity> boxById = boxRepository.findByIdInAndProductionOrderId(boxIds, productionOrderId).stream()
                .collect(Collectors.toMap(LfPackingBoxEntity::getId, b -> b));
        if (boxById.size() != boxIds.size()) {
            throw new LfPackingConflictException("BOX_NOT_FOUND", "Alguna de las cajas ya no existe o no pertenece a la orden " + po.getCode() + ".");
        }
        List<LfPackingBoxEntity> boxes = boxIds.stream().map(boxById::get)
                .sorted(Comparator.comparing(LfPackingBoxEntity::getBoxNo))
                .collect(Collectors.toList());
        for (LfPackingBoxEntity box : boxes) {
            if (LfPackingBoxEntity.STATUS_DISPATCHED.equals(box.getStatus())) {
                throw dispatchedBox(box);
            }
            if (!LfPackingBoxEntity.STATUS_CLOSED.equals(box.getStatus())) {
                throw new LfPackingConflictException("BOX_NOT_CLOSED",
                        "La caja " + box.getBoxNo() + " sigue abierta; ciérrala antes de despacharla.");
            }
        }

        Map<Long, List<LfPackingBoxLineEntity>> linesByBox = boxLineRepository
                .findByBoxIdIn(boxes.stream().map(LfPackingBoxEntity::getId).collect(Collectors.toList())).stream()
                .collect(Collectors.groupingBy(LfPackingBoxLineEntity::getBoxId));
        Map<Long, PartialReleaseLineResponse> rows = rowsByItem(partialReleaseService.availabilityRowsForOrder(productionOrderId));

        // Lo que llevan las cajas, sumado por línea y talla.
        Map<String, Integer> needed = new LinkedHashMap<>();
        Map<String, Object[]> neededMeta = new HashMap<>();
        for (LfPackingBoxEntity box : boxes) {
            for (LfPackingBoxLineEntity line : linesByBox.getOrDefault(box.getId(), List.of())) {
                String k = key(line.getProductionOrderItemId(), line.getSizeKey());
                needed.merge(k, line.getQuantity(), Integer::sum);
                neededMeta.putIfAbsent(k, new Object[] {line.getProductionOrderItemId(), line.getSizeKey()});
            }
        }
        if (needed.isEmpty()) {
            throw new LfPackingConflictException("BOX_EMPTY", "Las cajas seleccionadas no tienen piezas.");
        }

        // Todo lo de las cajas debe estar ya recibido (y sin despachar) en la reserva.
        Map<String, Integer> inReserve = reservePool(po.getId(), rows);
        List<String> shortages = new ArrayList<>();
        for (Map.Entry<String, Integer> e : needed.entrySet()) {
            int have = inReserve.getOrDefault(e.getKey(), 0);
            if (e.getValue() > have) {
                Object[] meta = neededMeta.get(e.getKey());
                PartialReleaseLineResponse row = rows.get((Long) meta[0]);
                shortages.add(describe(row, (String) meta[1]) + ": en cajas " + e.getValue()
                        + ", recibidas en reserva " + have + ", faltan " + (e.getValue() - have));
            }
        }
        if (!shortages.isEmpty()) {
            throw new LfPackingConflictException(
                    "PIECES_NOT_RECEIVED",
                    "Faltan piezas por recibir en reserva para despachar estas cajas.",
                    shortages);
        }

        // Las cajas dejan de reservar piezas antes de crear la liberación, para que el pendiente no las cuente dos veces.
        String boxLabel = boxes.stream().map(b -> String.valueOf(b.getBoxNo())).collect(Collectors.joining(", "));
        Long userId = securityUtil.getCurrentUserId();
        for (LfPackingBoxEntity box : boxes) {
            box.setStatus(LfPackingBoxEntity.STATUS_DISPATCHED);
            box.setUpdatedBy(userId);
        }
        boxRepository.saveAllAndFlush(boxes);

        PartialReleaseResponse release;
        try {
            release = partialReleaseService.createDraft(productionOrderId, PartialReleaseUpsertRequest.builder()
                    .label("Cajas " + boxLabel + " (Bodega PT)")
                    .notes("Despacho de Bodega PT: cajas " + boxLabel)
                    .status("CONFIRMED")
                    .lines(releaseLines(needed, neededMeta, rows))
                    .build());
        } catch (BusinessException ex) {
            throw new LfPackingConflictException("RELEASE_CONFLICT",
                    "Las cajas ya no caben en el pendiente de la orden: " + ex.getMessage());
        }

        // Misma ruta que el ERP: envío CONFIRMED (numeración y precios congelados) y liberación SHIPPED.
        ProductShipmentResponse shipment = partialReleaseService.generateShipment(release.getId(), null);
        List<ProductShipmentRequest.PackingItemRequest> packing = cleanPacking(request.getPackingItems());
        if (!packing.isEmpty()) {
            distributionService.updateShipmentPackingItems(shipment.getId(), packing);
        }
        // Mismo envío que hace el móvil hoy: CONFIRMED -> SENT (reserva y cargo de cartera por esta misma ruta).
        ProductShipmentResponse sent = distributionService.sendShipment(shipment.getId());

        // sendShipment limpia el contexto de persistencia: se recargan las cajas antes de ligarlas.
        List<LfPackingBoxEntity> reloaded = boxRepository.findByIdInAndProductionOrderId(boxIds, productionOrderId);
        for (LfPackingBoxEntity box : reloaded) {
            box.setPartialReleaseId(release.getId());
            box.setShipmentId(sent.getId());
            box.setUpdatedBy(userId);
        }
        boxRepository.saveAllAndFlush(reloaded);

        ProductionOrderEntity fresh = orderRepository.findById(productionOrderId).orElse(po);
        OrderDetail detail = buildDetail(fresh);
        Set<Long> dispatched = new HashSet<>(boxIds);
        return DispatchResult.builder()
                .productionOrderId(productionOrderId)
                .orderCode(fresh.getCode())
                .shipmentId(sent.getId())
                .shipmentNumber(sent.getShipmentNumber())
                .shipmentStatus(sent.getStatus())
                .partialReleaseId(release.getId())
                .totalPieces(needed.values().stream().mapToInt(Integer::intValue).sum())
                .boxes(detail.getBoxes().stream().filter(b -> dispatched.contains(b.getId())).collect(Collectors.toList()))
                .detail(detail)
                .build();
    }

    // ───────────────────────────── Cálculo ─────────────────────────────

    private OrderDetail buildDetail(ProductionOrderEntity po) {
        List<PartialReleaseLineResponse> availability = partialReleaseService.availabilityRowsForOrder(po.getId());
        Map<Long, PartialReleaseLineResponse> rows = rowsByItem(availability);
        List<ProductionOrderWarehouseUnitEntity> units = unitRepository
                .findByProductionOrderIdOrderByProductionOrderItemIdAscSizeKeyAscUnitSeqAsc(po.getId());
        Map<String, Integer> receivedByKey = new HashMap<>();
        Map<String, Integer> shippedByKey = new HashMap<>();
        int receivedTotal = 0;
        for (ProductionOrderWarehouseUnitEntity unit : units) {
            if (!RECEIVED.equalsIgnoreCase(String.valueOf(unit.getReceiptStatus()).trim())) {
                continue;
            }
            receivedTotal++;
            String k = unitKey(unit, rows.get(unit.getProductionOrderItemId()));
            receivedByKey.merge(k, 1, Integer::sum);
            if (unit.getShippedAt() != null) {
                shippedByKey.merge(k, 1, Integer::sum);
            }
        }
        Map<String, Integer> pool = new HashMap<>();
        receivedByKey.forEach((k, v) -> pool.put(k, Math.max(0, v - shippedByKey.getOrDefault(k, 0))));

        List<LineRow> lines = new ArrayList<>();
        for (PartialReleaseLineResponse row : availability) {
            for (String size : sizesOf(row)) {
                int ordered = pick(row.getOrderedSizes(), row.getOrderedTotal(), size);
                int received = receivedByKey.getOrDefault(key(row.getProductionOrderItemId(), size), 0);
                int inBoxes = pick(row.getInOpenBoxesSizes(), row.getInOpenBoxes(), size);
                int dispatched = pick(row.getAllocatedSizesInOtherReleases(), row.getAllocatedInOtherReleases(), size);
                int available = pick(row.getPendingSizes(), row.getPendingTotal(), size);
                int inReserve = pool.getOrDefault(key(row.getProductionOrderItemId(), size), 0);
                lines.add(LineRow.builder()
                        .productionOrderItemId(row.getProductionOrderItemId())
                        .sizeKey(size)
                        .productCode(row.getProductCode())
                        .productName(row.getProductName())
                        .colorName(row.getColorName())
                        .ordered(ordered)
                        .received(received)
                        .inBoxes(inBoxes)
                        .dispatched(dispatched)
                        .missingReceipt(Math.max(0, ordered - received))
                        .availableToPack(available)
                        .boxesNotYetReceived(Math.max(0, inBoxes - inReserve))
                        .build());
            }
        }

        List<LfPackingBoxEntity> boxEntities = boxRepository.findByProductionOrderIdOrderByBoxNoAsc(po.getId());
        Map<Long, List<LfPackingBoxLineEntity>> linesByBox = boxEntities.isEmpty()
                ? Map.of()
                : boxLineRepository.findByBoxIdIn(boxEntities.stream().map(LfPackingBoxEntity::getId).collect(Collectors.toList()))
                        .stream().collect(Collectors.groupingBy(LfPackingBoxLineEntity::getBoxId));
        List<ProductShipmentEntity> allShipments = shipmentRepository.findByProductionOrderId(po.getId());
        List<ProductShipmentEntity> shipments = visibleShipments(
                allShipments, partialReleaseService.unsentHistoricalShipmentIds(allShipments));
        Map<Long, String> shipmentNumbers = allShipments.stream()
                .collect(Collectors.toMap(ProductShipmentEntity::getId,
                        s -> s.getShipmentNumber() == null ? "" : s.getShipmentNumber(), (a, b) -> a));
        int totalBoxes = boxEntities.stream().mapToInt(LfPackingBoxEntity::getBoxNo).max().orElse(0);

        List<Box> boxes = new ArrayList<>();
        for (LfPackingBoxEntity box : boxEntities) {
            boxes.add(toBox(po, box, totalBoxes, linesByBox.getOrDefault(box.getId(), List.of()), rows, pool, shipmentNumbers));
        }
        OrderSummary summary = summarize(po, availability, receivedTotal, boxEntities, shipments);
        return OrderDetail.builder()
                .order(summary)
                .lines(lines)
                .boxes(boxes)
                .shipments(shipments.stream()
                        .map(s -> ShipmentRef.builder().id(s.getId()).shipmentNumber(s.getShipmentNumber())
                                .status(s.getStatus() == null ? null : s.getStatus().trim().toUpperCase(Locale.ROOT)).build())
                        .collect(Collectors.toList()))
                .build();
    }

    private Box toBox(
            ProductionOrderEntity po,
            LfPackingBoxEntity box,
            int totalBoxes,
            List<LfPackingBoxLineEntity> lines,
            Map<Long, PartialReleaseLineResponse> rows,
            Map<String, Integer> pool,
            Map<Long, String> shipmentNumbers) {
        boolean dispatched = LfPackingBoxEntity.STATUS_DISPATCHED.equals(box.getStatus());
        boolean allReceived = true;
        List<BoxLine> out = new ArrayList<>();
        for (LfPackingBoxLineEntity line : lines.stream()
                .sorted(Comparator.comparing(LfPackingBoxLineEntity::getProductionOrderItemId)
                        .thenComparing(LfPackingBoxLineEntity::getSizeKey))
                .collect(Collectors.toList())) {
            PartialReleaseLineResponse row = rows.get(line.getProductionOrderItemId());
            int have = pool.getOrDefault(key(line.getProductionOrderItemId(), line.getSizeKey()), 0);
            int missing = dispatched ? 0 : Math.max(0, line.getQuantity() - have);
            if (missing > 0) {
                allReceived = false;
            }
            out.add(BoxLine.builder()
                    .productionOrderItemId(line.getProductionOrderItemId())
                    .sizeKey(line.getSizeKey())
                    .productCode(row == null ? null : row.getProductCode())
                    .productName(row == null ? null : row.getProductName())
                    .colorName(row == null ? null : row.getColorName())
                    .quantity(line.getQuantity())
                    .missingReceipt(missing)
                    .build());
        }
        return Box.builder()
                .id(box.getId())
                .productionOrderId(po.getId())
                .orderCode(po.getCode())
                .boxNo(box.getBoxNo())
                .totalBoxes(totalBoxes)
                .status(box.getStatus())
                .partialReleaseId(box.getPartialReleaseId())
                .shipmentId(box.getShipmentId())
                .shipmentNumber(box.getShipmentId() == null ? null : shipmentNumbers.get(box.getShipmentId()))
                .notes(box.getNotes())
                .totalPieces(out.stream().mapToInt(BoxLine::getQuantity).sum())
                .allReceived(!out.isEmpty() && allReceived)
                .lines(out)
                .build();
    }

    private OrderSummary summarize(
            ProductionOrderEntity po,
            List<PartialReleaseLineResponse> availability,
            int receivedUnits,
            List<LfPackingBoxEntity> boxes,
            List<ProductShipmentEntity> shipments) {
        int ordered = availability.stream().mapToInt(r -> nz(r.getOrderedTotal())).sum();
        int dispatched = availability.stream().mapToInt(r -> nz(r.getAllocatedInOtherReleases())).sum();
        int inBoxes = availability.stream().mapToInt(r -> nz(r.getInOpenBoxes())).sum();
        int open = (int) boxes.stream().filter(b -> LfPackingBoxEntity.STATUS_OPEN.equals(b.getStatus())).count();
        int closed = (int) boxes.stream().filter(b -> LfPackingBoxEntity.STATUS_CLOSED.equals(b.getStatus())).count();
        int sent = (int) boxes.stream().filter(b -> LfPackingBoxEntity.STATUS_DISPATCHED.equals(b.getStatus())).count();
        return OrderSummary.builder()
                .productionOrderId(po.getId())
                .code(po.getCode())
                .family(ProductionPlanningConstants.orderFamilyLabel(po.getOrderType(), po.getCode()))
                .customerName(po.getCustomerName())
                .status(po.getStatus())
                .deliveryDate(po.getDeliveryDate())
                .orderedUnits(ordered)
                .receivedUnits(receivedUnits)
                .missingReceiptUnits(Math.max(0, ordered - receivedUnits))
                .inBoxesUnits(inBoxes)
                .dispatchedUnits(dispatched)
                .boxCount(boxes.size())
                .openBoxes(open)
                .closedBoxes(closed)
                .dispatchedBoxes(sent)
                .warehouseState(warehouseState(ordered, receivedUnits, dispatched, open, closed))
                .shipmentNumbers(shipments.stream().map(ProductShipmentEntity::getShipmentNumber)
                        .filter(Objects::nonNull).collect(Collectors.toList()))
                .build();
    }

    static String warehouseState(int ordered, int received, int dispatched, int openBoxes, int closedBoxes) {
        if (ordered > 0 && dispatched >= ordered) {
            return "DISPATCHED_ALL";
        }
        if (openBoxes > 0) {
            return "PACKING";
        }
        if (closedBoxes > 0) {
            return "READY";
        }
        if (dispatched > 0) {
            return "DISPATCHED_PARTIAL";
        }
        if (received <= 0) {
            return "NOT_RECEIVED";
        }
        return received < ordered ? "RECEIVING" : "RECEIVED";
    }

    /** Piezas recibidas en reserva y aún sin salir, por línea y talla. */
    private Map<String, Integer> reservePool(Long productionOrderId, Map<Long, PartialReleaseLineResponse> rows) {
        Map<String, Integer> received = new HashMap<>();
        Map<String, Integer> shipped = new HashMap<>();
        for (ProductionOrderWarehouseUnitEntity unit : unitRepository
                .findByProductionOrderIdOrderByProductionOrderItemIdAscSizeKeyAscUnitSeqAsc(productionOrderId)) {
            if (!RECEIVED.equalsIgnoreCase(String.valueOf(unit.getReceiptStatus()).trim())) {
                continue;
            }
            String k = unitKey(unit, rows.get(unit.getProductionOrderItemId()));
            received.merge(k, 1, Integer::sum);
            if (unit.getShippedAt() != null) {
                shipped.merge(k, 1, Integer::sum);
            }
        }
        Map<String, Integer> pool = new HashMap<>();
        received.forEach((k, v) -> pool.put(k, Math.max(0, v - shipped.getOrDefault(k, 0))));
        return pool;
    }

    /** Líneas de la liberación a partir de lo que llevan las cajas (tallas → mapa; sin tallas → cantidad). */
    private List<PartialReleaseLineRequest> releaseLines(
            Map<String, Integer> needed, Map<String, Object[]> meta, Map<Long, PartialReleaseLineResponse> rows) {
        Map<Long, Map<String, Integer>> sizesByItem = new LinkedHashMap<>();
        Map<Long, Integer> plainByItem = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : needed.entrySet()) {
            Object[] m = meta.get(e.getKey());
            Long itemId = (Long) m[0];
            String size = (String) m[1];
            if (size.isEmpty()) {
                plainByItem.merge(itemId, e.getValue(), Integer::sum);
            } else {
                sizesByItem.computeIfAbsent(itemId, k -> new LinkedHashMap<>()).merge(size, e.getValue(), Integer::sum);
            }
        }
        List<PartialReleaseLineRequest> lines = new ArrayList<>();
        sizesByItem.forEach((itemId, sizes) -> lines.add(PartialReleaseLineRequest.builder()
                .productionOrderItemId(itemId).sizes(sizes).build()));
        plainByItem.forEach((itemId, qty) -> lines.add(PartialReleaseLineRequest.builder()
                .productionOrderItemId(itemId).quantity(qty).build()));
        return lines;
    }

    private static List<ProductShipmentRequest.PackingItemRequest> cleanPacking(
            List<ProductShipmentRequest.PackingItemRequest> items) {
        if (items == null) {
            return List.of();
        }
        return items.stream()
                .filter(i -> i != null && i.getMaterialId() != null && i.getQuantity() != null
                        && i.getQuantity().signum() > 0)
                .collect(Collectors.toList());
    }

    // ───────────────────────────── Utilidades ─────────────────────────────

    private ProductionOrderEntity lockOrder(Long productionOrderId) throws ResourceNotFoundException, BusinessException {
        ProductionOrderEntity po = orderRepository.findByIdForUpdate(productionOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductionOrder", productionOrderId));
        requireReserveOrder(po);
        if ("CANCELLED".equalsIgnoreCase(String.valueOf(po.getStatus()).trim())) {
            throw new LfPackingConflictException("ORDER_CANCELLED", "La orden " + po.getCode() + " está cancelada.");
        }
        return po;
    }

    private static void requireReserveOrder(ProductionOrderEntity po) throws BusinessException {
        if (!isReserveOrder(po)) {
            throw new BusinessException("Solo las OPV y OPC de Luis Felipe se arman en cajas.");
        }
    }

    static boolean isReserveOrder(ProductionOrderEntity po) {
        return po != null && ProductionPlanningConstants.isLuisFelipeReserveOrder(
                po.getSellerName(), po.getOrderType(), po.getCode());
    }

    private LfPackingBoxEntity loadBox(Long productionOrderId, Long boxId) throws ResourceNotFoundException {
        return boxRepository.findByIdAndProductionOrderId(boxId, productionOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("LfPackingBox", boxId));
    }

    private static void requireStatus(LfPackingBoxEntity box, String expected, String code, String message) {
        if (!expected.equals(box.getStatus())) {
            throw new LfPackingConflictException(code, message);
        }
    }

    private static LfPackingConflictException dispatchedBox(LfPackingBoxEntity box) {
        return new LfPackingConflictException("BOX_DISPATCHED",
                "La caja " + box.getBoxNo() + " ya fue despachada y no se puede modificar.");
    }

    private static boolean matches(ProductionOrderEntity po, String needle, Set<Long> shipmentOrderIds) {
        if (po.getCode() != null && po.getCode().toUpperCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        if (po.getVendorShipmentNumber() != null && po.getVendorShipmentNumber().toUpperCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        return shipmentOrderIds.contains(po.getId());
    }

    /** Envíos reales de la orden: sin anulados ni los «ENVIO HISTORICO» del respaldo de cartera. */
    private static List<ProductShipmentEntity> visibleShipments(
            Collection<ProductShipmentEntity> shipments, Set<Long> unsentHistoricalIds) {
        return shipments.stream()
                .filter(s -> !unsentHistoricalIds.contains(s.getId()))
                .filter(s -> {
                    String st = s.getStatus() == null ? "" : s.getStatus().trim().toUpperCase(Locale.ROOT);
                    return !"CANCELLED".equals(st) && !"VOID".equals(st) && !"VOIDED".equals(st) && !"DRAFT".equals(st);
                })
                .filter(s -> s.getNotes() == null
                        || !s.getNotes().trim().toUpperCase(Locale.ROOT).startsWith("ENVIO HISTORICO"))
                .sorted(Comparator.comparing(ProductShipmentEntity::getId))
                .collect(Collectors.toList());
    }

    private static Map<Long, PartialReleaseLineResponse> rowsByItem(List<PartialReleaseLineResponse> rows) {
        Map<Long, PartialReleaseLineResponse> out = new LinkedHashMap<>();
        rows.forEach(r -> out.putIfAbsent(r.getProductionOrderItemId(), r));
        return out;
    }

    /** Tallas de la línea tal como las guarda la orden; vacía ("") cuando la línea no tiene tallas. */
    private static List<String> sizesOf(PartialReleaseLineResponse row) {
        if (row.getOrderedSizes() == null || row.getOrderedSizes().isEmpty()) {
            return List.of("");
        }
        return new ArrayList<>(row.getOrderedSizes().keySet());
    }

    private static int pick(Map<String, Integer> bySize, Integer total, String size) {
        if (size == null || size.isEmpty()) {
            return nz(total);
        }
        return bySize == null ? 0 : nz(bySize.get(size));
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }

    /** La talla se guarda como la escribe la orden (sin importar mayúsculas); "" si la línea no tiene tallas. */
    private static String canonicalSize(PartialReleaseLineResponse row, String raw) throws BusinessException {
        String size = raw == null ? "" : raw.trim();
        if (row.getOrderedSizes() == null || row.getOrderedSizes().isEmpty()) {
            if (!size.isEmpty()) {
                throw new BusinessException(row.getProductCode() + " no maneja tallas.");
            }
            return "";
        }
        if (size.isEmpty()) {
            throw new BusinessException("Indica la talla de " + row.getProductCode() + ".");
        }
        for (String ordered : row.getOrderedSizes().keySet()) {
            if (ordered.equalsIgnoreCase(size)) {
                return ordered;
            }
        }
        throw new BusinessException("La talla " + size + " no está en la orden para " + row.getProductCode() + ".");
    }

    private static String describe(PartialReleaseLineResponse row, String size) {
        StringBuilder sb = new StringBuilder();
        sb.append(row == null ? "Línea" : row.getProductCode());
        if (row != null && row.getColorName() != null && !row.getColorName().isBlank() && !"-".equals(row.getColorName())) {
            sb.append(' ').append(row.getColorName());
        }
        if (size != null && !size.isEmpty()) {
            sb.append(" talla ").append(size);
        }
        return sb.toString();
    }

    private static String key(Long itemId, String size) {
        return itemId + "|" + (size == null ? "" : size.trim().toUpperCase(Locale.ROOT));
    }

    /** Talla de la pieza; si la pieza no la trae y la línea tiene una sola talla, esa es la suya. */
    private static String unitKey(ProductionOrderWarehouseUnitEntity unit, PartialReleaseLineResponse row) {
        String size = unit.getSizeKey() == null ? "" : unit.getSizeKey().trim();
        if (size.isEmpty() && row != null && row.getOrderedSizes() != null && row.getOrderedSizes().size() == 1) {
            size = row.getOrderedSizes().keySet().iterator().next();
        }
        return key(unit.getProductionOrderItemId(), size);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }
}
