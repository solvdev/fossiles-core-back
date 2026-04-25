package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PurchaseReportService {

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final MaterialRepository materialRepository;
    private final ProductRepository productRepository;
    private final ProductCategoryRepository productCategoryRepository;
    private final SystemConfigRepository systemConfigRepository;
    private final BomRepository bomRepository;
    private final BomItemRepository bomItemRepository;
    private final SupplierRepository supplierRepository;
    private final MaterialReceiptRepository materialReceiptRepository;
    private final AccountingEntryRepository accountingEntryRepository;
    private final StockIntelligenceService stockIntelligenceService;
    private final AccountingService accountingService;
    private final InventoryService inventoryService;

    // CP-08-001: Reporte de Órdenes por Estado
    public PurchaseReportResponse.OrdersByStatusReport getOrdersByStatusReport(LocalDate startDate, LocalDate endDate) {
        List<PurchaseOrderEntity> orders;
        
        if (startDate != null && endDate != null) {
            orders = purchaseOrderRepository.findByOrderDateBetween(startDate, endDate);
        } else {
            orders = purchaseOrderRepository.findAll();
        }

        Map<String, PurchaseReportResponse.StatusSummary> statusMap = new HashMap<>();
        BigDecimal grandTotal = BigDecimal.ZERO;
        int totalOrders = orders.size();

        for (PurchaseOrderEntity order : orders) {
            String status = order.getStatus();
            statusMap.putIfAbsent(status, PurchaseReportResponse.StatusSummary.builder()
                    .status(status)
                    .count(0)
                    .totalAmount(BigDecimal.ZERO)
                    .build());

            PurchaseReportResponse.StatusSummary summary = statusMap.get(status);
            summary.setCount(summary.getCount() + 1);
            summary.setTotalAmount(summary.getTotalAmount().add(order.getTotal() != null ? order.getTotal() : BigDecimal.ZERO));
            grandTotal = grandTotal.add(order.getTotal() != null ? order.getTotal() : BigDecimal.ZERO);
        }

        // Calcular porcentajes
        for (PurchaseReportResponse.StatusSummary summary : statusMap.values()) {
            if (grandTotal.compareTo(BigDecimal.ZERO) > 0) {
                double percentage = summary.getTotalAmount()
                        .divide(grandTotal, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .doubleValue();
                summary.setPercentage(percentage);
            } else {
                summary.setPercentage(0.0);
            }
        }

        return PurchaseReportResponse.OrdersByStatusReport.builder()
                .statusSummary(statusMap)
                .totalAmount(grandTotal)
                .totalOrders(totalOrders)
                .startDate(startDate)
                .endDate(endDate)
                .build();
    }

    // CP-08-002: Reporte de Compras por Proveedor
    public PurchaseReportResponse.PurchasesBySupplierReport getPurchasesBySupplierReport(LocalDate startDate, LocalDate endDate) {
        List<PurchaseOrderEntity> orders;
        
        if (startDate != null && endDate != null) {
            orders = purchaseOrderRepository.findByOrderDateBetween(startDate, endDate);
        } else {
            orders = purchaseOrderRepository.findAll();
        }

        Map<Long, PurchaseReportResponse.SupplierSummary> supplierMap = new HashMap<>();
        BigDecimal grandTotal = BigDecimal.ZERO;
        int totalOrders = orders.size();

        for (PurchaseOrderEntity order : orders) {
            Long supplierId = order.getSupplierId();
            SupplierEntity supplier = supplierRepository.findById(supplierId).orElse(null);
            
            supplierMap.putIfAbsent(supplierId, PurchaseReportResponse.SupplierSummary.builder()
                    .supplierId(supplierId)
                    .supplierName(supplier != null ? supplier.getName() : "Desconocido")
                    .orderCount(0)
                    .totalAmount(BigDecimal.ZERO)
                    .build());

            PurchaseReportResponse.SupplierSummary summary = supplierMap.get(supplierId);
            summary.setOrderCount(summary.getOrderCount() + 1);
            summary.setTotalAmount(summary.getTotalAmount().add(order.getTotal() != null ? order.getTotal() : BigDecimal.ZERO));
            grandTotal = grandTotal.add(order.getTotal() != null ? order.getTotal() : BigDecimal.ZERO);
        }

        // Calcular porcentajes y ordenar
        BigDecimal finalGrandTotal = grandTotal;
        List<PurchaseReportResponse.SupplierSummary> suppliers = supplierMap.values().stream()
                .peek(summary -> {
                    if (finalGrandTotal.compareTo(BigDecimal.ZERO) > 0) {
                        double percentage = summary.getTotalAmount()
                                .divide(finalGrandTotal, 4, RoundingMode.HALF_UP)
                                .multiply(BigDecimal.valueOf(100))
                                .doubleValue();
                        summary.setPercentage(percentage);
                    } else {
                        summary.setPercentage(0.0);
                    }
                })
                .sorted((a, b) -> b.getTotalAmount().compareTo(a.getTotalAmount()))
                .collect(Collectors.toList());

        return PurchaseReportResponse.PurchasesBySupplierReport.builder()
                .suppliers(suppliers)
                .grandTotal(grandTotal)
                .totalOrders(totalOrders)
                .startDate(startDate)
                .endDate(endDate)
                .build();
    }

    // CP-08-003: Reporte de Inventario Actual
    public PurchaseReportResponse.CurrentInventoryReport getCurrentInventoryReport() {
        List<MaterialEntity> materials = materialRepository.findAll();
        
        List<PurchaseReportResponse.MaterialInventoryItem> items = new ArrayList<>();
        BigDecimal totalValue = BigDecimal.ZERO;
        int criticalCount = 0;

        for (MaterialEntity material : materials) {
            // Obtener stock actual del inventario
            BigDecimal currentStock = BigDecimal.ZERO;
            try {
                com.fossiles.fossilescorebackend.application.dto.response.MaterialInventoryResponse inventory = 
                    inventoryService.getMaterialInventory(material.getId());
                currentStock = inventory.getTotalQuantity() != null ? inventory.getTotalQuantity() : BigDecimal.ZERO;
            } catch (com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException e) {
                // Si no existe inventario, el stock es cero
                currentStock = BigDecimal.ZERO;
            }
            
            BigDecimal unitCost = material.getUnitCost() != null ? material.getUnitCost() : BigDecimal.ZERO;
            BigDecimal totalItemValue = currentStock.multiply(unitCost);
            
            BigDecimal minStock = material.getMin() != null ? BigDecimal.valueOf(material.getMin()) : BigDecimal.ZERO;
            boolean isCritical = currentStock.compareTo(minStock) <= 0;
            
            if (isCritical) {
                criticalCount++;
            }

            items.add(PurchaseReportResponse.MaterialInventoryItem.builder()
                    .materialId(material.getId())
                    .sku(material.getSku())
                    .name(material.getName())
                    .currentStock(currentStock)
                    .minStock(minStock)
                    .maxStock(material.getMax() != null ? BigDecimal.valueOf(material.getMax()) : BigDecimal.ZERO)
                    .unitCost(unitCost)
                    .totalValue(totalItemValue)
                    .isCritical(isCritical)
                    .status(material.getStatus())
                    .build());

            totalValue = totalValue.add(totalItemValue);
        }

        return PurchaseReportResponse.CurrentInventoryReport.builder()
                .materials(items)
                .totalInventoryValue(totalValue)
                .totalMaterials(materials.size())
                .criticalMaterialsCount(criticalCount)
                .build();
    }

    // CP-08-004: Reporte de Materiales Críticos
    public PurchaseReportResponse.CriticalMaterialsReport getCriticalMaterialsReport() {
        List<MaterialEntity> materials = materialRepository.findAll();
        
        List<PurchaseReportResponse.CriticalMaterialItem> criticalItems = new ArrayList<>();

        for (MaterialEntity material : materials) {
            // Obtener stock actual del inventario
            BigDecimal currentStock = BigDecimal.ZERO;
            try {
                com.fossiles.fossilescorebackend.application.dto.response.MaterialInventoryResponse inventory = 
                    inventoryService.getMaterialInventory(material.getId());
                currentStock = inventory.getTotalQuantity() != null ? inventory.getTotalQuantity() : BigDecimal.ZERO;
            } catch (com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException e) {
                // Si no existe inventario, el stock es cero
                currentStock = BigDecimal.ZERO;
            }
            
            BigDecimal minStock = material.getMin() != null ? BigDecimal.valueOf(material.getMin()) : BigDecimal.ZERO;
            
            // Solo incluir materiales críticos (stock <= mínimo)
            if (currentStock.compareTo(minStock) <= 0) {
                BigDecimal reorderPoint = stockIntelligenceService.calculateReorderPoint(material.getId());
                BigDecimal daysOfInventory = stockIntelligenceService.calculateDaysOfInventory(material.getId());
                BigDecimal suggestedQuantity = reorderPoint.multiply(BigDecimal.valueOf(1.5)); // 1.5x el punto de reorden
                
                String priority = "MEDIA";
                if (currentStock.compareTo(minStock.multiply(BigDecimal.valueOf(0.5))) <= 0) {
                    priority = "ALTA";
                } else if (currentStock.compareTo(minStock) == 0) {
                    priority = "BAJA";
                }

                BigDecimal unitCost = material.getUnitCost() != null ? material.getUnitCost() : BigDecimal.ZERO;
                BigDecimal totalValue = currentStock.multiply(unitCost);

                criticalItems.add(PurchaseReportResponse.CriticalMaterialItem.builder()
                        .materialId(material.getId())
                        .sku(material.getSku())
                        .name(material.getName())
                        .currentStock(currentStock)
                        .minStock(minStock)
                        .reorderPoint(reorderPoint)
                        .daysOfInventory(daysOfInventory)
                        .suggestedQuantity(suggestedQuantity)
                        .priority(priority)
                        .unitCost(unitCost)
                        .totalValue(totalValue)
                        .build());
            }
        }

        // Ordenar por prioridad y stock
        criticalItems.sort((a, b) -> {
            int priorityCompare = a.getPriority().compareTo(b.getPriority());
            if (priorityCompare != 0) return priorityCompare;
            return a.getCurrentStock().compareTo(b.getCurrentStock());
        });

        return PurchaseReportResponse.CriticalMaterialsReport.builder()
                .materials(criticalItems)
                .totalCritical(criticalItems.size())
                .build();
    }

    // CP-08-005: Reporte de Asientos Contables
    public PurchaseReportResponse.AccountingEntriesReport getAccountingEntriesReport(
            LocalDate startDate, LocalDate endDate, String documentType) {
        
        LocalDateTime startDateTime = startDate != null ? startDate.atStartOfDay() : null;
        LocalDateTime endDateTime = endDate != null ? endDate.atTime(23, 59, 59) : null;

        List<AccountingEntryEntity> entries;
        
        if (documentType != null && !documentType.isEmpty()) {
            if (startDateTime != null && endDateTime != null) {
                entries = accountingEntryRepository.findByDocumentTypeAndEntryDateBetween(documentType, startDateTime, endDateTime);
            } else {
                entries = accountingEntryRepository.findByDocumentType(documentType);
            }
        } else if (startDateTime != null && endDateTime != null) {
            entries = accountingEntryRepository.findByEntryDateBetween(startDateTime, endDateTime);
        } else {
            entries = accountingEntryRepository.findAll();
        }

        BigDecimal totalDebits = entries.stream()
                .map(e -> e.getDebitAmount() != null ? e.getDebitAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalCredits = entries.stream()
                .map(e -> e.getCreditAmount() != null ? e.getCreditAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal balance = totalDebits.subtract(totalCredits);

        // Agrupar por tipo de documento
        Map<String, PurchaseReportResponse.DocumentTypeSummary> byType = new HashMap<>();
        for (AccountingEntryEntity entry : entries) {
            String type = entry.getDocumentType();
            byType.putIfAbsent(type, PurchaseReportResponse.DocumentTypeSummary.builder()
                    .documentType(type)
                    .count(0)
                    .totalDebits(BigDecimal.ZERO)
                    .totalCredits(BigDecimal.ZERO)
                    .build());

            PurchaseReportResponse.DocumentTypeSummary summary = byType.get(type);
            summary.setCount(summary.getCount() + 1);
            summary.setTotalDebits(summary.getTotalDebits().add(
                    entry.getDebitAmount() != null ? entry.getDebitAmount() : BigDecimal.ZERO));
            summary.setTotalCredits(summary.getTotalCredits().add(
                    entry.getCreditAmount() != null ? entry.getCreditAmount() : BigDecimal.ZERO));
        }

        // Convertir entidades a DTOs usando el servicio de contabilidad
        // Primero obtener todos los asientos y filtrar por los IDs que necesitamos
        List<Long> entryIds = entries.stream().map(AccountingEntryEntity::getId).collect(Collectors.toList());
        List<AccountingEntryResponse> allEntries = accountingService.getAllEntries();
        List<AccountingEntryResponse> entryResponses = allEntries.stream()
                .filter(e -> entryIds.contains(e.getId()))
                .collect(Collectors.toList());

        return PurchaseReportResponse.AccountingEntriesReport.builder()
                .entries(entryResponses)
                .totalDebits(totalDebits)
                .totalCredits(totalCredits)
                .balance(balance)
                .byDocumentType(byType)
                .startDate(startDate)
                .endDate(endDate)
                .build();
    }

    // CP-08-006: Dashboard Ejecutivo
    public PurchaseReportResponse.ExecutiveDashboard getExecutiveDashboard(LocalDate startDate, LocalDate endDate) {
        List<PurchaseOrderEntity> orders;
        
        if (startDate != null && endDate != null) {
            orders = purchaseOrderRepository.findByOrderDateBetween(startDate, endDate);
        } else {
            // Últimos 30 días por defecto
            LocalDate defaultEnd = LocalDate.now();
            LocalDate defaultStart = defaultEnd.minusDays(30);
            orders = purchaseOrderRepository.findByOrderDateBetween(defaultStart, defaultEnd);
            startDate = defaultStart;
            endDate = defaultEnd;
        }

        BigDecimal totalPurchased = orders.stream()
                .map(o -> o.getTotal() != null ? o.getTotal() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long activeSuppliers = orders.stream()
                .map(PurchaseOrderEntity::getSupplierId)
                .distinct()
                .count();

        long pendingOrders = orders.stream()
                .filter(o -> "CREADA".equals(o.getStatus()) || "PARCIALMENTE_RECIBIDA".equals(o.getStatus()))
                .count();

        long receivedOrders = orders.stream()
                .filter(o -> "RECIBIDA".equals(o.getStatus()))
                .count();

        BigDecimal averageOrderValue = orders.isEmpty() ? BigDecimal.ZERO :
                totalPurchased.divide(BigDecimal.valueOf(orders.size()), 2, RoundingMode.HALF_UP);

        // Materiales críticos
        PurchaseReportResponse.CriticalMaterialsReport criticalReport = getCriticalMaterialsReport();

        // Órdenes recientes (últimas 10)
        List<PurchaseReportResponse.RecentOrder> recentOrders = orders.stream()
                .sorted((a, b) -> b.getOrderDate().compareTo(a.getOrderDate()))
                .limit(10)
                .map(order -> {
                    SupplierEntity supplier = supplierRepository.findById(order.getSupplierId()).orElse(null);
                    return PurchaseReportResponse.RecentOrder.builder()
                            .id(order.getId())
                            .code(order.getCode())
                            .supplierName(supplier != null ? supplier.getName() : "Desconocido")
                            .total(order.getTotal())
                            .status(order.getStatus())
                            .orderDate(order.getOrderDate())
                            .build();
                })
                .collect(Collectors.toList());

        // Tendencia mensual
        Map<String, BigDecimal> monthlyTrend = new LinkedHashMap<>();
        LocalDate current = startDate;
        while (!current.isAfter(endDate)) {
            LocalDate monthStart = current.withDayOfMonth(1);
            LocalDate monthEnd = monthStart.plusMonths(1).minusDays(1);
            if (monthEnd.isAfter(endDate)) monthEnd = endDate;

            BigDecimal monthTotal = purchaseOrderRepository.findByOrderDateBetween(monthStart, monthEnd).stream()
                    .map(o -> o.getTotal() != null ? o.getTotal() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            monthlyTrend.put(monthStart.toString().substring(0, 7), monthTotal);
            current = monthStart.plusMonths(1);
        }

        PurchaseReportResponse.DashboardMetrics metrics = PurchaseReportResponse.DashboardMetrics.builder()
                .totalPurchasedPeriod(totalPurchased)
                .totalOrders(orders.size())
                .activeSuppliers((int) activeSuppliers)
                .criticalMaterials(criticalReport.getTotalCritical())
                .averageOrderValue(averageOrderValue)
                .pendingOrders((int) pendingOrders)
                .receivedOrders((int) receivedOrders)
                .build();

        return PurchaseReportResponse.ExecutiveDashboard.builder()
                .metrics(metrics)
                .recentOrders(recentOrders)
                .topCriticalMaterials(criticalReport.getMaterials().stream().limit(10).collect(Collectors.toList()))
                .monthlyTrend(monthlyTrend)
                .build();
    }

    private static final BigDecimal LEATHER_COST_PER_SQFT = new BigDecimal("7.75");

    // CP-08-007: Reporte promedio de costos por producto
    public PurchaseReportResponse.ProductAverageCostReport getProductAverageCostReport() {
        List<ProductEntity> products = productRepository.findAll();
        List<MaterialEntity> materials = materialRepository.findAll();
        List<ProductCategoryEntity> categories = productCategoryRepository.findAll();

        Map<Long, MaterialEntity> materialById = materials.stream()
                .collect(Collectors.toMap(MaterialEntity::getId, m -> m, (a, b) -> a));
        Map<Long, ProductCategoryEntity> categoryById = categories.stream()
                .collect(Collectors.toMap(ProductCategoryEntity::getId, c -> c, (a, b) -> a));

        BigDecimal costoHoraCinchos = getCurrentCinchosHourlyCost();
        BigDecimal costoHoraMesas = getCurrentMesasHourlyCost();

        List<PurchaseReportResponse.ProductAverageCostItem> productCosts = new ArrayList<>();
        BigDecimal totalAverageCost = BigDecimal.ZERO;
        int productsWithRecipe = 0;

        for (ProductEntity product : products) {
            List<BomEntity> allBoms = bomRepository.findByProductId(product.getId());
            if (allBoms == null || allBoms.isEmpty()) {
                continue;
            }

            List<BomEntity> activeBoms = allBoms.stream()
                    .filter(b -> b.getStatus() != null && "A".equalsIgnoreCase(b.getStatus()))
                    .collect(Collectors.toList());
            List<BomEntity> candidateBoms = activeBoms.isEmpty() ? allBoms : activeBoms;
            BomEntity bomForCost = pickBomForCostReport(candidateBoms, materialById);
            if (bomForCost == null) {
                continue;
            }

            List<BomItemEntity> items = bomItemRepository.findByBomId(bomForCost.getId());
            if (items == null || items.isEmpty()) {
                continue;
            }

            int recipeItemsCount = 0;
            int recipeItemsWithoutCost = 0;

            BigDecimal leatherConsumption = product.getLeatherConsumption() != null
                    ? product.getLeatherConsumption() : BigDecimal.ZERO;
            BigDecimal leatherCost = leatherConsumption.multiply(LEATHER_COST_PER_SQFT);
            BigDecimal manufacturingCost = getProductManufacturingCost(
                    product,
                    categoryById.get(product.getCategoryId()),
                    costoHoraCinchos,
                    costoHoraMesas);

            BigDecimal recipeCost = leatherCost.add(manufacturingCost);
            for (BomItemEntity item : items) {
                recipeItemsCount++;
                MaterialEntity material = materialById.get(item.getMaterialId());

                if (material != null && Boolean.TRUE.equals(material.getIsPrimaryLeather())) {
                    continue;
                }

                BigDecimal unitCost = material != null ? material.getUnitCost() : null;
                if (unitCost == null || unitCost.compareTo(BigDecimal.ZERO) <= 0) {
                    unitCost = material != null ? material.getCost() : null;
                }
                if (unitCost == null || unitCost.compareTo(BigDecimal.ZERO) <= 0) {
                    recipeItemsWithoutCost++;
                    continue;
                }

                BigDecimal qty = item.getQuantity() != null ? item.getQuantity() : BigDecimal.ZERO;
                BigDecimal measurement = item.getMeasurement() != null ? item.getMeasurement() : BigDecimal.ONE;
                BigDecimal lineCost = qty.multiply(unitCost);
                if (measurement.compareTo(BigDecimal.ZERO) > 0 && measurement.compareTo(BigDecimal.ONE) != 0) {
                    lineCost = lineCost.multiply(measurement);
                }
                recipeCost = recipeCost.add(lineCost);
            }

            productsWithRecipe++;
            BigDecimal costTotal = recipeCost.setScale(2, RoundingMode.HALF_UP);
            BigDecimal min = costTotal;
            BigDecimal max = costTotal;
            totalAverageCost = totalAverageCost.add(costTotal);

            BigDecimal rawSale = product.getSalePrice() != null ? product.getSalePrice() : BigDecimal.ZERO;
            BigDecimal salePrice = rawSale.setScale(2, RoundingMode.HALF_UP);

            BigDecimal costVsSalePercentage = null;
            if (salePrice.compareTo(BigDecimal.ZERO) > 0) {
                costVsSalePercentage = costTotal.multiply(BigDecimal.valueOf(100))
                        .divide(salePrice, 4, RoundingMode.HALF_UP);
            }

            productCosts.add(PurchaseReportResponse.ProductAverageCostItem.builder()
                    .productId(product.getId())
                    .productCode(product.getCode())
                    .productName(product.getName())
                    .recipeCount(1)
                    .averageRecipeCost(costTotal)
                    .salePrice(salePrice)
                    .costVsSalePercentage(costVsSalePercentage)
                    .minRecipeCost(min)
                    .maxRecipeCost(max)
                    .recipeItemsCount(recipeItemsCount)
                    .recipeItemsWithoutCost(recipeItemsWithoutCost)
                    .leatherConsumption(leatherConsumption)
                    .leatherCost(leatherCost.setScale(2, RoundingMode.HALF_UP))
                    .build());
        }

        productCosts.sort(Comparator.comparing(
                PurchaseReportResponse.ProductAverageCostItem::getAverageRecipeCost,
                Comparator.nullsLast(Comparator.reverseOrder())));

        BigDecimal averageCostAcrossProducts = productsWithRecipe == 0
                ? BigDecimal.ZERO
                : totalAverageCost.divide(BigDecimal.valueOf(productsWithRecipe), 2, RoundingMode.HALF_UP);

        BigDecimal totalSaleForAvg = BigDecimal.ZERO;
        int productsWithSalePrice = 0;
        BigDecimal totalRowPercentages = BigDecimal.ZERO;
        int productsWithPercentage = 0;
        for (PurchaseReportResponse.ProductAverageCostItem item : productCosts) {
            if (item.getSalePrice() != null && item.getSalePrice().compareTo(BigDecimal.ZERO) > 0) {
                totalSaleForAvg = totalSaleForAvg.add(item.getSalePrice());
                productsWithSalePrice++;
            }
            if (item.getCostVsSalePercentage() != null) {
                totalRowPercentages = totalRowPercentages.add(item.getCostVsSalePercentage());
                productsWithPercentage++;
            }
        }

        BigDecimal averageSalePriceAcrossProducts = productsWithSalePrice == 0
                ? BigDecimal.ZERO
                : totalSaleForAvg.divide(BigDecimal.valueOf(productsWithSalePrice), 2, RoundingMode.HALF_UP);
        BigDecimal averageCostVsSalePercentage = productsWithPercentage == 0
                ? BigDecimal.ZERO
                : totalRowPercentages.divide(BigDecimal.valueOf(productsWithPercentage), 4, RoundingMode.HALF_UP);

        return PurchaseReportResponse.ProductAverageCostReport.builder()
                .productCosts(productCosts)
                .productsWithoutRecipe(Collections.emptyList())
                .materialsWithoutCost(Collections.emptyList())
                .totalProducts(products.size())
                .productsWithRecipe(productsWithRecipe)
                .productsWithoutRecipeCount(0)
                .materialsWithoutCostCount(0)
                .averageCostAcrossProducts(averageCostAcrossProducts)
                .averageSalePriceAcrossProducts(averageSalePriceAcrossProducts)
                .averageCostVsSalePercentage(averageCostVsSalePercentage)
                .build();
    }

    private BigDecimal getCurrentCinchosHourlyCost() {
        BigDecimal payrollCinchos = getConfigValueAsBigDecimal("MANUFACTURING_PAYROLL_CINCHOS");
        BigDecimal payrollWarehouse = getConfigValueAsBigDecimal("MANUFACTURING_PAYROLL_WAREHOUSE");
        Integer minutesCinchos = getConfigValueAsInteger("MANUFACTURING_MINUTES_CINCHOS");
        if (minutesCinchos == null || minutesCinchos <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal totalPayroll = payrollCinchos.add(payrollWarehouse);
        return totalPayroll
                .divide(BigDecimal.valueOf(minutesCinchos), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(60));
    }

    private BigDecimal getCurrentMesasHourlyCost() {
        BigDecimal payrollMesas = getConfigValueAsBigDecimal("MANUFACTURING_PAYROLL_MESAS");
        BigDecimal payrollWarehouse = getConfigValueAsBigDecimal("MANUFACTURING_PAYROLL_WAREHOUSE");
        Integer minutesMesas = getConfigValueAsInteger("MANUFACTURING_MINUTES_MESAS");
        Integer numberOfTablesMesas = getConfigValueAsInteger("MANUFACTURING_NUMBER_OF_TABLES_MESAS");
        if (minutesMesas == null || minutesMesas <= 0 || numberOfTablesMesas == null || numberOfTablesMesas <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal totalPayroll = payrollMesas.add(payrollWarehouse);
        BigDecimal denominator = BigDecimal.valueOf(numberOfTablesMesas).multiply(BigDecimal.valueOf(minutesMesas));
        return totalPayroll
                .divide(denominator, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(60));
    }

    private BigDecimal getProductManufacturingCost(
            ProductEntity product,
            ProductCategoryEntity category,
            BigDecimal costoHoraCinchos,
            BigDecimal costoHoraMesas) {
        if (product == null || product.getPrdTime() == null || product.getPrdTime() <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal hourlyCost = BigDecimal.ZERO;
        boolean isFossCategory = category != null
                && category.getCode() != null
                && "FOSS".equalsIgnoreCase(category.getCode());

        if (isFossCategory) {
            BigDecimal categoryPayroll = category.getPayrollTotal();
            BigDecimal categoryMinutes = category.getAvailableHours();
            Integer categoryTables = category.getNumberOfTables();

            if (categoryPayroll != null && categoryPayroll.compareTo(BigDecimal.ZERO) > 0
                    && categoryMinutes != null && categoryMinutes.compareTo(BigDecimal.ZERO) > 0
                    && categoryTables != null && categoryTables > 0) {
                BigDecimal denominator = categoryMinutes.multiply(BigDecimal.valueOf(categoryTables));
                hourlyCost = categoryPayroll
                        .divide(denominator, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(60));
            } else if (category.getHourlyCost() != null && category.getHourlyCost().compareTo(BigDecimal.ZERO) > 0) {
                hourlyCost = category.getHourlyCost();
            } else {
                hourlyCost = costoHoraCinchos;
            }
        } else {
            hourlyCost = costoHoraMesas;
        }

        return BigDecimal.valueOf(product.getPrdTime()).multiply(hourlyCost);
    }

    /** Suma costo de líneas de la BOM (excluye cuero primario; misma lógica que el cálculo del reporte). */
    private BigDecimal sumMaterialCostForBomItems(
            List<BomItemEntity> items,
            Map<Long, MaterialEntity> materialById) {
        if (items == null || items.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal sub = BigDecimal.ZERO;
        for (BomItemEntity item : items) {
            MaterialEntity material = materialById.get(item.getMaterialId());
            if (material != null && Boolean.TRUE.equals(material.getIsPrimaryLeather())) {
                continue;
            }
            BigDecimal unitCost = material != null ? material.getUnitCost() : null;
            if (unitCost == null || unitCost.compareTo(BigDecimal.ZERO) <= 0) {
                unitCost = material != null ? material.getCost() : null;
            }
            if (unitCost == null || unitCost.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal qty = item.getQuantity() != null ? item.getQuantity() : BigDecimal.ZERO;
            BigDecimal measurement = item.getMeasurement() != null ? item.getMeasurement() : BigDecimal.ONE;
            BigDecimal lineCost = qty.multiply(unitCost);
            if (measurement.compareTo(BigDecimal.ZERO) > 0 && measurement.compareTo(BigDecimal.ONE) != 0) {
                lineCost = lineCost.multiply(measurement);
            }
            sub = sub.add(lineCost);
        }
        return sub;
    }

    /**
     * Entre BOMs candidatas (activas o todas), usa la que tenga mayor costo en materiales para evitar
     * discrepancias con la vista de producto cuando hay varias recetas activas (p. ej. una corta y una completa).
     * Empate en subtotal: mayor id.
     */
    private BomEntity pickBomForCostReport(
            List<BomEntity> candidateBoms,
            Map<Long, MaterialEntity> materialById) {
        BomEntity best = null;
        BigDecimal bestSubtotal = null;
        for (BomEntity bom : candidateBoms) {
            List<BomItemEntity> items = bomItemRepository.findByBomId(bom.getId());
            if (items == null || items.isEmpty()) {
                continue;
            }
            BigDecimal sub = sumMaterialCostForBomItems(items, materialById);
            if (best == null
                    || sub.compareTo(bestSubtotal) > 0
                    || (sub.compareTo(bestSubtotal) == 0 && bom.getId() > best.getId())) {
                best = bom;
                bestSubtotal = sub;
            }
        }
        return best;
    }

    private BigDecimal getConfigValueAsBigDecimal(String key) {
        return systemConfigRepository.findByConfigKey(key)
                .map(entity -> {
                    try {
                        return new BigDecimal(entity.getConfigValue());
                    } catch (Exception e) {
                        return BigDecimal.ZERO;
                    }
                })
                .orElse(BigDecimal.ZERO);
    }

    private Integer getConfigValueAsInteger(String key) {
        return systemConfigRepository.findByConfigKey(key)
                .map(entity -> {
                    try {
                        return Integer.parseInt(entity.getConfigValue());
                    } catch (Exception e) {
                        return 0;
                    }
                })
                .orElse(0);
    }
}

