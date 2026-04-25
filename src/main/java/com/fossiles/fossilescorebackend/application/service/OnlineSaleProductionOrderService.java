package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class OnlineSaleProductionOrderService {

    private final ProductionOrderCodeService productionOrderCodeService;
    private final ProductionOrderRepository productionOrderRepository;
    private final ProductionOrderItemRepository productionOrderItemRepository;
    private final OnlineSaleRepository onlineSaleRepository;
    private final OnlineSaleItemRepository onlineSaleItemRepository;
    private final OnlineSaleService saleService;

    // Dependencias para revisión de inventario
    private final ProductInventoryLocationRepository productInventoryLocationRepository;
    private final InventoryLocationTypeRepository inventoryLocationTypeRepository;
    private final LocationRepository locationRepository;

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
        boolean               bodegaPtFound
    ) {}

    public record FulfilledSale(String saleNumber, String customerName, String message) {}

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

        // Buscar ubicación de Bodega Producto Terminado
        LocationEntity bodegaPT = findBodegaPT();
        boolean bodegaPtFound = (bodegaPT != null);

        List<FulfilledSale>  fulfilled  = new ArrayList<>();
        List<OnlineSaleEntity> needProduction = new ArrayList<>();

        for (OnlineSaleEntity sale : sales) {
            if (bodegaPT != null && canFulfillFromInventory(sale, bodegaPT)) {
                // Descontar inventario y marcar como producido
                fulfillFromInventory(sale, bodegaPT);
                sale.setStatus("PRODUCIDO");
                onlineSaleRepository.save(sale);
                fulfilled.add(new FulfilledSale(
                        sale.getSaleNumber(),
                        sale.getCustomerName(),
                        "Despachado desde inventario BODEGA_PT"));
            } else {
                needProduction.add(sale);
            }
        }

        // Crear órdenes de producción para las ventas sin stock
        List<CreateResult> productionResults = new ArrayList<>();
        if (!needProduction.isEmpty()) {
            List<Long> productionSaleIds = needProduction.stream().map(OnlineSaleEntity::getId).toList();
            productionResults = createFromSaleIds(productionSaleIds);
        }

        return new FulfillmentResult(
                fulfilled,
                productionResults,
                fulfilled.size(),
                productionResults.size(),
                bodegaPtFound
        );
    }

    /**
     * Verifica si todos los items de la venta tienen suficiente stock en BODEGA_PT.
     */
    private boolean canFulfillFromInventory(OnlineSaleEntity sale, LocationEntity bodegaPT) {
        List<OnlineSaleItemEntity> items = onlineSaleItemRepository
                .findByOnlineSaleIdOrderByIdAsc(sale.getId());

        if (items != null && !items.isEmpty()) {
            for (OnlineSaleItemEntity item : items) {
                if (item.getProductId() == null) continue;
                int needed = item.getQuantity() != null ? item.getQuantity() : 1;
                BigDecimal stock = getStockInBodegaPT(item.getProductId(), bodegaPT.getId(), item.getColorId());
                if (stock.compareTo(BigDecimal.valueOf(needed)) < 0) {
                    return false;
                }
            }
            return true;
        } else {
            // Modo legado: un solo producto
            if (sale.getProductId() == null) return false;
            int needed = sale.getQuantity() != null ? sale.getQuantity() : 1;
            BigDecimal stock = getStockInBodegaPT(sale.getProductId(), bodegaPT.getId(), sale.getColorId());
            return stock.compareTo(BigDecimal.valueOf(needed)) >= 0;
        }
    }

    /**
     * Descuenta el inventario de BODEGA_PT para todos los items de la venta.
     */
    private void fulfillFromInventory(OnlineSaleEntity sale, LocationEntity bodegaPT) {
        List<OnlineSaleItemEntity> items = onlineSaleItemRepository
                .findByOnlineSaleIdOrderByIdAsc(sale.getId());

        if (items != null && !items.isEmpty()) {
            for (OnlineSaleItemEntity item : items) {
                if (item.getProductId() == null) continue;
                int qty = item.getQuantity() != null ? item.getQuantity() : 1;
                decrementBodegaPT(item.getProductId(), bodegaPT.getId(), item.getColorId(),
                        BigDecimal.valueOf(qty));
            }
        } else {
            if (sale.getProductId() != null) {
                int qty = sale.getQuantity() != null ? sale.getQuantity() : 1;
                decrementBodegaPT(sale.getProductId(), bodegaPT.getId(), sale.getColorId(),
                        BigDecimal.valueOf(qty));
            }
        }
    }

    /**
     * Consulta el stock disponible de un producto+color en BODEGA_PT.
     */
    private BigDecimal getStockInBodegaPT(Long productId, Long locationId, Long colorId) {
        return productInventoryLocationRepository
                .findByProductIdAndLocationIdAndColorId(productId, locationId, colorId)
                .map(pil -> pil.getQuantity() != null ? pil.getQuantity() : BigDecimal.ZERO)
                .orElse(BigDecimal.ZERO);
    }

    /**
     * Decrementa el inventario de un producto en BODEGA_PT.
     */
    private void decrementBodegaPT(Long productId, Long locationId, Long colorId, BigDecimal qty) {
        productInventoryLocationRepository
                .findByProductIdAndLocationIdAndColorId(productId, locationId, colorId)
                .ifPresent(pil -> {
                    BigDecimal newQty = pil.getQuantity().subtract(qty);
                    if (newQty.compareTo(BigDecimal.ZERO) < 0) newQty = BigDecimal.ZERO;
                    pil.setQuantity(newQty);
                    productInventoryLocationRepository.save(pil);
                });
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
    // MÉTODO ORIGINAL: crea órdenes de producción agrupadas por cliente
    // ─────────────────────────────────────────────────────────────

    /**
     * Agrupa las ventas por cliente y crea una orden de producción por cliente.
     */
    @Transactional
    public List<CreateResult> createFromSaleIds(List<Long> saleIds) throws BusinessException {
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

        return results;
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
}
