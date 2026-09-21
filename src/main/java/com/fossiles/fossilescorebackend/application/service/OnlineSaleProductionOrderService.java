package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.InventoryOutflowRequest;
import com.fossiles.fossilescorebackend.application.dto.response.InventoryOutflowResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.*;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductionOrderPlanPriority;
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

    // TODO: Reactivar cuando la configuración/inventario de cuero para OPL esté estabilizada.
    private static final boolean ONLINE_SALE_LEATHER_CHECK_ENABLED = false;

    private final ProductionOrderCodeService productionOrderCodeService;
    private final ProductionOrderRepository productionOrderRepository;
    private final ProductionOrderItemRepository productionOrderItemRepository;
    private final OnlineSaleRepository onlineSaleRepository;
    private final OnlineSaleItemRepository onlineSaleItemRepository;
    private final OnlineSaleService saleService;
    private final OnlineSaleShipmentNumberService onlineSaleShipmentNumberService;

    // Dependencias para revisión de inventario
    private final ProductInventoryService productInventoryService;
    private final InventoryLocationTypeRepository inventoryLocationTypeRepository;
    private final LocationRepository locationRepository;
    private final ProductVariantLeatherRepository productVariantLeatherRepository;
    private final MaterialRepository materialRepository;
    private final InventoryLocationRepository inventoryLocationRepository;
    private final InventoryService inventoryService;
    private final OnlineSaleReturnsWarehouseLocator returnsWarehouseLocator;
    private final ProductionAutoPlannerService productionAutoPlannerService;

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
        LocationEntity bodegaDevoluciones = returnsWarehouseLocator.find().orElse(null);
        List<LocationEntity> sourceWarehouses = List.of(bodegaDevoluciones, bodegaPT).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        boolean bodegaPtFound = !sourceWarehouses.isEmpty();

        List<FulfilledSale>  fulfilled  = new ArrayList<>();
        List<OnlineSaleEntity> needProduction = new ArrayList<>();

        for (OnlineSaleEntity sale : sales) {
            InventorySourceDecision routed = resolveInventorySourceDecision(sale, bodegaDevoluciones, bodegaPT);
            if ("PARCIAL".equals(routed.sourceLabel())) {
                throw new BusinessException(
                        "La venta #" + sale.getSaleNumber()
                                + " tiene inventario parcial por linea: use \"Resolver venta mixta\" "
                                + "en lugar de generar OP automatica sobre toda la venta.");
            }
            if (!sourceWarehouses.isEmpty() && canFulfillFromInventory(sale, sourceWarehouses)) {
                // Descontar inventario y marcar como producido (ENVL se asigna en "Preparar envío", no aquí)
                fulfillFromInventory(sale, sourceWarehouses, false);
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
        LocationEntity bodegaDevoluciones = returnsWarehouseLocator.find().orElse(null);
        List<LocationEntity> sourceWarehouses = List.of(bodegaDevoluciones, bodegaPT).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        boolean bodegaPtFound = !sourceWarehouses.isEmpty();

        List<FulfillmentPreviewRow> rows = sales.stream()
                .map(sale -> {
                    InventorySourceDecision decision = resolveInventorySourceDecision(sale, bodegaDevoluciones, bodegaPT);
                    boolean needOpl = !decision.canFulfill && !"PARCIAL".equals(decision.sourceLabel);
                    LeatherOlpPreview leather = needOpl && ONLINE_SALE_LEATHER_CHECK_ENABLED
                            ? previewLeatherForSale(sale)
                            : LeatherOlpPreview.allGood();
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
                ? getStock(productId, bodegaDevoluciones.getId(), colorId, size)
                : BigDecimal.ZERO;
        BigDecimal stockPt = bodegaPT != null
                ? getStock(productId, bodegaPT.getId(), colorId, size)
                : BigDecimal.ZERO;
        BigDecimal total = stockDev.add(stockPt);
        String suggested = total.compareTo(BigDecimal.valueOf(quantity)) >= 0 ? ACTION_DISPATCH : ACTION_PRODUCE;
        if (ACTION_DISPATCH.equals(suggested)) {
            return new SaleItemStock(saleItemId, productId, productCode, productName,
                    colorId, colorName, size, quantity, stockDev, stockPt, total, suggested,
                    null, null, null, null, null, List.of(), "DESPACHO", List.of(
                            "Stock: primero bodega de devoluciones, luego Bodega PT (mismo orden al preparar)."));
        }
        return buildProduceItemStock(saleItemId, productId, productCode, productName,
                colorId, colorName, size, quantity, stockDev, stockPt, total, suggested);
    }

    private SaleItemStock buildProduceItemStock(
            Long saleItemId, Long productId, String productCode, String productName,
            Long colorId, String colorName, String size, int quantity,
            BigDecimal stockDev, BigDecimal stockPt, BigDecimal total, String suggested
    ) {
        if (!ONLINE_SALE_LEATHER_CHECK_ENABLED) {
            return new SaleItemStock(saleItemId, productId, productCode, productName,
                    colorId, colorName, size, quantity, stockDev, stockPt, total, suggested,
                    null, null, null, null, null, List.of(), "OMITIDO", List.of());
        }

        Optional<ProductVariantLeatherEntity> mapping = resolveLeatherMapping(productId, colorId);
        if (mapping.isEmpty()) {
            return new SaleItemStock(saleItemId, productId, productCode, productName,
                    colorId, colorName, size, quantity, stockDev, stockPt, total, suggested,
                    null, null, null, null, null, List.of(), "SIN_CONFIG",
                    List.of("Sin configuración de cuero para " + formatProductVariant(productCode, productName, colorName) + "."));
        }
        ProductVariantLeatherEntity m = mapping.get();
        BigDecimal qpu = m.getQtyPerUnit() != null ? m.getQtyPerUnit() : BigDecimal.ONE;
        BigDecimal required = qpu.multiply(BigDecimal.valueOf(quantity));
        Long matId = m.getLeatherMaterialId();
        String matName = materialRepository.findById(matId).map(this::formatMaterialLabel).orElse("Material " + matId);

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
                            "Falta cuero " + matName + ": se requieren " + shortfall.stripTrailingZeros().toPlainString()
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
        LocationEntity bodegaDevoluciones = returnsWarehouseLocator.find().orElse(null);
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

        if (!sale.isPaid()) {
            throw new BusinessException("La venta #" + sale.getSaleNumber() + " no tiene pago confirmado (" + sale.getPaymentMethod() + ")");
        }
        if ("ENVIADO".equals(sale.getStatus()) || "ENTREGADO".equals(sale.getStatus())) {
            throw new BusinessException("La venta ya fue despachada. Estado actual: " + sale.getStatus());
        }
        if ("PRODUCIDO".equals(sale.getStatus())) {
            throw new BusinessException("La venta ya esta lista para despacho (PRODUCIDO)");
        }

        LocationEntity bodegaPT = findBodegaPT();
        LocationEntity bodegaDevoluciones = returnsWarehouseLocator.find().orElse(null);
        List<LocationEntity> sourceWarehouses = List.of(bodegaDevoluciones, bodegaPT).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (sourceWarehouses.isEmpty()) {
            throw new BusinessException("No se encontró BODEGA_PT o Bodega Devoluciones configurada");
        }

        List<OnlineSaleItemEntity> detailItems = onlineSaleItemRepository.findByOnlineSaleIdOrderByIdAsc(sale.getId());
        boolean routes = saleItemsUseResolvedRoutes(detailItems);
        boolean produceMarked = saleHasProduceRoute(detailItems);
        boolean inOp = Boolean.TRUE.equals(sale.getInProductionOrder());
        Long poId = sale.getProductionOrderId();

        onlineSaleShipmentNumberService.assignIfMissing(sale);

        if (produceMarked) {
            if (!inOp || poId == null) {
                throw new BusinessException(
                        "Falta orden de produccion vinculada para las lineas PRODUCE; resuelva la venta antes de preparar.");
            }
            if (!produceMarkedLinesFullyReceived(poId, sale.getId(), detailItems)) {
                throw new BusinessException(
                        "Aun no estan todas las lineas PRODUCE recibidas en PT (recepcion OP incompleta).");
            }
            if (!stockCoversRoutedDispatchOrProduce(detailItems, sourceWarehouses)) {
                throw new BusinessException(
                        "Sin stock suficiente en Bodega PT / Devoluciones para todas las lineas de esta venta");
            }
            fulfillRoutedDispatchAndProduce(sale, detailItems, sourceWarehouses);
        } else if (routes) {
            if (inOp) {
                throw new BusinessException(
                        "Estado inconsistente: venta con rutas solo despacho no deberia estar en orden de produccion.");
            }
            if (!stockCoversDispatchRoutesOnly(detailItems, sourceWarehouses)) {
                throw new BusinessException(
                        "Sin stock suficiente en Bodega PT / Devoluciones para las lineas marcadas como despacho");
            }
            fulfillDispatchRoutedOnly(sale, detailItems, sourceWarehouses);
        } else if (inOp) {
            if (poId == null) {
                throw new BusinessException("Venta en OP sin id de orden de produccion");
            }
            if (!legacyAllPoRowsReceivedForSale(poId, sale.getId())) {
                throw new BusinessException(
                        "Aun faltan recepciones en PT para completar la orden de produccion de esta venta.");
            }
            if (!canFulfillFromInventory(sale, sourceWarehouses)) {
                throw new BusinessException("Sin stock suficiente en Bodega PT / Devoluciones para esta venta");
            }
            fulfillFromInventory(sale, sourceWarehouses, false);
        } else {
            if (!canFulfillFromInventory(sale, sourceWarehouses)) {
                throw new BusinessException("Sin stock suficiente en Bodega PT / Devoluciones para esta venta");
            }
            fulfillFromInventory(sale, sourceWarehouses, false);
        }

        sale.setStatus("PRODUCIDO");
        sale.setInProductionOrder(false);
        onlineSaleRepository.save(sale);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("onlineSaleId", sale.getId());
        out.put("saleNumber", sale.getSaleNumber());
        out.put("shipmentNumber", sale.getShipmentNumber());
        out.put("saleStatus", sale.getStatus());
        out.put("message", "Venta preparada desde inventario (primero devoluciones, luego Bodega PT)");
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
                BigDecimal stock = getStockAcrossWarehouses(item.getProductId(), item.getColorId(), sourceWarehouses, item.getSize());
                if (stock.compareTo(BigDecimal.valueOf(needed)) < 0) {
                    return false;
                }
            }
            return true;
        } else {
            // Modo legado: un solo producto
            if (sale.getProductId() == null) return false;
            int needed = sale.getQuantity() != null ? sale.getQuantity() : 1;
            BigDecimal stock = getStockAcrossWarehouses(sale.getProductId(), sale.getColorId(), sourceWarehouses, sale.getSize());
            return stock.compareTo(BigDecimal.valueOf(needed)) >= 0;
        }
    }

    /**
     * Descuenta el inventario para todos los items de la venta.
     * Regla: consumir primero Bodega Devoluciones (si existe) y luego Bodega PT.
     *
     * @param assignShipmentNumber si true, asigna ENVL-nnnnn cuando aún no existe
     */
    private void fulfillFromInventory(
            OnlineSaleEntity sale,
            List<LocationEntity> sourceWarehouses,
            boolean assignShipmentNumber
    ) throws BusinessException {
        if (assignShipmentNumber) {
            onlineSaleShipmentNumberService.assignIfMissing(sale);
        }

        List<OnlineSaleItemEntity> items = onlineSaleItemRepository
                .findByOnlineSaleIdOrderByIdAsc(sale.getId());

        if (items != null && !items.isEmpty()) {
            for (OnlineSaleItemEntity item : items) {
                if (item.getProductId() == null) continue;
                int qty = item.getQuantity() != null ? item.getQuantity() : 1;
                consumeAcrossWarehouses(sale, item.getId(), item.getProductId(), item.getColorId(), BigDecimal.valueOf(qty), item.getSize());
            }
        } else {
            if (sale.getProductId() != null) {
                int qty = sale.getQuantity() != null ? sale.getQuantity() : 1;
                consumeAcrossWarehouses(sale, null, sale.getProductId(), sale.getColorId(), BigDecimal.valueOf(qty), sale.getSize());
            }
        }
    }

    private String normalizeFulfillmentRoute(String r) {
        return r == null ? "" : r.trim().toUpperCase(Locale.ROOT);
    }

    private boolean saleItemsUseResolvedRoutes(List<OnlineSaleItemEntity> items) {
        return items != null && items.stream()
                .anyMatch(i -> i.getFulfillmentRoute() != null && !i.getFulfillmentRoute().isBlank());
    }

    private boolean saleHasProduceRoute(List<OnlineSaleItemEntity> items) {
        return items != null && items.stream()
                .anyMatch(i -> ACTION_PRODUCE.equals(normalizeFulfillmentRoute(i.getFulfillmentRoute())));
    }

    private ProductionOrderItemEntity findPoItemForSaleLine(
            List<ProductionOrderItemEntity> pool,
            Set<Long> consumedPoItemIds,
            Long onlineSaleId,
            OnlineSaleItemEntity si
    ) {
        ProductionOrderItemEntity byLine = pool.stream()
                .filter(p -> !consumedPoItemIds.contains(p.getId()))
                .filter(p -> Objects.equals(p.getOnlineSaleId(), onlineSaleId))
                .filter(p -> si.getId() != null && Objects.equals(si.getId(), p.getOnlineSaleItemId()))
                .findFirst().orElse(null);
        if (byLine != null) {
            return byLine;
        }
        return pool.stream()
                .filter(p -> !consumedPoItemIds.contains(p.getId()))
                .filter(p -> Objects.equals(p.getOnlineSaleId(), onlineSaleId))
                .filter(p -> Objects.equals(p.getProductId(), si.getProductId())
                        && Objects.equals(p.getColorId(), si.getColorId()))
                .findFirst().orElse(null);
    }

    private boolean produceMarkedLinesFullyReceived(Long poId, Long onlineSaleId, List<OnlineSaleItemEntity> items) {
        List<ProductionOrderItemEntity> pool = productionOrderItemRepository.findByProductionOrderId(poId);
        Set<Long> consumed = new HashSet<>();
        for (OnlineSaleItemEntity si : items) {
            if (!ACTION_PRODUCE.equals(normalizeFulfillmentRoute(si.getFulfillmentRoute()))) {
                continue;
            }
            if (si.getProductId() == null) {
                continue;
            }
            int need = si.getQuantity() != null ? si.getQuantity() : 1;
            ProductionOrderItemEntity p = findPoItemForSaleLine(pool, consumed, onlineSaleId, si);
            if (p == null) {
                return false;
            }
            consumed.add(p.getId());
            int got = p.getWarehouseReceivedQty() != null ? p.getWarehouseReceivedQty() : 0;
            if (got < need) {
                return false;
            }
        }
        return true;
    }

    private boolean legacyAllPoRowsReceivedForSale(Long poId, Long onlineSaleId) {
        List<ProductionOrderItemEntity> rows = productionOrderItemRepository.findByProductionOrderId(poId).stream()
                .filter(p -> Objects.equals(p.getOnlineSaleId(), onlineSaleId))
                .toList();
        if (rows.isEmpty()) {
            return false;
        }
        for (ProductionOrderItemEntity p : rows) {
            int q = p.getQuantity() != null ? p.getQuantity() : 1;
            int got = p.getWarehouseReceivedQty() != null ? p.getWarehouseReceivedQty() : 0;
            if (got < q) {
                return false;
            }
        }
        return true;
    }

    private boolean stockCoversRoutedDispatchOrProduce(List<OnlineSaleItemEntity> items, List<LocationEntity> wh) {
        for (OnlineSaleItemEntity item : items) {
            String r = normalizeFulfillmentRoute(item.getFulfillmentRoute());
            if (!ACTION_DISPATCH.equals(r) && !ACTION_PRODUCE.equals(r)) {
                continue;
            }
            if (item.getProductId() == null) {
                continue;
            }
            int needed = item.getQuantity() != null ? item.getQuantity() : 1;
            BigDecimal stock = getStockAcrossWarehouses(item.getProductId(), item.getColorId(), wh, item.getSize());
            if (stock.compareTo(BigDecimal.valueOf(needed)) < 0) {
                return false;
            }
        }
        return true;
    }

    private boolean stockCoversDispatchRoutesOnly(List<OnlineSaleItemEntity> items, List<LocationEntity> wh) {
        for (OnlineSaleItemEntity item : items) {
            if (!ACTION_DISPATCH.equals(normalizeFulfillmentRoute(item.getFulfillmentRoute()))) {
                continue;
            }
            if (item.getProductId() == null) {
                continue;
            }
            int needed = item.getQuantity() != null ? item.getQuantity() : 1;
            BigDecimal stock = getStockAcrossWarehouses(item.getProductId(), item.getColorId(), wh, item.getSize());
            if (stock.compareTo(BigDecimal.valueOf(needed)) < 0) {
                return false;
            }
        }
        return true;
    }

    private void fulfillRoutedDispatchAndProduce(
            OnlineSaleEntity sale,
            List<OnlineSaleItemEntity> items,
            List<LocationEntity> sourceWarehouses
    ) throws BusinessException {
        for (OnlineSaleItemEntity item : items) {
            String r = normalizeFulfillmentRoute(item.getFulfillmentRoute());
            if (!ACTION_DISPATCH.equals(r) && !ACTION_PRODUCE.equals(r)) {
                continue;
            }
            if (item.getProductId() == null) {
                continue;
            }
            int qty = item.getQuantity() != null ? item.getQuantity() : 1;
            consumeAcrossWarehouses(sale, item.getId(), item.getProductId(), item.getColorId(), BigDecimal.valueOf(qty), item.getSize());
        }
    }

    private void fulfillDispatchRoutedOnly(
            OnlineSaleEntity sale,
            List<OnlineSaleItemEntity> items,
            List<LocationEntity> sourceWarehouses
    ) throws BusinessException {
        for (OnlineSaleItemEntity item : items) {
            if (!ACTION_DISPATCH.equals(normalizeFulfillmentRoute(item.getFulfillmentRoute()))) {
                continue;
            }
            if (item.getProductId() == null) {
                continue;
            }
            int qty = item.getQuantity() != null ? item.getQuantity() : 1;
            consumeAcrossWarehouses(sale, item.getId(), item.getProductId(), item.getColorId(), BigDecimal.valueOf(qty), item.getSize());
        }
    }

    /**
     * Consulta el stock disponible de un producto+color en una ubicación (por talla si aplica FOSS con sizes_data).
     */
    private BigDecimal getStock(Long productId, Long locationId, Long colorId, String sizeLabel) {
        return productInventoryService.getAvailableQuantity(productId, locationId, colorId, sizeLabel);
    }

    /**
     * Stock consolidado para venta online: siempre suma primero bodega(s) de devoluciones y luego Bodega PT,
     * alineado con el consumo en {@link #consumeAcrossWarehouses}.
     */
    private BigDecimal getStockAcrossWarehouses(Long productId, Long colorId, List<LocationEntity> sourceWarehouses, String sizeLabel) {
        if (sourceWarehouses == null || sourceWarehouses.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (LocationEntity loc : orderWarehousesReturnsFirst(sourceWarehouses)) {
            total = total.add(getStock(productId, loc.getId(), colorId, sizeLabel));
        }
        return total;
    }

    /**
     * Garantiza orden Devoluciones → PT aunque la lista venga en otro orden.
     */
    private List<LocationEntity> orderWarehousesReturnsFirst(List<LocationEntity> sourceWarehouses) {
        List<LocationEntity> returns = new ArrayList<>();
        List<LocationEntity> rest = new ArrayList<>();
        for (LocationEntity loc : sourceWarehouses) {
            if (loc == null) {
                continue;
            }
            if (isReturnsWarehouseLocation(loc)) {
                returns.add(loc);
            } else {
                rest.add(loc);
            }
        }
        List<LocationEntity> out = new ArrayList<>(returns.size() + rest.size());
        out.addAll(returns);
        out.addAll(rest);
        return out;
    }

    private boolean isReturnsWarehouseLocation(LocationEntity loc) {
        return returnsWarehouseLocator.matchesReturnsWarehouse(loc);
    }

    /**
     * Descuenta el inventario de una línea de venta consumiendo Devoluciones primero y luego Bodega
     * PT. El id de la línea es el candado de idempotencia: sin él, dos tallas del mismo producto y
     * color dentro de la misma venta se tomaban por el mismo movimiento y la segunda no se descargaba.
     */
    private void consumeAcrossWarehouses(
            OnlineSaleEntity sale,
            Long saleItemId,
            Long productId,
            Long colorId,
            BigDecimal qty,
            String sizeLabel) throws BusinessException {
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        String desc = "Prep venta online #" + (sale.getSaleNumber() != null ? sale.getSaleNumber() : sale.getId());
        productInventoryService.decrementFromDispatchWarehouses(
                productId,
                colorId,
                sizeLabel,
                qty,
                ProductInventoryService.REF_ONLINE_SALE_PREPARE,
                sale.getId(),
                sale.getSaleNumber(),
                desc,
                ProductInventoryService.REF_ONLINE_SALE_PREPARE,
                saleItemId);
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

    // ─────────────────────────────────────────────────────────────
    // RESOLVER MIXTO: despachar items con stock + crear OP para los faltantes
    // ─────────────────────────────────────────────────────────────

    /**
     * Resuelve una venta mixta en una sola venta: persiste DISPATCH vs PRODUCE por línea;
     * la OP sólo incluye líneas PRODUCE y enlaza los ítems a la venta original.
     *
     * No descuenta inventario ni marca PRODUCIDO.
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

        for (OnlineSaleItemEntity it : items) {
            if (it.getFulfillmentRoute() != null && !it.getFulfillmentRoute().isBlank()) {
                throw new BusinessException("Esta venta ya fue resuelta por linea; no se puede volver a resolver.");
            }
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

        LocationEntity bodegaPT = findBodegaPT();
        LocationEntity bodegaDevoluciones = returnsWarehouseLocator.find().orElse(null);
        List<LocationEntity> warehouses = List.of(bodegaDevoluciones, bodegaPT).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (warehouses.isEmpty()) {
            throw new BusinessException("No hay bodegas de despacho configuradas para validar stock de lineas DESPACHAR");
        }

        // Caso 1: solo DISPATCH -> rutas persistentes + validar cobertura de stock para despacho
        if (produceIds.isEmpty()) {
            List<OnlineSaleItemEntity> dispatchItems = dispatchIds.stream()
                    .map(itemsById::get)
                    .filter(Objects::nonNull)
                    .toList();
            assertDispatchLinesHaveStock(sale, dispatchItems, warehouses);
            for (OnlineSaleItemEntity it : items) {
                it.setFulfillmentRoute(ACTION_DISPATCH);
                onlineSaleItemRepository.save(it);
            }
            onlineSaleRepository.save(sale);
            return new ResolveMixedResult(sale.getId(), sale.getSaleNumber(), null, null,
                    null, null, items.size(), 0,
                    "Sin OP: mismo numero de venta; pendiente despacho desde PT/Devoluciones cuando marques Listo.",
                    List.of());
        }

        List<OnlineSaleItemEntity> dispatchItemsMixed = dispatchIds.stream()
                .map(itemsById::get)
                .filter(Objects::nonNull)
                .toList();
        if (!dispatchItemsMixed.isEmpty()) {
            assertDispatchLinesHaveStock(sale, dispatchItemsMixed, warehouses);
        }

        for (OnlineSaleItemEntity it : items) {
            it.setFulfillmentRoute(dispatchIds.contains(it.getId()) ? ACTION_DISPATCH : ACTION_PRODUCE);
            onlineSaleItemRepository.save(it);
        }

        // Caso 2: solo PRODUCE — OP sobre la venta original (solo líneas a producir)
        if (dispatchIds.isEmpty()) {
            List<OnlineSaleItemEntity> produceList = produceIds.stream()
                    .map(itemsById::get)
                    .filter(Objects::nonNull)
                    .toList();
            OplCreationResult batch = createOplFromProduceLineItems(sale, produceList);
            CreateResult cr = batch.productionOrders().get(0);
            return new ResolveMixedResult(sale.getId(), sale.getSaleNumber(), null, null,
                    cr.productionOrderId(), cr.productionOrderCode(), 0, produceList.size(),
                    "OP creada sobre la venta #" + sale.getSaleNumber() + ": " + cr.productionOrderCode(),
                    batch.kioskOutflows());
        }

        // Caso 3: mixto — misma venta, OP sólo líneas PRODUCE
        List<OnlineSaleItemEntity> produceList = produceIds.stream()
                .map(itemsById::get)
                .filter(Objects::nonNull)
                .toList();
        sale.setObservations(appendNote(sale.getObservations(),
                "Venta mixta: OP con lineas PRODUCE; mismo numero #" + sale.getSaleNumber()));
        onlineSaleRepository.save(sale);

        OplCreationResult batch = createOplFromProduceLineItems(sale, produceList);
        CreateResult cr = batch.productionOrders().get(0);
        return new ResolveMixedResult(
                sale.getId(), sale.getSaleNumber(),
                null, null,
                cr.productionOrderId(), cr.productionOrderCode(),
                dispatchIds.size(), produceIds.size(),
                "Venta #" + sale.getSaleNumber() + ": " + dispatchIds.size() + " linea(s) despacho cuando listo;"
                        + " OP " + cr.productionOrderCode() + " con " + produceIds.size() + " linea(s) a producir",
                batch.kioskOutflows());
    }

    private String appendNote(String original, String note) {
        if (note == null || note.isBlank()) return original;
        if (original == null || original.isBlank()) return note;
        return original + " | " + note;
    }

    private void assertDispatchLinesHaveStock(
            OnlineSaleEntity sale,
            List<OnlineSaleItemEntity> dispatchItems,
            List<LocationEntity> warehouses
    ) throws BusinessException {
        for (OnlineSaleItemEntity item : dispatchItems) {
            if (item.getProductId() == null) continue;
            int needed = item.getQuantity() != null ? item.getQuantity() : 1;
            BigDecimal stock = getStockAcrossWarehouses(item.getProductId(), item.getColorId(), warehouses, item.getSize());
            if (stock.compareTo(BigDecimal.valueOf(needed)) < 0) {
                String code = item.getProductCode() != null ? item.getProductCode() : String.valueOf(item.getProductId());
                throw new BusinessException(
                        "Stock insuficiente para despacho de " + code + " en venta #" + sale.getSaleNumber()
                                + ". Necesario: " + needed + ", disponible PT/Devoluciones: " + stock.stripTrailingZeros().toPlainString());
            }
        }
    }

    /**
     * Una OP sólo para líneas PRODUCE de una venta; enlaza ítems y vincula la venta madre ({@link OnlineSaleService#linkToProductionOrder}).
     */
    private OplCreationResult createOplFromProduceLineItems(OnlineSaleEntity sale, List<OnlineSaleItemEntity> produceItems)
            throws BusinessException {
        Objects.requireNonNull(sale);
        if (produceItems == null || produceItems.isEmpty()) {
            throw new BusinessException("No hay items para producir");
        }
        if (Boolean.TRUE.equals(sale.getInProductionOrder())) {
            throw new BusinessException("La venta #" + sale.getSaleNumber() + " ya está en una orden de producción");
        }
        if (!sale.isPaid()) {
            throw new BusinessException(
                    "La venta #" + sale.getSaleNumber() + " no tiene pago confirmado (" + sale.getPaymentMethod() + ")");
        }

        List<KioskOutflowSummary> kioskOutflows = validateLeatherAndConsumeKioskSubset(sale, produceItems);

        String customer = sale.getCustomerName() != null ? sale.getCustomerName().trim() : "Sin nombre";
        int corr = productionOrderCodeService.getNextCorrelative("OPL");
        String orderCode = "OPL-" + corr;
        if (productionOrderRepository.existsByCode(orderCode)) {
            throw new BusinessException("El código de orden ya existe: " + orderCode);
        }

        ProductionOrderEntity po = ProductionOrderEntity.builder()
                .code(orderCode)
                .orderType("VENTA_EN_LINEA")
                .customerName(customer)
                .startDate(LocalDate.now())
                .deliveryDate(LocalDate.now().plusDays(1))
                .observations(buildOplHeaderObservations(
                        "Orden desde venta online #" + sale.getSaleNumber()
                                + " (lineas PRODUCE). Cliente: " + customer + ".",
                        List.of(sale)))
                .status("PENDING")
                .schedulingPriority(ProductionOrderPlanPriority.OPL)
                .build();
        ProductionOrderEntity savedPO = productionOrderRepository.save(po);

        for (OnlineSaleItemEntity si : produceItems) {
            if (si.getProductId() == null) continue;
            ProductionOrderItemEntity item = ProductionOrderItemEntity.builder()
                    .productionOrderId(savedPO.getId())
                    .onlineSaleId(sale.getId())
                    .onlineSaleItemId(si.getId())
                    .productId(si.getProductId())
                    .colorId(si.getColorId())
                    .quantity(si.getQuantity() != null ? si.getQuantity() : 1)
                    .warehouseReceivedQty(0)
                    .observations(buildOplItemObservations(sale, si.getSize()))
                    .build();
            productionOrderItemRepository.save(item);
        }

        saleService.linkToProductionOrder(List.of(sale.getId()), savedPO.getId());
        productionAutoPlannerService.planQuietly(savedPO.getId());
        return new OplCreationResult(List.of(new CreateResult(savedPO.getId(), orderCode, 1, customer)), kioskOutflows);
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
                    .observations(buildOplHeaderObservations(
                            "Orden generada desde ventas online. Cliente: " + customer + ". Ventas: " +
                                    customerSales.stream().map(s -> "#" + s.getSaleNumber()).reduce((a, b) -> a + ", " + b).orElse(""),
                            customerSales))
                    .status("PENDING")
                    .schedulingPriority(ProductionOrderPlanPriority.OPL)
                    .build();
            ProductionOrderEntity savedPO = productionOrderRepository.save(po);

            List<Long> customerSaleIds = new ArrayList<>();
            for (OnlineSaleEntity sale : customerSales) {
                customerSaleIds.add(sale.getId());
                createOrderItems(savedPO.getId(), sale);
            }

            saleService.linkToProductionOrder(customerSaleIds, savedPO.getId());
            productionAutoPlannerService.planQuietly(savedPO.getId());
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
                        .onlineSaleItemId(si.getId())
                        .productId(si.getProductId())
                        .colorId(si.getColorId())
                        .quantity(si.getQuantity() != null ? si.getQuantity() : 1)
                        .warehouseReceivedQty(0)
                        .observations(buildOplItemObservations(sale, si.getSize()))
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
                    .observations(buildOplItemObservations(sale, sale.getSize()))
                    .build();
            productionOrderItemRepository.save(item);
        }
    }

    /** Stub de OPL + observaciones reales de la(s) venta(s) online (máx. 1000). */
    private String buildOplHeaderObservations(String stub, List<OnlineSaleEntity> sales) {
        String base = stub != null ? stub.trim() : "";
        if (sales == null || sales.isEmpty()) {
            return truncate(base, 1000);
        }
        StringBuilder sb = new StringBuilder(base);
        for (OnlineSaleEntity sale : sales) {
            String obs = sale != null ? safeTrim(sale.getObservations()) : "";
            if (obs.isEmpty()) continue;
            String saleRef = sale.getSaleNumber() != null ? sale.getSaleNumber() : String.valueOf(sale.getId());
            String chunk = " | Obs. venta #" + saleRef + ": " + obs;
            if (sb.length() + chunk.length() > 1000) {
                int room = 1000 - sb.length();
                if (room > 20) {
                    sb.append(chunk, 0, room - 1).append("…");
                }
                break;
            }
            sb.append(chunk);
        }
        return truncate(sb.toString(), 1000);
    }

    /** Referencia de ítem + observación de la venta (máx. 500). */
    private String buildOplItemObservations(OnlineSaleEntity sale, String size) {
        String stub = "Venta #" + (sale.getSaleNumber() != null ? sale.getSaleNumber() : "")
                + " - " + (sale.getCustomerName() != null ? sale.getCustomerName() : "")
                + (size != null && !size.isBlank() ? " | Talla: " + size.trim() : "");
        String obs = safeTrim(sale.getObservations());
        if (obs.isEmpty()) {
            return truncate(stub, 500);
        }
        return truncate(stub + " | Obs: " + obs, 500);
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        if (value.length() <= max) return value;
        if (max <= 1) return "…";
        return value.substring(0, max - 1) + "…";
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
                lines.add(formatProductVariant(line.productCode(), line.productName(), line.colorName())
                        + ": sin configuración de cuero.");
                continue;
            }
            ProductVariantLeatherEntity m = mapping.get();
            BigDecimal qpu = m.getQtyPerUnit() != null ? m.getQtyPerUnit() : BigDecimal.ONE;
            BigDecimal required = qpu.multiply(BigDecimal.valueOf(line.quantity()));
            Long matId = m.getLeatherMaterialId();
            String leatherLabel = materialRepository.findById(matId)
                    .map(this::formatMaterialLabel)
                    .orElse("Material " + matId);
            BigDecimal workshop = sumWorkshopMaterialStock(matId);
            BigDecimal shortfall = required.subtract(workshop);
            if (shortfall.compareTo(BigDecimal.ZERO) <= 0) {
                lines.add(formatProductVariant(line.productCode(), line.productName(), line.colorName())
                        + ": cuero " + leatherLabel + " OK en taller/bodegas (necesario "
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
                lines.add(formatProductVariant(line.productCode(), line.productName(), line.colorName())
                        + ": falta cuero " + leatherLabel + ". Requerido total "
                        + required.stripTrailingZeros().toPlainString()
                        + ", taller "
                        + workshop.stripTrailingZeros().toPlainString()
                        + " — kioskos no cubren el faltante.");
            } else {
                lines.add(formatProductVariant(line.productCode(), line.productName(), line.colorName())
                        + ": faltante de cuero " + leatherLabel + " "
                        + shortfall.stripTrailingZeros().toPlainString()
                        + " cubrible desde kiosko(s) al procesar.");
            }
        }
        return new LeatherOlpPreview(allOk, List.copyOf(lines));
    }

    private record SaleLine(
            Long productId,
            String productCode,
            String productName,
            Long colorId,
            String colorName,
            int quantity
    ) {}

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
                        it.getProductCode(),
                        it.getProductName(),
                        it.getColorId(),
                        it.getColorName(),
                        it.getQuantity() != null ? it.getQuantity() : 1));
            }
        } else if (sale.getProductId() != null) {
            lines.add(new SaleLine(
                    sale.getProductId(),
                    sale.getProductCode(),
                    sale.getProductName(),
                    sale.getColorId(),
                    sale.getColorName(),
                    sale.getQuantity() != null ? sale.getQuantity() : 1));
        }
        return lines;
    }

    private List<SaleLine> flattenSaleLinesFromItems(List<OnlineSaleItemEntity> items) {
        List<SaleLine> lines = new ArrayList<>();
        if (items == null) {
            return lines;
        }
        for (OnlineSaleItemEntity it : items) {
            if (it.getProductId() == null) {
                continue;
            }
            lines.add(new SaleLine(
                    it.getProductId(),
                    it.getProductCode(),
                    it.getProductName(),
                    it.getColorId(),
                    it.getColorName(),
                    it.getQuantity() != null ? it.getQuantity() : 1));
        }
        return lines;
    }

    private List<KioskOutflowSummary> validateLeatherAndConsumeKioskSubset(OnlineSaleEntity sale,
            List<OnlineSaleItemEntity> subsetItems)
            throws BusinessException {
        if (!ONLINE_SALE_LEATHER_CHECK_ENABLED) {
            return List.of();
        }
        List<KioskOutflowSummary> out = new ArrayList<>();
        List<LocationEntity> kiosks = locationRepository.findByCategoriaIgnoreCaseOrderByNameAsc("KIOSKO");
        for (SaleLine line : flattenSaleLinesFromItems(subsetItems)) {
            leatherValidateOneLineConsumeKiosks(sale, line, kiosks, out);
        }
        return out;
    }

    /** Validación/consume cuero por una línea (se reutiliza con distintos orígenes de líneas). */
    private void leatherValidateOneLineConsumeKiosks(
            OnlineSaleEntity sale,
            SaleLine line,
            List<LocationEntity> kiosks,
            List<KioskOutflowSummary> out
    ) throws BusinessException {
        ProductVariantLeatherEntity mapping = resolveLeatherMapping(line.productId(), line.colorId())
                .orElseThrow(() -> new BusinessException(
                        "Configure cuero por variante para "
                                + formatProductVariant(line.productCode(), line.productName(), line.colorName())
                                + ") antes de crear OPL — venta #" + sale.getSaleNumber()));

        BigDecimal qpu = mapping.getQtyPerUnit() != null ? mapping.getQtyPerUnit() : BigDecimal.ONE;
        BigDecimal required = qpu.multiply(BigDecimal.valueOf(line.quantity()));
        Long matId = mapping.getLeatherMaterialId();

        BigDecimal workshop = sumWorkshopMaterialStock(matId);
        BigDecimal needFromKiosks = required.subtract(workshop);
        if (needFromKiosks.compareTo(BigDecimal.ZERO) <= 0) {
            return;
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

    private List<KioskOutflowSummary> validateLeatherAndConsumeKiosk(List<OnlineSaleEntity> sales)
            throws BusinessException {
        if (!ONLINE_SALE_LEATHER_CHECK_ENABLED) {
            return List.of();
        }

        List<KioskOutflowSummary> out = new ArrayList<>();
        List<LocationEntity> kiosks = locationRepository.findByCategoriaIgnoreCaseOrderByNameAsc("KIOSKO");

        for (OnlineSaleEntity sale : sales) {
            for (SaleLine line : flattenSaleLines(sale)) {
                leatherValidateOneLineConsumeKiosks(sale, line, kiosks, out);
            }
        }
        return out;
    }

    private String formatProductVariant(String productCode, String productName, String colorName) {
        List<String> parts = new ArrayList<>();
        if (productCode != null && !productCode.isBlank()) {
            parts.add(productCode);
        }
        if (productName != null && !productName.isBlank()) {
            parts.add(productName);
        }
        String productLabel = String.join(" ", parts);
        if (productLabel.isBlank()) {
            productLabel = "Producto";
        }
        if (colorName == null || colorName.isBlank()) {
            return productLabel;
        }
        return productLabel + " - " + colorName;
    }

    private String formatMaterialLabel(MaterialEntity material) {
        List<String> parts = new ArrayList<>();
        if (material.getSku() != null && !material.getSku().isBlank()) {
            parts.add(material.getSku());
        }
        if (material.getName() != null && !material.getName().isBlank()) {
            parts.add(material.getName());
        }
        String label = String.join(" - ", parts);
        return label.isBlank() ? "Material " + material.getId() : label;
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
