package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.OnlineSaleRequest;
import com.fossiles.fossilescorebackend.application.dto.request.OnlineSaleExchangeRequest;
import com.fossiles.fossilescorebackend.application.dto.response.OnlineSaleDailySummaryResponse;
import com.fossiles.fossilescorebackend.application.dto.response.OnlineSaleReturnPrintResponse;
import com.fossiles.fossilescorebackend.application.dto.response.OnlineSaleResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ColorEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleReturnEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleReturnLineEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ReturnInventoryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ColorRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineSaleItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineSaleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineSaleReturnLineRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineSaleReturnRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ReturnInventoryRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class OnlineSaleService {

    private final OnlineSaleRepository saleRepository;
    private final OnlineSaleItemRepository itemRepository;
    private final ProductRepository productRepository;
    private final ColorRepository colorRepository;
    private final ReturnInventoryRepository returnInventoryRepository;
    private final OnlineSaleReturnRepository onlineSaleReturnRepository;
    private final OnlineSaleReturnLineRepository onlineSaleReturnLineRepository;
    private final SecurityUtil securityUtil;
    private final OnlineSaleShipmentNumberService onlineSaleShipmentNumberService;
    private final OnlineSaleReturnsWarehouseLocator returnsWarehouseLocator;
    private final ProductInventoryService productInventoryService;

    private static final Map<String, String> PAYMENT_METHOD_DISPLAY = new LinkedHashMap<>();
    static {
        PAYMENT_METHOD_DISPLAY.put("TRANSFERENCIA_PENDIENTE", "Transferencia Pendiente");
        PAYMENT_METHOD_DISPLAY.put("TRANSFERENCIA_LISTA",     "Transferencia Lista");
        PAYMENT_METHOD_DISPLAY.put("VISALINK_PAGADO",         "Visalink");
        PAYMENT_METHOD_DISPLAY.put("VISALINK_PENDIENTE",      "Visalink Pendiente");
        // Tarjeta física (débito/crédito). Se muestra como TD/TC para distinguirla de Visalink.
        PAYMENT_METHOD_DISPLAY.put("TARJETA_PAGADO",          "TD/TC");
        PAYMENT_METHOD_DISPLAY.put("DEPOSITO_LISTO",          "Depósito Listo");
        PAYMENT_METHOD_DISPLAY.put("DEPOSITO_PENDIENTE",      "Depósito Pendiente");
        PAYMENT_METHOD_DISPLAY.put("CONTRA_ENTREGA",          "Contra Entrega");
        PAYMENT_METHOD_DISPLAY.put("CONTRA_ENTREGA_DEPOSITO", "Contra Entrega Depósito");
    }

    // ─── CRUD ───────────────────────────────────────────────────────

    public OnlineSaleResponse create(OnlineSaleRequest req) {
        OnlineSaleEntity entity = mapToEntity(new OnlineSaleEntity(), req);
        // Estado inicial siempre automático al crear desde formulario/API
        entity.setStatus("PENDIENTE");

        // Generar número correlativo del día
        long count = saleRepository.countBySaleDate(entity.getSaleDate()) + 1;
        entity.setSaleNumber(String.valueOf(count));
        entity.setCreatedBy(securityUtil.getCurrentUserId());

        // Si vienen items, calcular totalAmount a partir de ellos
        List<OnlineSaleRequest.SaleItemRequest> itemReqs = req.getItems();
        boolean hasItems = itemReqs != null && !itemReqs.isEmpty();

        if (hasItems) {
            // netAmount = suma de subtotales (precios sin envío)
            BigDecimal net = BigDecimal.ZERO;
            for (OnlineSaleRequest.SaleItemRequest ir : itemReqs) {
                BigDecimal price = ir.getUnitPrice() != null ? ir.getUnitPrice() : BigDecimal.ZERO;
                int qty = ir.getQuantity() != null ? ir.getQuantity() : 1;
                net = net.add(price.multiply(BigDecimal.valueOf(qty)));
            }
            entity.setNetAmount(net);
            // totalAmount se calcula en @PrePersist: net + shipping (o se fuerza si viene shippingCost custom)
            copyFirstItemToLegacy(entity, itemReqs.get(0));
        } else if (req.getProductId() != null) {
            // Legacy: un solo producto
            enrichWithProductAndColor(entity, req.getProductId(), req.getColorId(), req.getUnitPrice());
            if (entity.getNetAmount() == null && entity.getUnitPrice() != null && entity.getQuantity() != null) {
                entity.setNetAmount(entity.getUnitPrice().multiply(BigDecimal.valueOf(entity.getQuantity())));
            }
        }

        // Si el request trae shippingCost (editable en formulario), respetarlo y recalcular total.
        // Esto evita que @PrePersist lo sobrescriba con la regla fija Q30/Q15.
        if (req.getShippingCost() != null && entity.getNetAmount() != null) {
            entity.setShippingCost(req.getShippingCost());
            entity.setTotalAmount(entity.getNetAmount().add(req.getShippingCost()));
            entity.setSkipAmountCalculation(true);
        }

        OnlineSaleEntity saved = saleRepository.save(entity);

        // Guardar items
        if (hasItems) {
            saveItems(saved.getId(), itemReqs);
        } else if (req.getProductId() != null) {
            // Crear un item legacy
            saveSingleItemFromLegacy(saved);
        }

        return toResponse(saved);
    }

    public OnlineSaleResponse update(Long id, OnlineSaleRequest req) throws ResourceNotFoundException {
        OnlineSaleEntity entity = saleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Venta no encontrada con id: " + id));

        mapToEntity(entity, req);

        List<OnlineSaleRequest.SaleItemRequest> itemReqs = req.getItems();
        boolean hasItems = itemReqs != null && !itemReqs.isEmpty();

        if (hasItems) {
            // Borrar items anteriores y recrear
            itemRepository.deleteByOnlineSaleId(id);
            BigDecimal net = BigDecimal.ZERO;
            for (OnlineSaleRequest.SaleItemRequest ir : itemReqs) {
                BigDecimal price = ir.getUnitPrice() != null ? ir.getUnitPrice() : BigDecimal.ZERO;
                int qty = ir.getQuantity() != null ? ir.getQuantity() : 1;
                net = net.add(price.multiply(BigDecimal.valueOf(qty)));
            }
            entity.setNetAmount(net);
            copyFirstItemToLegacy(entity, itemReqs.get(0));
            saveItems(id, itemReqs);
        } else {
            // Actualización parcial (inline edit) — no tocar items
            if (req.getProductId() != null) {
                enrichWithProductAndColor(entity, req.getProductId(), req.getColorId(), req.getUnitPrice());
            }
            if (entity.getUnitPrice() != null && entity.getQuantity() != null) {
                entity.setNetAmount(entity.getUnitPrice().multiply(BigDecimal.valueOf(entity.getQuantity())));
            }
        }

        // Si el request trae shippingCost, respetarlo y recalcular total.
        if (req.getShippingCost() != null && entity.getNetAmount() != null) {
            entity.setShippingCost(req.getShippingCost());
            entity.setTotalAmount(entity.getNetAmount().add(req.getShippingCost()));
            entity.setSkipAmountCalculation(true);
        }

        entity.setUpdatedBy(securityUtil.getCurrentUserId());
        OnlineSaleEntity saved = saleRepository.save(entity);
        if (saved.getGuideNumber() != null && !saved.getGuideNumber().isBlank()
                && saved.getShippingCarrier() != null && !saved.getShippingCarrier().isBlank()) {
            String prevShipment = saved.getShipmentNumber();
            onlineSaleShipmentNumberService.assignIfMissing(saved);
            if (!Objects.equals(prevShipment, saved.getShipmentNumber())) {
                saved = saleRepository.save(saved);
            }
        }
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public OnlineSaleResponse getById(Long id) throws ResourceNotFoundException {
        OnlineSaleEntity entity = saleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Venta no encontrada con id: " + id));
        return toResponse(entity);
    }

    @Transactional(readOnly = true)
    public List<OnlineSaleResponse> getAll() {
        return saleRepository.findAll().stream()
                .sorted(Comparator.comparing(OnlineSaleEntity::getSaleDate).reversed()
                        .thenComparing(OnlineSaleEntity::getId))
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public void delete(Long id) throws ResourceNotFoundException {
        if (!saleRepository.existsById(id)) {
            throw new ResourceNotFoundException("Venta no encontrada con id: " + id);
        }
        itemRepository.deleteByOnlineSaleId(id);
        saleRepository.deleteById(id);
    }

    // ─── Filtros ────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<OnlineSaleResponse> getByDate(LocalDate date) {
        return saleRepository.findBySaleDateOrderByIdAsc(date).stream()
                .map(this::toResponse).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<OnlineSaleResponse> getBySalesperson(String salesperson) {
        return saleRepository.findBySalespersonOrderBySaleDateDesc(salesperson).stream()
                .map(this::toResponse).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<OnlineSaleResponse> getByDateAndSalesperson(LocalDate date, String salesperson) {
        return saleRepository.findBySaleDateAndSalespersonOrderByIdAsc(date, salesperson).stream()
                .map(this::toResponse).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<OnlineSaleResponse> getByDateRange(LocalDate startDate, LocalDate endDate) {
        return saleRepository.findBySaleDateBetweenOrderBySaleDateDesc(startDate, endDate).stream()
                .map(this::toResponse).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<OnlineSaleResponse> getEligibleForProduction() {
        return saleRepository.findEligibleForProduction().stream()
                .map(this::toResponse).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<OnlineSaleResponse> getEligibleForProduction(LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null) {
            return saleRepository.findEligibleForProductionByDateRange(startDate, endDate).stream()
                    .map(this::toResponse).collect(Collectors.toList());
        }
        return getEligibleForProduction();
    }

    // ─── Resumen Diario ─────────────────────────────────────────────

    @Transactional(readOnly = true)
    public OnlineSaleDailySummaryResponse getDailySummary(LocalDate date) {
        List<OnlineSaleEntity> sales = saleRepository.findBySaleDateOrderByIdAsc(date);

        Map<String, List<OnlineSaleEntity>> bySeller = sales.stream()
                .filter(s -> s.getSalesperson() != null)
                .collect(Collectors.groupingBy(OnlineSaleEntity::getSalesperson));

        List<OnlineSaleDailySummaryResponse.SellerSummary> sellerSummaries = bySeller.entrySet().stream()
                .map(e -> OnlineSaleDailySummaryResponse.SellerSummary.builder()
                        .salesperson(e.getKey())
                        .salesCount(e.getValue().size())
                        .totalAmount(e.getValue().stream()
                                .map(s -> s.getTotalAmount() != null ? s.getTotalAmount() : BigDecimal.ZERO)
                                .reduce(BigDecimal.ZERO, BigDecimal::add))
                        .totalNetAmount(e.getValue().stream()
                                .map(s -> s.getNetAmount() != null ? s.getNetAmount() : BigDecimal.ZERO)
                                .reduce(BigDecimal.ZERO, BigDecimal::add))
                        .build())
                .collect(Collectors.toList());

        // Por red social con montos
        List<OnlineSaleDailySummaryResponse.GroupSummary> socialSummaries = sales.stream()
                .filter(s -> s.getSocialNetwork() != null)
                .collect(Collectors.groupingBy(OnlineSaleEntity::getSocialNetwork))
                .entrySet().stream()
                .map(e -> OnlineSaleDailySummaryResponse.GroupSummary.builder()
                        .name(e.getKey())
                        .salesCount(e.getValue().size())
                        .totalAmount(e.getValue().stream()
                                .map(s -> s.getTotalAmount() != null ? s.getTotalAmount() : BigDecimal.ZERO)
                                .reduce(BigDecimal.ZERO, BigDecimal::add))
                        .totalNetAmount(e.getValue().stream()
                                .map(s -> s.getNetAmount() != null ? s.getNetAmount() : BigDecimal.ZERO)
                                .reduce(BigDecimal.ZERO, BigDecimal::add))
                        .build())
                .collect(Collectors.toList());

        // Por forma de pago con montos
        List<OnlineSaleDailySummaryResponse.GroupSummary> paymentSummaries = sales.stream()
                .filter(s -> s.getPaymentMethod() != null)
                .collect(Collectors.groupingBy(s -> PAYMENT_METHOD_DISPLAY.getOrDefault(s.getPaymentMethod(), s.getPaymentMethod())))
                .entrySet().stream()
                .map(e -> OnlineSaleDailySummaryResponse.GroupSummary.builder()
                        .name(e.getKey())
                        .salesCount(e.getValue().size())
                        .totalAmount(e.getValue().stream()
                                .map(s -> s.getTotalAmount() != null ? s.getTotalAmount() : BigDecimal.ZERO)
                                .reduce(BigDecimal.ZERO, BigDecimal::add))
                        .totalNetAmount(e.getValue().stream()
                                .map(s -> s.getNetAmount() != null ? s.getNetAmount() : BigDecimal.ZERO)
                                .reduce(BigDecimal.ZERO, BigDecimal::add))
                        .build())
                .collect(Collectors.toList());

        Map<String, Integer> byStatus = sales.stream()
                .filter(s -> s.getStatus() != null)
                .collect(Collectors.groupingBy(OnlineSaleEntity::getStatus,
                        Collectors.collectingAndThen(Collectors.counting(), Long::intValue)));

        BigDecimal totalAmt = sales.stream()
                .map(s -> s.getTotalAmount() != null ? s.getTotalAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalNet = sales.stream()
                .map(s -> s.getNetAmount() != null ? s.getNetAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return OnlineSaleDailySummaryResponse.builder()
                .date(date)
                .totalSalesCount(sales.size())
                .totalAmount(totalAmt)
                .totalNetAmount(totalNet)
                .bySeller(sellerSummaries)
                .bySocialNetwork(socialSummaries)
                .byPaymentMethod(paymentSummaries)
                .byStatus(byStatus)
                .build();
    }

    // ─── Importación masiva ────────────────────────────────────────

    public Map<String, Object> importBatch(List<OnlineSaleRequest> requests) {
        int imported = 0;
        int skipped = 0;
        int errors = 0;
        List<String> errorMessages = new ArrayList<>();

        for (OnlineSaleRequest req : requests) {
            try {
                // Deduplicación: saltar si ya existe venta con mismo número y fecha
                if (req.getSaleNumber() != null && req.getSaleDate() != null
                        && saleRepository.existsBySaleNumberAndSaleDate(req.getSaleNumber(), req.getSaleDate())) {
                    skipped++;
                    continue;
                }

                OnlineSaleEntity entity = mapToEntity(new OnlineSaleEntity(), req);

                // Usar número proporcionado o auto-generar
                if (req.getSaleNumber() != null && !req.getSaleNumber().isBlank()) {
                    entity.setSaleNumber(req.getSaleNumber());
                } else {
                    long count = saleRepository.countBySaleDate(entity.getSaleDate()) + 1;
                    entity.setSaleNumber(String.valueOf(count));
                }

                // Establecer montos pre-calculados del CSV
                if (req.getShippingCost() != null) entity.setShippingCost(req.getShippingCost());
                if (req.getNetAmount() != null) entity.setNetAmount(req.getNetAmount());
                if (req.getTotalAmount() != null) entity.setTotalAmount(req.getTotalAmount());

                // Mantener importación operativa:
                // no marcar en producción automáticamente y respetar estado del CSV.
                if (entity.getStatus() == null || entity.getStatus().isBlank()) {
                    entity.setStatus("PENDIENTE");
                }

                entity.setCreatedBy(securityUtil.getCurrentUserId());

                // Calcular netAmount a partir de items si no viene
                List<OnlineSaleRequest.SaleItemRequest> items = req.getItems();
                boolean hasItems = items != null && !items.isEmpty();
                if (hasItems && entity.getNetAmount() == null) {
                    BigDecimal net = BigDecimal.ZERO;
                    for (var ir : items) {
                        BigDecimal price = ir.getUnitPrice() != null ? ir.getUnitPrice() : BigDecimal.ZERO;
                        int qty = ir.getQuantity() != null ? ir.getQuantity() : 1;
                        net = net.add(price.multiply(BigDecimal.valueOf(qty)));
                    }
                    entity.setNetAmount(net);
                }

                // Recalcular totalAmount si no viene: net + envío
                if (entity.getTotalAmount() == null && entity.getNetAmount() != null) {
                    BigDecimal ship = entity.getShippingCost() != null ? entity.getShippingCost() : BigDecimal.ZERO;
                    entity.setTotalAmount(entity.getNetAmount().add(ship));
                }

                // Evitar que @PrePersist sobrescriba los montos del CSV
                entity.setSkipAmountCalculation(true);

                OnlineSaleEntity saved = saleRepository.save(entity);

                // Guardar items
                if (hasItems) {
                    saveImportItems(saved.getId(), items);
                }

                imported++;
            } catch (Exception e) {
                errors++;
                errorMessages.add("Fila " + (req.getSaleNumber() != null ? req.getSaleNumber() : "?")
                        + ": " + e.getMessage());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("imported", imported);
        result.put("skipped", skipped);
        result.put("errors", errors);
        result.put("total", requests.size());
        if (!errorMessages.isEmpty()) {
            result.put("errorMessages", errorMessages.subList(0, Math.min(10, errorMessages.size())));
        }
        return result;
    }

    private void saveImportItems(Long saleId, List<OnlineSaleRequest.SaleItemRequest> itemReqs) {
        for (var ir : itemReqs) {
            OnlineSaleItemEntity item = OnlineSaleItemEntity.builder()
                    .onlineSaleId(saleId)
                    .productId(ir.getProductId())
                    .colorId(ir.getColorId())
                    .size(ir.getSize())
                    .quantity(ir.getQuantity() != null ? ir.getQuantity() : 1)
                    .unitPrice(ir.getUnitPrice() != null ? ir.getUnitPrice() : BigDecimal.ZERO)
                    .build();

            // Resolver producto desde DB o usar nombre directo
            if (ir.getProductId() != null) {
                ProductEntity product = productRepository.findById(ir.getProductId()).orElse(null);
                if (product != null) {
                    item.setProductCode(product.getCode());
                    item.setProductName(product.getName());
                }
            } else {
                item.setProductName(ir.getProductName());
                item.setProductCode(ir.getProductCode());
            }

            // Resolver color desde DB o usar nombre directo
            if (ir.getColorId() != null) {
                ColorEntity color = colorRepository.findById(ir.getColorId()).orElse(null);
                if (color != null) {
                    item.setColorName(color.getName());
                }
            } else if (ir.getColorName() != null) {
                item.setColorName(ir.getColorName());
            }

            itemRepository.save(item);
        }
    }

    // ─── Devolver / Anular ─────────────────────────────────────────

    /**
     * Marcar una venta como DEVOLUCION.
     * Solo permitido si el estado actual es ENVIADO o ENTREGADO.
     * Incrementa stock en la ubicación de devoluciones y crea registros de historial / documento imprimible.
     */
    public OnlineSaleResponse markAsReturn(Long id, String reason, String itemCondition) throws ResourceNotFoundException, BusinessException {
        OnlineSaleEntity sale = saleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Venta no encontrada con id: " + id));

        if (!"ENVIADO".equals(sale.getStatus()) && !"ENTREGADO".equals(sale.getStatus())) {
            throw new BusinessException("Solo se pueden devolver ventas en estado ENVIADO o ENTREGADO. Estado actual: " + sale.getStatus());
        }

        onlineSaleShipmentNumberService.assignIfMissing(sale);

        LocationEntity returnsLocation = returnsWarehouseLocator.find()
                .orElseThrow(() -> new BusinessException(
                        "No está configurada la ubicación de inventario de devoluciones "
                                + "(active un tipo DEVOLUCION / BODEGA_DEVOLUCIONES en inventory_location_type "
                                + "y cree la ubicación con el mismo código)."));

        String condition = (itemCondition != null && !itemCondition.isBlank()) ? itemCondition : "BUENO";
        List<OnlineSaleItemEntity> items = itemRepository.findByOnlineSaleIdOrderByIdAsc(id);

        if (items != null && !items.isEmpty()) {
            for (OnlineSaleItemEntity item : items) {
                incrementReturnPhysicalStock(sale, returnsLocation, item);
            }
        } else {
            incrementReturnPhysicalStock(
                    sale,
                    returnsLocation,
                    sale.getProductId(),
                    sale.getColorId(),
                    sale.getSize(),
                    sale.getQuantity());
        }

        sale.setStatus("DEVOLUCION");
        sale.setObservations((sale.getObservations() != null ? sale.getObservations() + " | " : "")
                + "DEVOLUCIÓN: " + (reason != null ? reason : "Sin razón especificada"));
        sale.setUpdatedBy(securityUtil.getCurrentUserId());
        saleRepository.save(sale);

        OnlineSaleReturnEntity header = OnlineSaleReturnEntity.builder()
                .onlineSaleId(id)
                .relatedShipmentNumber(sale.getShipmentNumber())
                .returnReason(reason)
                .itemCondition(condition)
                .createdBy(securityUtil.getCurrentUserId())
                .build();
        OnlineSaleReturnEntity savedHeader = onlineSaleReturnRepository.save(header);

        if (items != null && !items.isEmpty()) {
            for (OnlineSaleItemEntity item : items) {
                OnlineSaleReturnLineEntity line = OnlineSaleReturnLineEntity.builder()
                        .returnId(savedHeader.getId())
                        .productId(item.getProductId())
                        .productCode(item.getProductCode())
                        .productName(item.getProductName())
                        .colorId(item.getColorId())
                        .colorName(item.getColorName())
                        .size(item.getSize())
                        .quantity(item.getQuantity())
                        .unitPrice(item.getUnitPrice())
                        .build();
                onlineSaleReturnLineRepository.save(line);

                ReturnInventoryEntity returnEntry = ReturnInventoryEntity.builder()
                        .onlineSaleId(id)
                        .productId(item.getProductId())
                        .productCode(item.getProductCode())
                        .productName(item.getProductName())
                        .colorId(item.getColorId())
                        .colorName(item.getColorName())
                        .size(item.getSize())
                        .quantity(item.getQuantity())
                        .unitPrice(item.getUnitPrice())
                        .returnDate(LocalDate.now())
                        .returnReason(reason)
                        .itemCondition(condition)
                        .createdBy(securityUtil.getCurrentUserId())
                        .build();
                returnInventoryRepository.save(returnEntry);
            }
        } else {
            onlineSaleReturnLineRepository.save(OnlineSaleReturnLineEntity.builder()
                    .returnId(savedHeader.getId())
                    .productId(sale.getProductId())
                    .productCode(sale.getProductCode())
                    .productName(sale.getProductName())
                    .colorId(sale.getColorId())
                    .colorName(sale.getColorName())
                    .size(sale.getSize())
                    .quantity(sale.getQuantity())
                    .unitPrice(sale.getUnitPrice())
                    .build());

            ReturnInventoryEntity returnEntry = ReturnInventoryEntity.builder()
                    .onlineSaleId(id)
                    .productId(sale.getProductId())
                    .productCode(sale.getProductCode())
                    .productName(sale.getProductName())
                    .colorId(sale.getColorId())
                    .colorName(sale.getColorName())
                    .size(sale.getSize())
                    .quantity(sale.getQuantity())
                    .unitPrice(sale.getUnitPrice())
                    .returnDate(LocalDate.now())
                    .returnReason(reason)
                    .itemCondition(condition)
                    .createdBy(securityUtil.getCurrentUserId())
                    .build();
            returnInventoryRepository.save(returnEntry);
        }

        return toResponse(sale);
    }

    private void incrementReturnPhysicalStock(OnlineSaleEntity sale, LocationEntity returnsLocation, OnlineSaleItemEntity item)
            throws BusinessException {
        incrementReturnPhysicalStock(
                sale,
                returnsLocation,
                item.getProductId(),
                item.getColorId(),
                item.getSize(),
                item.getQuantity());
    }

    private void incrementReturnPhysicalStock(
            OnlineSaleEntity sale,
            LocationEntity returnsLocation,
            Long productId,
            Long colorId,
            String size,
            Integer quantity) throws BusinessException {
        if (productId == null) {
            return;
        }
        int q = quantity != null ? quantity : 1;
        if (q <= 0) {
            return;
        }
        String saleRef = sale.getSaleNumber() != null ? sale.getSaleNumber() : String.valueOf(sale.getId());
        String desc = "Devolución venta online #" + saleRef;
        try {
            productInventoryService.incrementInventory(
                    productId,
                    returnsLocation.getId(),
                    colorId,
                    BigDecimal.valueOf(q),
                    null,
                    "ONLINE_SALE_RETURN",
                    sale.getId(),
                    sale.getSaleNumber(),
                    desc,
                    size);
        } catch (ResourceNotFoundException e) {
            throw new BusinessException("No se pudo ingresar stock en devoluciones: " + e.getMessage());
        }
    }

    /**
     * Marcar una venta como ANULADA.
     * No permitido si ya está en DEVOLUCION.
     * Si está ENVIADO/ENTREGADO, debe ser devolución, no anulación.
     */
    public OnlineSaleResponse markAsVoid(Long id, String reason) throws ResourceNotFoundException, BusinessException {
        OnlineSaleEntity sale = saleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Venta no encontrada con id: " + id));

        if ("DEVOLUCION".equals(sale.getStatus())) {
            throw new BusinessException("No se puede anular una venta que ya fue devuelta");
        }
        if ("ANULADA".equals(sale.getStatus())) {
            throw new BusinessException("La venta ya está anulada");
        }
        if ("ENVIADO".equals(sale.getStatus()) || "ENTREGADO".equals(sale.getStatus())) {
            throw new BusinessException("La venta ya fue enviada/entregada. Use DEVOLUCIÓN en su lugar.");
        }

        sale.setStatus("ANULADA");
        sale.setObservations((sale.getObservations() != null ? sale.getObservations() + " | " : "")
                + "ANULADA: " + (reason != null ? reason : "Sin razón especificada"));
        sale.setUpdatedBy(securityUtil.getCurrentUserId());
        saleRepository.save(sale);

        return toResponse(sale);
    }

    public OnlineSaleResponse registerShipment(Long id, String guideNumber, String shippingCarrier, String observations)
            throws ResourceNotFoundException, BusinessException {
        OnlineSaleEntity sale = saleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Venta no encontrada con id: " + id));

        if ("DEVOLUCION".equals(sale.getStatus()) || "ANULADA".equals(sale.getStatus()) || "CANCELADO".equals(sale.getStatus())) {
            throw new BusinessException("No se puede registrar envío para una venta en estado " + sale.getStatus());
        }

        boolean hasGuide = guideNumber != null && !guideNumber.isBlank();
        boolean hasCarrier = shippingCarrier != null && !shippingCarrier.isBlank();
        if (hasGuide) {
            sale.setGuideNumber(guideNumber.trim());
        }
        if (hasCarrier) {
            sale.setShippingCarrier(shippingCarrier.trim());
        }
        if (observations != null && !observations.isBlank()) {
            String nextObs = observations.trim();
            sale.setObservations((sale.getObservations() != null && !sale.getObservations().isBlank())
                    ? sale.getObservations() + " | ENVIO_PREP: " + nextObs
                    : "ENVIO_PREP: " + nextObs);
        }

        // Número ENVL y datos de guía/transporte = preparación antes de producción/bodega.
        // No cambia el estado de la venta; ENVIADO solo al despacho real (p. ej. bodega PT).
        if (hasGuide || hasCarrier) {
            onlineSaleShipmentNumberService.assignIfMissing(sale);
        }

        sale.setUpdatedBy(securityUtil.getCurrentUserId());
        saleRepository.save(sale);
        return toResponse(sale);
    }

    /**
     * Crear un CAMBIO (nuevo envío a Q0) relacionado a una venta original.
     * Genera un nuevo shipmentNumber (ENVL-*) y guarda items con precio 0.
     */
    public OnlineSaleResponse createExchange(Long originalOnlineSaleId, OnlineSaleExchangeRequest req)
            throws ResourceNotFoundException, BusinessException {
        OnlineSaleEntity original = saleRepository.findById(originalOnlineSaleId)
                .orElseThrow(() -> new ResourceNotFoundException("Online Sale", originalOnlineSaleId));

        List<OnlineSaleExchangeRequest.ExchangeItemRequest> itemsReq = req != null ? req.getItems() : null;
        List<OnlineSaleExchangeRequest.ExchangeItemRequest> safeItems = (itemsReq == null) ? List.of() : itemsReq;
        List<OnlineSaleExchangeRequest.ExchangeItemRequest> normalizedItems = safeItems.stream()
                .filter(it -> it != null && it.getProductId() != null && (it.getQuantity() == null || it.getQuantity() > 0))
                .toList();
        if (normalizedItems.isEmpty()) {
            throw new BusinessException("Debe especificar al menos un item para el CAMBIO");
        }

        OnlineSaleEntity exchange = new OnlineSaleEntity();
        exchange.setSkipAmountCalculation(true);
        exchange.setStatus("PENDIENTE");
        exchange.setInProductionOrder(false);
        exchange.setProductionOrderId(null);
        exchange.setSaleDate(LocalDate.now());

        exchange.setCustomerName(original.getCustomerName());
        exchange.setAddress(original.getAddress());
        exchange.setPhone(original.getPhone());
        exchange.setPhone2(original.getPhone2());
        exchange.setPackaging(original.getPackaging());
        exchange.setInvoiceTaxId(original.getInvoiceTaxId());
        exchange.setSocialNetwork(original.getSocialNetwork());
        exchange.setEmail(original.getEmail());
        exchange.setSalesperson(original.getSalesperson());

        // Pago ya fue realizado en la venta original → este envío es Q0
        exchange.setPaymentMethod(original.getPaymentMethod());
        exchange.setNetAmount(BigDecimal.ZERO);
        exchange.setShippingCost(BigDecimal.ZERO);
        exchange.setTotalAmount(BigDecimal.ZERO);

        if (req != null && req.getShippingCarrier() != null && !req.getShippingCarrier().isBlank()) {
            exchange.setShippingCarrier(req.getShippingCarrier().trim());
        } else {
            exchange.setShippingCarrier(original.getShippingCarrier());
        }
        if (req != null && req.getGuideNumber() != null && !req.getGuideNumber().isBlank()) {
            exchange.setGuideNumber(req.getGuideNumber().trim());
        }

        String baseObs = original.getObservations() != null ? original.getObservations() : "";
        String rel = original.getShipmentNumber() != null ? ("ENVIO_REL=" + original.getShipmentNumber()) : ("VENTA_REL=" + original.getId());
        String changeTag = "CAMBIO_Q0(" + rel + ")";
        String extra = (req != null && req.getObservations() != null && !req.getObservations().isBlank())
                ? " " + req.getObservations().trim()
                : "";
        exchange.setObservations((baseObs.isBlank() ? "" : baseObs + " | ") + changeTag + extra);

        exchange.setCreatedBy(securityUtil.getCurrentUserId());

        // Generar número correlativo del día (igual que create)
        long count = saleRepository.countBySaleDate(exchange.getSaleDate()) + 1;
        exchange.setSaleNumber(String.valueOf(count));

        OnlineSaleEntity saved = saleRepository.save(exchange);

        // Guardar items con precio 0, pero mantener producto/color/talla/cantidad
        for (OnlineSaleExchangeRequest.ExchangeItemRequest ir : normalizedItems) {
            OnlineSaleItemEntity item = OnlineSaleItemEntity.builder()
                    .onlineSaleId(saved.getId())
                    .productId(ir.getProductId())
                    .colorId(ir.getColorId())
                    .size(ir.getSize())
                    .quantity(ir.getQuantity() != null ? ir.getQuantity() : 1)
                    .unitPrice(BigDecimal.ZERO)
                    .build();

            enrichItemWithProductAndColor(item);
            itemRepository.save(item);
        }

        // Asegurar shipmentNumber nuevo para imprimir/paquetería
        onlineSaleShipmentNumberService.assignIfMissing(saved);
        saved.setUpdatedBy(securityUtil.getCurrentUserId());
        saleRepository.save(saved);

        return toResponse(saved);
    }

    private void enrichItemWithProductAndColor(OnlineSaleItemEntity item) {
        if (item.getProductId() != null) {
            ProductEntity product = productRepository.findById(item.getProductId()).orElse(null);
            if (product != null) {
                item.setProductCode(product.getCode());
                item.setProductName(product.getName());
            }
        }
        if (item.getColorId() != null) {
            ColorEntity color = colorRepository.findById(item.getColorId()).orElse(null);
            if (color != null) {
                item.setColorName(color.getName());
            }
        }
    }

    /**
     * Obtener inventario de devoluciones por rango de fechas.
     */
    @Transactional(readOnly = true)
    public List<ReturnInventoryEntity> getReturnInventory(LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null) {
            return returnInventoryRepository.findByReturnDateBetweenOrderByReturnDateDesc(startDate, endDate);
        }
        return returnInventoryRepository.findAllByOrderByReturnDateDesc();
    }

    @Transactional(readOnly = true)
    public List<OnlineSaleReturnEntity> getReturnEvents(LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null) {
            LocalDateTime start = startDate.atStartOfDay();
            LocalDateTime end = endDate.plusDays(1).atStartOfDay().minusNanos(1);
            return onlineSaleReturnRepository.findByCreatedAtBetweenOrderByIdDesc(start, end);
        }
        return onlineSaleReturnRepository.findAllByOrderByIdDesc();
    }

    @Transactional(readOnly = true)
    public OnlineSaleReturnPrintResponse getReturnForPrint(Long returnId) throws ResourceNotFoundException {
        OnlineSaleReturnEntity header = onlineSaleReturnRepository.findById(returnId)
                .orElseThrow(() -> new ResourceNotFoundException("Return", returnId));
        OnlineSaleEntity sale = saleRepository.findById(header.getOnlineSaleId())
                .orElseThrow(() -> new ResourceNotFoundException("Online Sale", header.getOnlineSaleId()));

        List<OnlineSaleReturnLineEntity> lines = onlineSaleReturnLineRepository.findByReturnIdOrderByIdAsc(returnId);
        if (lines == null) lines = List.of();
        List<OnlineSaleReturnPrintResponse.ReturnLine> items = lines.stream()
                .map(l -> OnlineSaleReturnPrintResponse.ReturnLine.builder()
                        .productId(l.getProductId())
                        .productCode(l.getProductCode())
                        .productName(l.getProductName())
                        .colorId(l.getColorId())
                        .colorName(l.getColorName())
                        .size(l.getSize())
                        .quantity(l.getQuantity())
                        .unitPrice(l.getUnitPrice())
                        .subtotal(l.getSubtotal())
                        .build())
                .toList();

        BigDecimal total = items.stream()
                .map(it -> it.getSubtotal() != null ? it.getSubtotal() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        LocalDate returnDate = header.getCreatedAt() != null ? header.getCreatedAt().toLocalDate() : LocalDate.now();
        return OnlineSaleReturnPrintResponse.builder()
                .returnId(header.getId())
                .onlineSaleId(sale.getId())
                .saleNumber(sale.getSaleNumber())
                .relatedShipmentNumber(header.getRelatedShipmentNumber())
                .customerName(sale.getCustomerName())
                .address(sale.getAddress())
                .phone(sale.getPhone())
                .phone2(sale.getPhone2())
                .returnReason(header.getReturnReason())
                .itemCondition(header.getItemCondition())
                .returnDate(returnDate)
                .totalAmount(total)
                .items(items)
                .build();
    }

    // ─── Vincular a OP ──────────────────────────────────────────────

    public void linkToProductionOrder(List<Long> saleIds, Long productionOrderId) {
        List<OnlineSaleEntity> sales = saleRepository.findAllById(saleIds);
        for (OnlineSaleEntity sale : sales) {
            sale.setInProductionOrder(true);
            sale.setProductionOrderId(productionOrderId);
            sale.setStatus("EN_PRODUCCION");
        }
        saleRepository.saveAll(sales);
    }

    // ─── Helpers ────────────────────────────────────────────────────

    private void saveItems(Long saleId, List<OnlineSaleRequest.SaleItemRequest> itemReqs) {
        for (OnlineSaleRequest.SaleItemRequest ir : itemReqs) {
            OnlineSaleItemEntity item = OnlineSaleItemEntity.builder()
                    .onlineSaleId(saleId)
                    .productId(ir.getProductId())
                    .colorId(ir.getColorId())
                    .size(ir.getSize())
                    .quantity(ir.getQuantity() != null ? ir.getQuantity() : 1)
                    .unitPrice(ir.getUnitPrice() != null ? ir.getUnitPrice() : BigDecimal.ZERO)
                    .build();

            // Resolver producto
            if (ir.getProductId() != null) {
                ProductEntity product = productRepository.findById(ir.getProductId()).orElse(null);
                if (product != null) {
                    item.setProductCode(product.getCode());
                    item.setProductName(product.getName());
                    if (ir.getUnitPrice() == null && product.getSalePrice() != null) {
                        item.setUnitPrice(product.getSalePrice());
                    }
                }
            }
            // Resolver color
            if (ir.getColorId() != null) {
                ColorEntity color = colorRepository.findById(ir.getColorId()).orElse(null);
                if (color != null) {
                    item.setColorName(color.getName());
                }
            }

            itemRepository.save(item);
        }
    }

    private void saveSingleItemFromLegacy(OnlineSaleEntity sale) {
        OnlineSaleItemEntity item = OnlineSaleItemEntity.builder()
                .onlineSaleId(sale.getId())
                .productId(sale.getProductId())
                .productCode(sale.getProductCode())
                .productName(sale.getProductName())
                .colorId(sale.getColorId())
                .colorName(sale.getColorName())
                .size(sale.getSize())
                .quantity(sale.getQuantity() != null ? sale.getQuantity() : 1)
                .unitPrice(sale.getUnitPrice() != null ? sale.getUnitPrice() : BigDecimal.ZERO)
                .build();
        itemRepository.save(item);
    }

    private void copyFirstItemToLegacy(OnlineSaleEntity entity, OnlineSaleRequest.SaleItemRequest first) {
        // Copiar primer producto a campos legacy para compatibilidad
        entity.setProductId(first.getProductId());
        entity.setColorId(first.getColorId());
        entity.setSize(first.getSize());
        entity.setQuantity(first.getQuantity() != null ? first.getQuantity() : 1);
        entity.setUnitPrice(first.getUnitPrice());
        enrichWithProductAndColor(entity, first.getProductId(), first.getColorId(), first.getUnitPrice());
    }

    private void enrichWithProductAndColor(OnlineSaleEntity entity, Long productId, Long colorId, BigDecimal overridePrice) {
        if (productId != null) {
            ProductEntity product = productRepository.findById(productId).orElse(null);
            if (product != null) {
                entity.setProductCode(product.getCode());
                entity.setProductName(product.getName());
                if (overridePrice == null && product.getSalePrice() != null) {
                    entity.setUnitPrice(product.getSalePrice());
                }
            }
        }
        if (colorId != null) {
            ColorEntity color = colorRepository.findById(colorId).orElse(null);
            if (color != null) {
                entity.setColorName(color.getName());
            }
        }
    }

    private OnlineSaleEntity mapToEntity(OnlineSaleEntity entity, OnlineSaleRequest req) {
        if (req.getCustomerName() != null) entity.setCustomerName(req.getCustomerName());
        if (req.getAddress() != null) entity.setAddress(req.getAddress());
        if (req.getPhone() != null) entity.setPhone(req.getPhone());
        if (req.getPhone2() != null) entity.setPhone2(req.getPhone2());
        if (req.getPackaging() != null) entity.setPackaging(req.getPackaging());
        if (req.getPaymentMethod() != null) entity.setPaymentMethod(req.getPaymentMethod());
        if (req.getInvoiceTaxId() != null) entity.setInvoiceTaxId(req.getInvoiceTaxId());
        if (req.getProductId() != null) entity.setProductId(req.getProductId());
        if (req.getColorId() != null) entity.setColorId(req.getColorId());
        if (req.getSize() != null) entity.setSize(req.getSize());
        if (req.getQuantity() != null) entity.setQuantity(req.getQuantity());
        if (req.getUnitPrice() != null) entity.setUnitPrice(req.getUnitPrice());
        // totalAmount NO se setea desde el request; se calcula automáticamente: net + envío
        if (req.getShippingCarrier() != null) entity.setShippingCarrier(req.getShippingCarrier());
        if (req.getSaleDate() != null) entity.setSaleDate(req.getSaleDate());
        else if (entity.getSaleDate() == null) entity.setSaleDate(LocalDate.now());
        if (req.getSocialNetwork() != null) entity.setSocialNetwork(req.getSocialNetwork());
        if (req.getEmail() != null) entity.setEmail(req.getEmail());
        if (req.getGuideNumber() != null) entity.setGuideNumber(req.getGuideNumber());
        if (req.getPaymentAuthorization() != null) entity.setPaymentAuthorization(req.getPaymentAuthorization());
        // El estado de la venta se controla por flujo del sistema (producción/distribución/QA),
        // no por edición manual desde formularios genéricos.
        if (req.getObservations() != null) entity.setObservations(req.getObservations());
        if (req.getSalesperson() != null) entity.setSalesperson(req.getSalesperson());
        return entity;
    }

    private OnlineSaleResponse toResponse(OnlineSaleEntity e) {
        // Cargar items
        List<OnlineSaleItemEntity> items = itemRepository.findByOnlineSaleIdOrderByIdAsc(e.getId());

        List<OnlineSaleResponse.SaleItemResponse> itemResponses;
        if (items != null && !items.isEmpty()) {
            itemResponses = items.stream().map(item -> OnlineSaleResponse.SaleItemResponse.builder()
                    .id(item.getId())
                    .productId(item.getProductId())
                    .productCode(item.getProductCode())
                    .productName(item.getProductName())
                    .colorId(item.getColorId())
                    .colorName(item.getColorName())
                    .size(item.getSize())
                    .quantity(item.getQuantity())
                    .unitPrice(item.getUnitPrice())
                    .subtotal(item.getSubtotal())
                    .build()
            ).collect(Collectors.toList());
        } else {
            // Fallback: crear item desde campos legacy
            itemResponses = new ArrayList<>();
            if (e.getProductId() != null) {
                BigDecimal sub = (e.getUnitPrice() != null && e.getQuantity() != null)
                        ? e.getUnitPrice().multiply(BigDecimal.valueOf(e.getQuantity()))
                        : e.getTotalAmount();
                itemResponses.add(OnlineSaleResponse.SaleItemResponse.builder()
                        .productId(e.getProductId())
                        .productCode(e.getProductCode())
                        .productName(e.getProductName())
                        .colorId(e.getColorId())
                        .colorName(e.getColorName())
                        .size(e.getSize())
                        .quantity(e.getQuantity())
                        .unitPrice(e.getUnitPrice())
                        .subtotal(sub)
                        .build());
            }
        }

        return OnlineSaleResponse.builder()
                .id(e.getId())
                .saleNumber(e.getSaleNumber())
                .customerName(e.getCustomerName())
                .address(e.getAddress())
                .phone(e.getPhone())
                .phone2(e.getPhone2())
                .packaging(e.getPackaging())
                .paymentMethod(e.getPaymentMethod())
                .paymentMethodDisplay(PAYMENT_METHOD_DISPLAY.getOrDefault(e.getPaymentMethod(), e.getPaymentMethod()))
                .invoiceTaxId(e.getInvoiceTaxId())
                .productId(e.getProductId())
                .productCode(e.getProductCode())
                .productName(e.getProductName())
                .colorId(e.getColorId())
                .colorName(e.getColorName())
                .size(e.getSize())
                .quantity(e.getQuantity())
                .unitPrice(e.getUnitPrice())
                .totalAmount(e.getTotalAmount())
                .shippingCost(e.getShippingCost())
                .netAmount(e.getNetAmount())
                .shippingCarrier(e.getShippingCarrier())
                .saleDate(e.getSaleDate())
                .socialNetwork(e.getSocialNetwork())
                .email(e.getEmail())
                .shipmentNumber(e.getShipmentNumber())
                .guideNumber(e.getGuideNumber())
                .paymentAuthorization(e.getPaymentAuthorization())
                .status(e.getStatus())
                .observations(e.getObservations())
                .salesperson(e.getSalesperson())
                .inProductionOrder(e.getInProductionOrder())
                .productionOrderId(e.getProductionOrderId())
                .createdAt(e.getCreatedAt())
                .createdBy(e.getCreatedBy())
                .items(itemResponses)
                .build();
    }
}
