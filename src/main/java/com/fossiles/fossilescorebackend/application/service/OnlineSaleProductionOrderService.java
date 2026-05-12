package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.InventoryOutflowRequest;
import com.fossiles.fossilescorebackend.application.dto.response.InventoryOutflowResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class OnlineSaleProductionOrderService {

    private static final List<String> RETURNS_WAREHOUSE_CODES = List.of(
            "BODEGA_DEVOLUCIONES",
            "BODEGA_DEV",
            "DEVOLUCION",
            "DEVOLUCIONES",
            "BODEGA_RET",
            "BODEGA_RETURN"
    );

    private final ProductionOrderCodeService productionOrderCodeService;
    private final ProductionOrderRepository productionOrderRepository;
    private final ProductionOrderItemRepository productionOrderItemRepository;
    private final OnlineSaleRepository onlineSaleRepository;
    private final OnlineSaleItemRepository onlineSaleItemRepository;
    private final OnlineSaleService saleService;
    private final OnlineSaleShipmentNumberService onlineSaleShipmentNumberService;

    // Dependencias para revisión de inventario
    private final ProductInventoryLocationRepository productInventoryLocationRepository;
    private final InventoryLocationTypeRepository inventoryLocationTypeRepository;
    private final LocationRepository locationRepository;
    private final ProductVariantLeatherRepository productVariantLeatherRepository;
    private final MaterialRepository materialRepository;
    private final InventoryLocationRepository inventoryLocationRepository;
    private final InventoryService inventoryService;

    public record CreateResult(long productionOrderId, String productionOrderCode, int salesCount, String customerName) {}

    /**
     * Resultado de procesar ventas con revisión de inventario.
     * Separa las ventas que se pueden despachar desde inventario
     * de las que necesitan orden de producción.
     */
    public record FulfillmentResult(
        List<FulfilledSale>   fulfilledFromInventory,
        List<CreateResult>    productionOrdersCreated,
        int                   fulfilledCount,
        int                   productionCount,
        boolean               bodegaPtFound,
        List<KioskOutflowSummary> kioskOutflows
    ) {}

    public record KioskOutflowSummary(
            String ticketNumber,
            Long materialId,
            String materialName,
            Long kioskLocationId,
            String kioskName,
            java.math.BigDecimal quantity,
            String saleNumber,
            Long onlineSaleId
    ) {}

    public record OplCreationResult(List<CreateResult> productionOrders, List<KioskOutflowSummary> kioskOutflows) {}

    public record LocationStockSnap(Long locationId, String locationName, java.math.BigDecimal quantity) {}

    public record FulfilledSale(String saleNumber, String customerName, String shipmentNumber, String message) {}

    public record FulfillmentPreviewRow(
            Long saleId,
            String saleNumber,
            String customerName,
            boolean canFulfillFromInventory,
            String inventorySource,
            boolean leatherOkForOpl,
            java.util.List<String> leatherSummary
    ) {}

    public record SaleItemStock(
            Long saleItemId,
            Long productId,
            String productCode,
            String productName,
            Long colorId,
            String colorName,
            String size,
            int quantity,
            BigDecimal stockDevoluciones,
            BigDecimal stockBodegaPt,
            BigDecimal stockTotal,
            String suggestedAction,
            Long leatherMaterialId,
            String leatherMaterialName,
            BigDecimal leatherQtyPerUnit,
            BigDecimal leatherRequired,
            BigDecimal leatherWorkshopStock,
            java.util.List<LocationStockSnap> leatherKioskStocks,
            String leatherStatus,
            java.util.List<String> leatherExplanation
    ) {}

    public record SaleItemsPreview(
            Long saleId,
            String saleNumber,
            String customerName,
            String overallStatus,
            List<SaleItemStock> items
    ) {}

    public record ItemResolution(Long saleItemId, String action) {}

    public record ResolveMixedResult(
            Long originalSaleId,
            String originalSaleNumber,
            Long childSaleId,
            String childSaleNumber,
            Long productionOrderId,
            String productionOrderCode,
            int dispatchedItems,
            int producedItems,
            String message,
            java.util.List<KioskOutflowSummary> kioskOutflows
    ) {}

    private record InventorySourceDecision(boolean canFulfill, boolean hasReturnsStock, boolean hasPtStock, String sourceLabel) {}

    private static final String ACTION_DISPATCH = "DISPATCH";
    private static final String ACTION_PRODUCE = "PRODUCE";

    // ─────────────────────────────────────────────────────────────
    // MÉTODO PRINCIPAL: procesar con revisión de inventario
    // ─────────────────────────────────────────────────────────────

    /**
     * Nuevo flujo correcto:
     * 1. Bodega PT revisa el inventario de cada venta seleccionada.
     * 2. Las ventas que tienen stock disponible se despachan directamente
     *    (se descuenta inventario, la venta pasa a PRODUCIDO).
     * 3. Solo las ventas SIN stock generan órdenes de producción.
     */
    @Transactional
    public FulfillmentResult processWithInventoryCheck(List<Long> saleIds) throws BusinessException {
        if (saleIds == null || saleIds.isEmpty()) {
            throw new BusinessException("Debe seleccionar al menos una venta");
        }

        List<OnlineSaleEntity> sales = onlineSaleRepository.findAllById(saleIds);
        if (sales.size() != saleIds.size()) {
            throw new BusinessException("Una o más ventas no existen");
        }

        for (OnlineSaleEntity sale : sales) {
            if (Boolean.TRUE.equals(sale.getInProductionOrder())) {
                throw new BusinessException("La venta #" + sale.getSaleNumber() + " ya está en proceso");
            }
            if (!sale.isPaid()) {
                throw new BusinessException("La venta #" + sale.getSaleNumber()
                        + " no tiene pago confirmado (" + sale.getPaymentMethod() + ")");
            }
        }

        // Buscar ubicaciones: Bodega PT + Bodega Devoluciones (si existe)
        LocationEntity bodegaPT = findBodegaPT();
        LocationEntity bodegaDevoluciones = findBodegaDevoluciones();
        List<LocationEntity> sourceWarehouses = List.of(bodegaDevoluciones, bodegaPT).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        boolean bodegaPtFound = !sourceWarehouses.isEmpty();

        List<FulfilledSale>  fulfilled  = new ArrayList<>();
        List<OnlineSaleEntity> needProduction = new ArrayList<>();

        for (OnlineSaleEntity sale : sales) {
            if (!sourceWarehouses.isEmpty() && canFulfillFromInventory(sale, sourceWarehouses)) {
                // Descontar inventario y marcar como producido
                fulfillFromInventory(sale, sourceWarehouses);
                sale.setStatus("PRODUCIDO");
                onlineSaleRepository.save(sale);
                fulfilled.add(new FulfilledSale(
                        sale.getSaleNumber(),
                        sale.getCustomerName(),
                        sale.getShipmentNumber(),
                        "Lista para despacho desde inventario (Bodega PT / Devoluciones)"));
            } else {
                needProduction.add(sale);
            }
        }

        List<KioskOutflowSummary> kioskOutflows = new ArrayList<>();
        List<CreateResult> productionResults = new ArrayList<>();
        if (!needProduction.isEmpty()) {
            List<Long> productionSaleIds = needProduction.stream().map(OnlineSaleEntity::getId).toList();
            OplCreationResult batch = createOplFromSales(productionSaleIds);
            productionResults = batch.productionOrders();
            kioskOutflows = batch.kioskOutflows();
        }

        return new FulfillmentResult(
                fulfilled,
                productionResults,
                fulfilled.size(),
                productionResults.size(),
                bodegaPtFound,
                kioskOutflows
        );
    }

    @Transactional(readOnly = true)
    public Map<String, Object> previewFulfillment(List<Long> saleIds) throws BusinessException {
        if (saleIds == null || saleIds.isEmpty()) {
            throw new BusinessException("Debe seleccionar al menos una venta");
        }

        List<OnlineSaleEntity> sales = onlineSaleRepository.findAllById(saleIds);
        if (sales.size() != saleIds.size()) {
            throw new BusinessException("Una o más ventas no existen");
        }

        LocationEntity bodegaPT = findBodegaPT();
        LocationEntity bodegaDevoluciones = findBodegaDevoluciones();
        List<LocationEntity> sourceWarehouses = List.of(bodegaDevoluciones, bodegaPT).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        boolean bodegaPtFound = !sourceWarehouses.isEmpty();

        List<FulfillmentPreviewRow> rows = sales.stream()
                .map(sale -> {
                    InventorySourceDecision decision = resolveInventorySourceDecision(sale, bodegaDevoluciones, bodegaPT);
                    boolean needOpl = !decision.canFulfill && !"PARCIAL".equals(decision.sourceLabel);
                    LeatherOlpPreview leather = needOpl ? previewLeatherForSale(sale) : LeatherOlpPreview.allGood();
                    return new FulfillmentPreviewRow(
                            sale.getId(),
                            sale.getSaleNumber(),
                            sale.getCustomerName(),
                            decision.canFulfill,
                            decision.sourceLabel,
                            leather.satisfied(),
                            leather.messages()
                    );
                })
                .toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bodegaPtFound", bodegaPtFound);
        out.put("rows", rows);
        return out;
    }

    private InventorySourceDecision resolveInventorySourceDecision(
            OnlineSaleEntity sale,
            LocationEntity bodegaDevoluciones,
            LocationEntity bodegaPT
    ) {
        if (sale == null) {
            return new InventorySourceDecision(false, false, false, "N/A");
        }

        List<OnlineSaleItemEntity> items = onlineSaleItemRepository.findByOnlineSaleIdOrderByIdAsc(sale.getId());
        List<SaleItemStock> stocks = buildItemStocks(sale, items, bodegaDevoluciones, bodegaPT);

        int total = stocks.size();
        if (total == 0) return new InventorySourceDecision(false, false, false, "SIN_STOCK");

        int fulfillable = 0;
        boolean usesDev = false;
        boolean usesPt = false;
        for (SaleItemStock s : stocks) {
            BigDecimal needed = BigDecimal.valueOf(s.quantity());
            if (s.stockTotal().compareTo(needed) >= 0) {
                fulfillable++;
                BigDecimal fromDev = s.stockDevoluciones().min(needed);
                BigDecimal remaining = needed.subtract(fromDev);
                if (fromDev.compareTo(BigDecimal.ZERO) > 0) usesDev = true;
                if (remaining.compareTo(BigDecimal.ZERO) > 0) usesPt = true;
            }
        }

        if (fulfillable == 0) return new InventorySourceDecision(false, false, false, "SIN_STOCK");
        // Marca PARCIAL como NO fulfillable a nivel venta para que no se mezcle con el flujo de despacho directo;
        // la venta debe resolverse via flujo "resolver mixto".
        if (fulfillable < total) return new InventorySourceDecision(false, usesDev, usesPt, "PARCIAL");

        String source;
        if (usesDev && usesPt) source = "MIXTO";
        else if (usesDev) source = "DEVOLUCIONES";
        else source = "BODEGA_PT";
        return new InventorySourceDecision(true, usesDev, usesPt, source);
    }

    private List<SaleItemStock> buildItemStocks(
            OnlineSaleEntity sale,
            List<OnlineSaleItemEntity> items,
            LocationEntity bodegaDevoluciones,
            LocationEntity bodegaPT
    ) {
        List<SaleItemStock> result = new ArrayList<>();
        if (items != null && !items.isEmpty()) {
            for (OnlineSaleItemEntity item : items) {
                result.add(toItemStock(
                        item.getId(),
                        item.getProductId(),
                        item.getProductCode(),
                        item.getProductName(),
                        item.getColorId(),
                        item.getColorName(),
                        item.getSize(),
                        item.getQuantity() != null ? item.getQuantity() : 1,
                        bodegaDevoluciones,
                        bodegaPT));
            }
        } else if (sale.getProductId() != null) {
            result.add(toItemStock(
                    null,
                    sale.getProductId(),
                    sale.getProductCode(),
                    sale.getProductName(),
                    sale.getColorId(),
                    sale.getColorName(),
                    sale.getSize(),
                    sale.getQuantity() != null ? sale.getQuantity() : 1,
                    bodegaDevoluciones,
                    bodegaPT));
        }
        return result;
    }

    private SaleItemStock toItemStock(
            Long saleItemId, Long productId, String productCode, String productName,
            Long colorId, String colorName, String size, int quantity,
            LocationEntity bodegaDevoluciones, LocationEntity bodegaPT
    ) {
        BigDecimal stockDev = bodegaDevoluciones != null
                ? getStock(productId, bodegaDevoluciones.getId(), colorId)
                : BigDecimal.ZERO;
        BigDecimal stockPt = bodegaPT != null
                ? getStock(productId, bodegaPT.getId(), colorId)
                : BigDecimal.ZERO;
        BigDecimal total = stockDev.add(stockPt);
        String suggested = total.compareTo(BigDecimal.valueOf(quantity)) >= 0 ? ACTION_DISPATCH : ACTION_PRODUCE;
        if (ACTION_DISPATCH.equals(suggested)) {
            return new SaleItemStock(saleItemId, productId, productCode, productName,
                    colorId, colorName, size, quantity, stockDev, stockPt, total, suggested,
                    null, null, null, null, null, List.of(), "DESPACHO", List.of(
                            "Stock cubierto en Devoluciones / Bodega PT según orden de consumo."));
        }
        return buildProduceItemStock(saleItemId, productId, productCode, productName,
                colorId, colorName, size, quantity, stockDev, stockPt, total, suggested);
    }

    private SaleItemStock buildProduceItemStock(
            Long saleItemId, Long productId, String productCode, String productName,
            Long colorId, String colorName, String size, int quantity,
            BigDecimal stockDev, BigDecimal stockPt, BigDecimal total, String suggested
    ) {
        Optional<ProductVariantLeatherEntity> mapping = resolveLeatherMapping(productId, colorId);
        if (mapping.isEmpty()) {
            return new SaleItemStock(saleItemId, productId, productCode, productName,
                    colorId, colorName, size, quantity, stockDev, stockPt, total, suggested,
                    null, null, null, null, null, List.of(), "SIN_CONFIG",
                    List.of("Configure cuero por variante (producto + color) para poder validar OPL."));
        }
        ProductVariantLeatherEntity m = mapping.get();
        BigDecimal qpu = m.getQtyPerUnit() != null ? m.getQtyPerUnit() : BigDecimal.ONE;
        BigDecimal required = qpu.multiply(BigDecimal.valueOf(quantity));
        Long matId = m.getLeatherMaterialId();
        String matName = materialRepository.findById(matId).map(MaterialEntity::getName).orElse("");

        BigDecimal workshop = sumWorkshopMaterialStock(matId);
        List<LocationEntity> kiosks = locationRepository.findByCategoriaIgnoreCaseOrderByNameAsc("KIOSKO");
        List<LocationStockSnap> kioskSnaps = new ArrayList<>();
        for (LocationEntity k : kiosks) {
            BigDecimal q = getMaterialStockAt(matId, k.getId());
            kioskSnaps.add(new LocationStockSnap(k.getId(), k.getName(), q));
        }

        BigDecimal shortfall = required.subtract(workshop);
        if (shortfall.compareTo(BigDecimal.ZERO) <= 0) {
            return new SaleItemStock(saleItemId, productId, productCode, productName,
                    colorId, colorName, size, quantity, stockDev, stockPt, total, suggested,
                    matId, matName, qpu, required, workshop, kioskSnaps, "TALLER",
                    List.of(
                            "Cuero requerido: " + required + " (" + qpu + " por unidad × " + quantity + ").",
                            "En taller/bodegas (no kiosko): " + workshop + " — suficiente para OPL."));
        }

        BigDecimal kioskPool = BigDecimal.ZERO;
        for (LocationStockSnap ks : kioskSnaps) {
            kioskPool = kioskPool.add(ks.quantity() != null ? ks.quantity() : BigDecimal.ZERO);
        }
        if (shortfall.compareTo(kioskPool) > 0) {
            return new SaleItemStock(saleItemId, productId, productCode, productName,
                    colorId, colorName, size, quantity, stockDev, stockPt, total, suggested,
                    matId, matName, qpu, required, workshop, kioskSnaps, "BLOQUEADO",
                    List.of(
                            "Falta cuero: se requieren " + shortfall.stripTrailingZeros().toPlainString()
                                    + " unidades adicionales tras taller (" + workshop + ").",
                            "Total disponible en kioskos (orden por nombre): " + kioskPool.stripTrailingZeros().toPlainString()
                                    + " — insuficiente."));
        }

        return new SaleItemStock(saleItemId, productId, productCode, productName,
                colorId, colorName, size, quantity, stockDev, stockPt, total, suggested,
                matId, matName, qpu, required, workshop, kioskSnaps, "KIOSKO",
                List.of(
                        "Taller cubre " + workshop.stripTrailingZeros().toPlainString()
                                + "; falta " + shortfall.stripTrailingZeros().toPlainString() + " — se tomará de kiosko en proceso.",
                        "Regla: primero Devoluciones/PT para producto terminado; para OPL se valida cuero en taller y luego kioskos."));
    }

    @Transactional(readOnly = true)
    public SaleItemsPreview previewSaleItems(long saleId) throws BusinessException {
        OnlineSaleEntity sale = onlineSaleRepository.findById(saleId)
                .orElseThrow(() -> new BusinessException("Venta no encontrada: " + saleId));

        LocationEntity bodegaPT = findBodegaPT();
        LocationEntity bodegaDevoluciones = findBodegaDevoluciones();
        List<OnlineSaleItemEntity> items = onlineSaleItemRepository.findByOnlineSaleIdOrderByIdAsc(sale.getId());
        List<SaleItemStock> stocks = buildItemStocks(sale, items, bodegaDevoluciones, bodegaPT);

        InventorySourceDecision decision = resolveInventorySourceDecision(sale, bodegaDevoluciones, bodegaPT);
        return new SaleItemsPreview(sale.getId(), sale.getSaleNumber(), sale.getCustomerName(),
                decision.sourceLabel, stocks);
    }

    @Transactional
    public Map<String, Object> prepareDirectSaleFromInventory(long onlineSaleId) throws BusinessException {
        OnlineSaleEntity sale = onlineSaleRepository.findById(onlineSaleId)
                .orElseThrow(() -> new BusinessException("Venta no encontrada: " + onlineSaleId));

        if (Boolean.TRUE.equals(sale.getInProductionOrder())) {
            throw new BusinessException("La venta ya está vinculada a una orden de producción");
        }
        if (!sale.isPaid()) {
            throw new BusinessException("La venta #" + sale.getSaleNumber() + " no tiene pago confirmado (" + sale.getPaymentMethod() + ")");
        }
        if ("ENVIADO".equals(sale.getStatus()) || "ENTREGADO".equals(sale.getStatus())) {
            throw new BusinessException("La venta ya fue despachada. Estado actual: " + sale.getStatus());
        }

        LocationEntity bodegaPT = findBodegaPT();
        LocationEntity bodegaDevoluciones = findBodegaDevoluciones();
        List<LocationEntity> sourceWarehouses = List.of(bodegaDevoluciones, bodegaPT).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (sourceWarehouses.isEmpty()) {
            throw new BusinessException("No se encontró BODEGA_PT o Bodega Devoluciones configurada");
        }
        if (!canFulfillFromInventory(sale, sourceWarehouses)) {
            throw new BusinessException("Sin stock suficiente en Bodega PT / Devoluciones para esta venta");
        }

        fulfillFromInventory(sale, sourceWarehouses);
        sale.setStatus("PRODUCIDO");
        onlineSaleRepository.save(sale);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("onlineSaleId", sale.getId());
        out.put("saleNumber", sale.getSaleNumber());
        out.put("shipmentNumber", sale.getShipmentNumber());
        out.put("saleStatus", sale.getStatus());
        out.put("message", "Venta preparada desde inventario (Bodega PT / Devoluciones)");
        return out;
    }

    /**
     * Verifica si todos los items de la venta tienen suficiente stock consolidado
     * en las bodegas de despacho (Bodega Devoluciones + Bodega PT).
     */
    private boolean canFulfillFromInventory(OnlineSaleEntity sale, List<LocationEntity> sourceWarehouses) {
        List<OnlineSaleItemEntity> items = onlineSaleItemRepository
                .findByOnlineSaleIdOrderByIdAsc(sale.getId());

        if (items != null && !items.isEmpty()) {
            for (OnlineSaleItemEntity item : items) {
                if (item.getProductId() == null) continue;
                int needed = item.getQuantity() != null ? item.getQuantity() : 1;
                BigDecimal stock = getStockAcrossWarehouses(item.getProductId(), item.getColorId(), sourceWarehouses);
                if (stock.compareTo(BigDecimal.valueOf(needed)) < 0) {
                    return false;
                }
            }
            return true;
        } else {
            // Modo legado: un solo producto
            if (sale.getProductId() == null) return false;
            int needed = sale.getQuantity() != null ? sale.getQuantity() : 1;
            BigDecimal stock = getStockAcrossWarehouses(sale.getProductId(), sale.getColorId(), sourceWarehouses);
            return stock.compareTo(BigDecimal.valueOf(needed)) >= 0;
        }
    }

    /**
     * Descuenta el inventario para todos los items de la venta.
     * Regla: consumir primero Bodega Devoluciones (si existe) y luego Bodega PT.
     */
    private void fulfillFromInventory(OnlineSaleEntity sale, List<LocationEntity> sourceWarehouses) {
        // Preparación automática: si se resuelve desde inventario PT,
        // asignar ENVL para que bodega pueda despachar con número ya listo.
        onlineSaleShipmentNumberService.assignIfMissing(sale);

        List<OnlineSaleItemEntity> items = onlineSaleItemRepository
                .findByOnlineSaleIdOrderByIdAsc(sale.getId());

        if (items != null && !items.isEmpty()) {
            for (OnlineSaleItemEntity item : items) {
                if (item.getProductId() == null) continue;
                int qty = item.getQuantity() != null ? item.getQuantity() : 1;
                consumeAcrossWarehouses(item.getProductId(), item.getColorId(), BigDecimal.valueOf(qty), sourceWarehouses);
            }
        } else {
            if (sale.getProductId() != null) {
                int qty = sale.getQuantity() != null ? sale.getQuantity() : 1;
                consumeAcrossWarehouses(sale.getProductId(), sale.getColorId(), BigDecimal.valueOf(qty), sourceWarehouses);
            }
        }
    }

    /**
     * Consulta el stock disponible de un producto+color en una ubicación.
     */
    private BigDecimal getStock(Long productId, Long locationId, Long colorId) {
        return productInventoryLocationRepository
                .findByProductIdAndLocationIdAndColorId(productId, locationId, colorId)
                .map(pil -> pil.getQuantity() != null ? pil.getQuantity() : BigDecimal.ZERO)
                .orElse(BigDecimal.ZERO);
    }

    /**
     * Stock consolidado en bodegas origen.
     */
    private BigDecimal getStockAcrossWarehouses(Long productId, Long colorId, List<LocationEntity> sourceWarehouses) {
        BigDecimal total = BigDecimal.ZERO;
        for (LocationEntity loc : sourceWarehouses) {
            total = total.add(getStock(productId, loc.getId(), colorId));
        }
        return total;
    }

    private void decrementLocation(Long productId, Long locationId, Long colorId, BigDecimal qty) {
        productInventoryLocationRepository
                .findByProductIdAndLocationIdAndColorId(productId, locationId, colorId)
                .ifPresent(pil -> {
                    BigDecimal newQty = (pil.getQuantity() != null ? pil.getQuantity() : BigDecimal.ZERO).subtract(qty);
                    if (newQty.compareTo(BigDecimal.ZERO) < 0) newQty = BigDecimal.ZERO;
                    pil.setQuantity(newQty);
                    productInventoryLocationRepository.save(pil);
                });
    }

    private void consumeAcrossWarehouses(Long productId, Long colorId, BigDecimal qty, List<LocationEntity> sourceWarehouses) {
        BigDecimal remaining = qty != null ? qty : BigDecimal.ZERO;
        if (remaining.compareTo(BigDecimal.ZERO) <= 0) return;

        for (LocationEntity loc : sourceWarehouses) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
            BigDecimal available = getStock(productId, loc.getId(), colorId);
            if (available.compareTo(BigDecimal.ZERO) <= 0) continue;
            BigDecimal toConsume = available.min(remaining);
            decrementLocation(productId, loc.getId(), colorId, toConsume);
            remaining = remaining.subtract(toConsume);
        }
    }

    /**
     * Encuentra la ubicación BODEGA_PT usando el tipo de inventario configurado.
     */
    private LocationEntity findBodegaPT() {
        Optional<InventoryLocationTypeEntity> locType =
                inventoryLocationTypeRepository.findByCodeAndIsActiveTrue("BODEGA_PT");
        if (locType.isEmpty()) {
            log.warn("No se encontró el tipo de ubicación BODEGA_PT activo");
            return null;
        }
        return locationRepository.findAll().stream()
                .filter(loc -> "BODEGA_PT".equals(loc.getCode()))
                .findFirst()
                .orElse(null);
    }

    private LocationEntity findBodegaDevoluciones() {
        for (String code : RETURNS_WAREHOUSE_CODES) {
            Optional<InventoryLocationTypeEntity> locType =
                    inventoryLocationTypeRepository.findByCodeAndIsActiveTrue(code);
            if (locType.isEmpty()) continue;

            LocationEntity loc = locationRepository.findAll().stream()
                    .filter(l -> code.equals(l.getCode()))
                    .findFirst()
                    .orElse(null);
            if (loc != null) {
                return loc;
            }
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────
    // RESOLVER MIXTO: despachar items con stock + crear OP para los faltantes
    // ─────────────────────────────────────────────────────────────

    /**
     * Resuelve una venta mixta dividiendola en dos partes:
     * - La venta original conserva los items marcados como DISPATCH (queda pendiente para que bodega la procese después).
     * - Se crea un sub-pedido (saleNumber + "-OP") con los items marcados como PRODUCE
     *   y se genera una orden de produccion para ese sub-pedido.
     *
     * IMPORTANTE: este resolver NO descuenta inventario ni marca PRODUCIDO. Solo decide qué va a OP.
     */
    @Transactional
    public ResolveMixedResult resolveMixedSale(long saleId, List<ItemResolution> resolutions) throws BusinessException {
        if (resolutions == null || resolutions.isEmpty()) {
            throw new BusinessException("Debe enviar al menos una resolucion de item");
        }

        OnlineSaleEntity sale = onlineSaleRepository.findById(saleId)
                .orElseThrow(() -> new BusinessException("Venta no encontrada: " + saleId));

        if (Boolean.TRUE.equals(sale.getInProductionOrder())) {
            throw new BusinessException("La venta ya esta vinculada a una orden de produccion");
        }
        if (!sale.isPaid()) {
            throw new BusinessException("La venta #" + sale.getSaleNumber() + " no tiene pago confirmado");
        }
        String currentStatus = sale.getStatus();
        if ("PRODUCIDO".equals(currentStatus) || "ENVIADO".equals(currentStatus) || "ENTREGADO".equals(currentStatus)) {
            throw new BusinessException("La venta ya fue procesada. Estado actual: " + currentStatus);
        }

        List<OnlineSaleItemEntity> items = onlineSaleItemRepository.findByOnlineSaleIdOrderByIdAsc(sale.getId());
        if (items == null || items.isEmpty()) {
            throw new BusinessException("La venta no tiene items detallados; usa los flujos estandar de despacho/OP");
        }

        Map<Long, OnlineSaleItemEntity> itemsById = items.stream()
                .collect(Collectors.toMap(OnlineSaleItemEntity::getId, i -> i));

        Set<Long> dispatchIds = new LinkedHashSet<>();
        Set<Long> produceIds = new LinkedHashSet<>();
        for (ItemResolution r : resolutions) {
            if (r.saleItemId() == null) throw new BusinessException("Falta saleItemId en una resolucion");
            if (!itemsById.containsKey(r.saleItemId())) {
                throw new BusinessException("Item no pertenece a la venta: " + r.saleItemId());
            }
            String action = r.action() != null ? r.action().toUpperCase(Locale.ROOT) : "";
            if (ACTION_DISPATCH.equals(action)) dispatchIds.add(r.saleItemId());
            else if (ACTION_PRODUCE.equals(action)) produceIds.add(r.saleItemId());
            else throw new BusinessException("Accion invalida (use DISPATCH o PRODUCE): " + r.action());
        }

        if (dispatchIds.isEmpty() && produceIds.isEmpty()) {
            throw new BusinessException("Selecciona al menos un item para despachar o producir");
        }
        for (OnlineSaleItemEntity it : items) {
            if (!dispatchIds.contains(it.getId()) && !produceIds.contains(it.getId())) {
                throw new BusinessException("Falta resolucion para el item " + it.getProductCode());
            }
        }

        // Nota: no validamos stock aquí porque no estamos despachando; bodega lo procesará después con el flujo normal.

        // Caso 1: solo DISPATCH -> no crear OP, mantener venta pendiente para despacho
        if (produceIds.isEmpty()) {
            return new ResolveMixedResult(sale.getId(), sale.getSaleNumber(), null, null,
                    null, null, items.size(), 0,
                    "Sin OP: la venta queda pendiente para despacho.",
                    List.of());
        }

        // Caso 2: solo PRODUCE -> crear OP sobre la venta original
        if (dispatchIds.isEmpty()) {
            OplCreationResult batch = createOplFromSales(List.of(sale.getId()));
            CreateResult cr = batch.productionOrders().get(0);
            return new ResolveMixedResult(sale.getId(), sale.getSaleNumber(), null, null,
                    cr.productionOrderId(), cr.productionOrderCode(), 0, items.size(),
                    "OP creada para todos los items: " + cr.productionOrderCode(),
                    batch.kioskOutflows());
        }

        // Caso 3: MIXTO -> dividir
        OnlineSaleEntity child = splitSaleForProduction(sale, produceIds, itemsById);

        // Recalcular montos (la venta original queda con los items para despacho; el sub-pedido con los de OP).
        recalculateSaleAmounts(sale);
        sale.setObservations(appendNote(sale.getObservations(),
                "Despacho parcial; pendiente OP en venta #" + child.getSaleNumber()));
        onlineSaleRepository.save(sale);

        // Crear OP del sub-pedido
        OplCreationResult batch = createOplFromSales(List.of(child.getId()));
        CreateResult cr = batch.productionOrders().get(0);

        return new ResolveMixedResult(
                sale.getId(), sale.getSaleNumber(),
                child.getId(), child.getSaleNumber(),
                cr.productionOrderId(), cr.productionOrderCode(),
                dispatchIds.size(), produceIds.size(),
                "Despachado " + dispatchIds.size() + " item(s) y OP " + cr.productionOrderCode()
                        + " creada para " + produceIds.size() + " item(s)",
                batch.kioskOutflows());
    }

    private OnlineSaleEntity splitSaleForProduction(
            OnlineSaleEntity original,
            Set<Long> produceItemIds,
            Map<Long, OnlineSaleItemEntity> itemsById
    ) {
        String childNumber = nextSplitSaleNumber(original);
        OnlineSaleEntity child = OnlineSaleEntity.builder()
                .saleNumber(childNumber)
                .customerName(original.getCustomerName())
                .address(original.getAddress())
                .phone(original.getPhone())
                .phone2(original.getPhone2())
                .packaging(original.getPackaging())
                .paymentMethod(original.getPaymentMethod())
                .invoiceTaxId(original.getInvoiceTaxId())
                .saleDate(original.getSaleDate() != null ? original.getSaleDate() : LocalDate.now())
                .socialNetwork(original.getSocialNetwork())
                .email(original.getEmail())
                .salesperson(original.getSalesperson())
                .status("PENDIENTE")
                .inProductionOrder(false)
                .shippingCost(BigDecimal.ZERO)
                .observations(appendNote(original.getObservations(),
                        "Sub-pedido OP de venta #" + original.getSaleNumber()))
                .build();
        child.setSkipAmountCalculation(true);
        OnlineSaleEntity savedChild = onlineSaleRepository.save(child);

        for (Long itemId : produceItemIds) {
            OnlineSaleItemEntity it = itemsById.get(itemId);
            it.setOnlineSaleId(savedChild.getId());
            onlineSaleItemRepository.save(it);
        }
        recalculateSaleAmounts(savedChild);
        return onlineSaleRepository.save(savedChild);
    }

    private void recalculateSaleAmounts(OnlineSaleEntity sale) {
        List<OnlineSaleItemEntity> items = onlineSaleItemRepository.findByOnlineSaleIdOrderByIdAsc(sale.getId());
        BigDecimal net = BigDecimal.ZERO;
        for (OnlineSaleItemEntity it : items) {
            BigDecimal subtotal = it.getSubtotal();
            if (subtotal == null && it.getUnitPrice() != null && it.getQuantity() != null) {
                subtotal = it.getUnitPrice().multiply(BigDecimal.valueOf(it.getQuantity()));
            }
            if (subtotal != null) net = net.add(subtotal);
        }
        sale.setNetAmount(net);
        BigDecimal shipping = sale.getShippingCost() != null ? sale.getShippingCost() : BigDecimal.ZERO;
        sale.setTotalAmount(net.add(shipping));
        sale.setSkipAmountCalculation(true);
    }

    private String nextSplitSaleNumber(OnlineSaleEntity original) {
        String base = original.getSaleNumber() != null ? original.getSaleNumber() : ("S" + original.getId());
        LocalDate date = original.getSaleDate();
        for (int i = 1; i <= 99; i++) {
            String candidate = base + "-OP" + (i == 1 ? "" : i);
            boolean exists = date != null
                    ? onlineSaleRepository.existsBySaleNumberAndSaleDate(candidate, date)
                    : false;
            if (!exists) return candidate;
        }
        return base + "-OP-" + System.currentTimeMillis();
    }

    private String appendNote(String original, String note) {
        if (note == null || note.isBlank()) return original;
        if (original == null || original.isBlank()) return note;
        return original + " | " + note;
    }

    // ─────────────────────────────────────────────────────────────
    // OPL desde ventas online (agrupa por cliente)
    // ─────────────────────────────────────────────────────────────

    /**
     * Compatibilidad: solo retorna las OP creadas (sin detalle de boletas kiosko).
     */
    @Transactional
    public List<CreateResult> createFromSaleIds(List<Long> saleIds) throws BusinessException {
        return createOplFromSales(saleIds).productionOrders();
    }

    /**
     * Crea OP VENTA_EN_LINEA y, si aplica, descuenta cuero desde kioskos cuando no alcanza en taller.
     */
    @Transactional
    public OplCreationResult createOplFromSales(List<Long> saleIds) throws BusinessException {
        if (saleIds == null || saleIds.isEmpty()) {
            throw new BusinessException("Debe seleccionar al menos una venta");
        }

        List<OnlineSaleEntity> sales = onlineSaleRepository.findAllById(saleIds);
        if (sales.size() != saleIds.size()) {
            throw new BusinessException("Una o más ventas no existen");
        }

        for (OnlineSaleEntity sale : sales) {
            if (Boolean.TRUE.equals(sale.getInProductionOrder())) {
                throw new BusinessException("La venta #" + sale.getSaleNumber() + " ya está en una orden de producción");
            }
            if (!sale.isPaid()) {
                throw new BusinessException("La venta #" + sale.getSaleNumber() + " no tiene pago confirmado (" + sale.getPaymentMethod() + ")");
            }
        }

        List<KioskOutflowSummary> kioskOutflows = validateLeatherAndConsumeKiosk(sales);

        Map<String, List<OnlineSaleEntity>> byCustomer = new LinkedHashMap<>();
        for (OnlineSaleEntity sale : sales) {
            String key = sale.getCustomerName() != null ? sale.getCustomerName().trim() : "Sin nombre";
            byCustomer.computeIfAbsent(key, k -> new ArrayList<>()).add(sale);
        }

        int baseCorrelative = productionOrderCodeService.getNextCorrelative("OPL");
        List<CreateResult> results = new ArrayList<>();
        int offset = 0;

        for (Map.Entry<String, List<OnlineSaleEntity>> entry : byCustomer.entrySet()) {
            String customer = entry.getKey();
            List<OnlineSaleEntity> customerSales = entry.getValue();

            String orderCode = "OPL-" + (baseCorrelative + offset);
            if (productionOrderRepository.existsByCode(orderCode)) {
                throw new BusinessException("El código de orden ya existe: " + orderCode);
            }

            ProductionOrderEntity po = ProductionOrderEntity.builder()
                    .code(orderCode)
                    .orderType("VENTA_EN_LINEA")
                    .customerName(customer)
                    .startDate(LocalDate.now())
                    .deliveryDate(LocalDate.now().plusDays(1))
                    .observations("Orden generada desde ventas online. Cliente: " + customer + ". Ventas: " +
                            customerSales.stream().map(s -> "#" + s.getSaleNumber()).reduce((a, b) -> a + ", " + b).orElse(""))
                    .status("PENDING")
                    .build();
            ProductionOrderEntity savedPO = productionOrderRepository.save(po);

            List<Long> customerSaleIds = new ArrayList<>();
            for (OnlineSaleEntity sale : customerSales) {
                customerSaleIds.add(sale.getId());
                createOrderItems(savedPO.getId(), sale);
            }

            saleService.linkToProductionOrder(customerSaleIds, savedPO.getId());
            results.add(new CreateResult(savedPO.getId(), orderCode, customerSales.size(), customer));
            offset++;
        }

        return new OplCreationResult(results, kioskOutflows);
    }

    private void createOrderItems(Long productionOrderId, OnlineSaleEntity sale) {
        List<OnlineSaleItemEntity> saleItems = onlineSaleItemRepository
                .findByOnlineSaleIdOrderByIdAsc(sale.getId());

        if (saleItems != null && !saleItems.isEmpty()) {
            for (OnlineSaleItemEntity si : saleItems) {
                ProductionOrderItemEntity item = ProductionOrderItemEntity.builder()
                        .productionOrderId(productionOrderId)
                        .onlineSaleId(sale.getId())
                        .productId(si.getProductId())
                        .colorId(si.getColorId())
                        .quantity(si.getQuantity() != null ? si.getQuantity() : 1)
                        .warehouseReceivedQty(0)
                        .observations("Venta #" + sale.getSaleNumber() + " - " +
                                (sale.getCustomerName() != null ? sale.getCustomerName() : "") +
                                (si.getSize() != null ? " | Talla: " + si.getSize() : ""))
                        .build();
                productionOrderItemRepository.save(item);
            }
        } else {
            ProductionOrderItemEntity item = ProductionOrderItemEntity.builder()
                    .productionOrderId(productionOrderId)
                    .onlineSaleId(sale.getId())
                    .productId(sale.getProductId())
                    .colorId(sale.getColorId())
                    .quantity(sale.getQuantity() != null ? sale.getQuantity() : 1)
                    .warehouseReceivedQty(0)
                    .observations("Venta #" + sale.getSaleNumber() + " - " +
                            (sale.getCustomerName() != null ? sale.getCustomerName() : "") +
                            (sale.getSize() != null ? " | Talla: " + sale.getSize() : ""))
                    .build();
            productionOrderItemRepository.save(item);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Cuero por variante + kioskos (OPL)
    // ─────────────────────────────────────────────────────────────

    private record LeatherOlpPreview(boolean satisfied, List<String> messages) {
        static LeatherOlpPreview allGood() {
            return new LeatherOlpPreview(true, List.of());
        }
    }

    private LeatherOlpPreview previewLeatherForSale(OnlineSaleEntity sale) {
        List<String> lines = new ArrayList<>();
        boolean allOk = true;
        for (SaleLine line : flattenSaleLines(sale)) {
            Optional<ProductVariantLeatherEntity> mapping = resolveLeatherMapping(line.productId(), line.colorId());
            if (mapping.isEmpty()) {
                allOk = false;
                lines.add("Item " + line.productId() + ": sin configuración de cuero (producto+color).");
                continue;
            }
            ProductVariantLeatherEntity m = mapping.get();
            BigDecimal qpu = m.getQtyPerUnit() != null ? m.getQtyPerUnit() : BigDecimal.ONE;
            BigDecimal required = qpu.multiply(BigDecimal.valueOf(line.quantity()));
            Long matId = m.getLeatherMaterialId();
            BigDecimal workshop = sumWorkshopMaterialStock(matId);
            BigDecimal shortfall = required.subtract(workshop);
            if (shortfall.compareTo(BigDecimal.ZERO) <= 0) {
                lines.add("Producto " + line.productId() + ": cuero OK en taller/bodegas (necesario "
                        + required.stripTrailingZeros().toPlainString() + ", disponible "
                        + workshop.stripTrailingZeros().toPlainString() + ").");
                continue;
            }
            List<LocationEntity> kiosks = locationRepository.findByCategoriaIgnoreCaseOrderByNameAsc("KIOSKO");
            BigDecimal remaining = shortfall;
            for (LocationEntity kiosk : kiosks) {
                if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                    break;
                }
                BigDecimal at = getMaterialStockAt(matId, kiosk.getId());
                BigDecimal take = at.min(remaining);
                if (take.compareTo(BigDecimal.ZERO) > 0) {
                    remaining = remaining.subtract(take);
                }
            }
            if (remaining.compareTo(BigDecimal.ZERO) > 0) {
                allOk = false;
                lines.add("Producto " + line.productId() + ": falta cuero (" + matId + "). Requerido total "
                        + required.stripTrailingZeros().toPlainString()
                        + ", taller "
                        + workshop.stripTrailingZeros().toPlainString()
                        + " — kioskos no cubren el faltante.");
            } else {
                lines.add("Producto " + line.productId() + ": faltante "
                        + shortfall.stripTrailingZeros().toPlainString()
                        + " cubrible desde kiosko(s) al procesar.");
            }
        }
        return new LeatherOlpPreview(allOk, List.copyOf(lines));
    }

    private record SaleLine(Long productId, Long colorId, int quantity) {}

    private List<SaleLine> flattenSaleLines(OnlineSaleEntity sale) {
        List<OnlineSaleItemEntity> items = onlineSaleItemRepository.findByOnlineSaleIdOrderByIdAsc(sale.getId());
        List<SaleLine> lines = new ArrayList<>();
        if (items != null && !items.isEmpty()) {
            for (OnlineSaleItemEntity it : items) {
                if (it.getProductId() == null) {
                    continue;
                }
                lines.add(new SaleLine(
                        it.getProductId(),
                        it.getColorId(),
                        it.getQuantity() != null ? it.getQuantity() : 1));
            }
        } else if (sale.getProductId() != null) {
            lines.add(new SaleLine(
                    sale.getProductId(),
                    sale.getColorId(),
                    sale.getQuantity() != null ? sale.getQuantity() : 1));
        }
        return lines;
    }

    private List<KioskOutflowSummary> validateLeatherAndConsumeKiosk(List<OnlineSaleEntity> sales)
            throws BusinessException {
        List<KioskOutflowSummary> out = new ArrayList<>();
        List<LocationEntity> kiosks = locationRepository.findByCategoriaIgnoreCaseOrderByNameAsc("KIOSKO");

        for (OnlineSaleEntity sale : sales) {
            for (SaleLine line : flattenSaleLines(sale)) {
                ProductVariantLeatherEntity mapping = resolveLeatherMapping(line.productId(), line.colorId())
                        .orElseThrow(() -> new BusinessException(
                                "Configure cuero por variante para producto "
                                        + line.productId() + " (color " + line.colorId()
                                        + ") antes de crear OPL — venta #" + sale.getSaleNumber()));

                BigDecimal qpu = mapping.getQtyPerUnit() != null ? mapping.getQtyPerUnit() : BigDecimal.ONE;
                BigDecimal required = qpu.multiply(BigDecimal.valueOf(line.quantity()));
                Long matId = mapping.getLeatherMaterialId();

                BigDecimal workshop = sumWorkshopMaterialStock(matId);
                BigDecimal needFromKiosks = required.subtract(workshop);
                if (needFromKiosks.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }

                BigDecimal remaining = needFromKiosks;
                for (LocationEntity kiosk : kiosks) {
                    if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                        break;
                    }
                    BigDecimal at = getMaterialStockAt(matId, kiosk.getId());
                    if (at.compareTo(BigDecimal.ZERO) <= 0) {
                        continue;
                    }
                    BigDecimal take = at.min(remaining);
                    InventoryOutflowResponse r;
                    try {
                        r = inventoryService.registerKioskOutflow(
                                InventoryOutflowRequest.builder()
                                        .fromLocationId(kiosk.getId())
                                        .materialId(matId)
                                        .quantity(take)
                                        .reason("Salida kiosko por OPL venta online #" + sale.getSaleNumber())
                                        .referenceType("ONLINE_SALE")
                                        .referenceId(sale.getId())
                                        .referenceNumber(sale.getSaleNumber())
                                        .build());
                    } catch (ResourceNotFoundException e) {
                        throw new BusinessException(e.getMessage() != null ? e.getMessage() : "Error al registrar salida kiosko");
                    }

                    String matName = materialRepository.findById(matId).map(MaterialEntity::getName).orElse("");
                    out.add(new KioskOutflowSummary(
                            r.getTicketNumber(),
                            matId,
                            matName,
                            kiosk.getId(),
                            kiosk.getName(),
                            take,
                            sale.getSaleNumber(),
                            sale.getId()));
                    remaining = remaining.subtract(take);
                }

                if (remaining.compareTo(BigDecimal.ZERO) > 0) {
                    throw new BusinessException(
                            "Cuero insuficiente para OPL (venta #" + sale.getSaleNumber()
                                    + ", producto " + line.productId() + "). Requerido "
                                    + required.stripTrailingZeros().toPlainString()
                                    + ", en taller "
                                    + workshop.stripTrailingZeros().toPlainString()
                                    + ", faltante sin cubrir "
                                    + remaining.stripTrailingZeros().toPlainString());
                }
            }
        }
        return out;
    }

    private Optional<ProductVariantLeatherEntity> resolveLeatherMapping(Long productId, Long colorId) {
        if (colorId != null) {
            Optional<ProductVariantLeatherEntity> exact =
                    productVariantLeatherRepository.findByProductIdAndColorId(productId, colorId);
            if (exact.isPresent()) {
                return exact;
            }
        }
        return productVariantLeatherRepository.findByProductIdAndColorIdIsNull(productId);
    }

    private BigDecimal sumWorkshopMaterialStock(Long materialId) {
        List<InventoryLocation> rows = inventoryLocationRepository.findByMaterialId(materialId);
        BigDecimal sum = BigDecimal.ZERO;
        for (InventoryLocation row : rows) {
            LocationEntity loc = locationRepository.findById(row.getLocationId()).orElse(null);
            if (!isKioskLocation(loc)) {
                sum = sum.add(row.getQuantity() != null ? row.getQuantity() : BigDecimal.ZERO);
            }
        }
        return sum;
    }

    private BigDecimal getMaterialStockAt(Long materialId, Long locationId) {
        return inventoryLocationRepository.findByMaterialIdAndLocationId(materialId, locationId)
                .map(il -> il.getQuantity() != null ? il.getQuantity() : BigDecimal.ZERO)
                .orElse(BigDecimal.ZERO);
    }

    private boolean isKioskLocation(LocationEntity loc) {
        if (loc == null || loc.getCategoria() == null) {
            return false;
        }
        String c = loc.getCategoria().toUpperCase(Locale.ROOT);
        return c.contains("KIOSKO") || c.contains("KIOSK");
    }
}
