package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public class PurchaseReportResponse {
    // Clase contenedora para los diferentes tipos de reportes
    // No necesita campos ni constructores propios
    // CP-08-001: Reporte de Órdenes por Estado
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrdersByStatusReport {
        private Map<String, StatusSummary> statusSummary;
        private BigDecimal totalAmount;
        private Integer totalOrders;
        private LocalDate startDate;
        private LocalDate endDate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StatusSummary {
        private String status;
        private Integer count;
        private BigDecimal totalAmount;
        private Double percentage;
    }

    // CP-08-002: Reporte de Compras por Proveedor
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PurchasesBySupplierReport {
        private List<SupplierSummary> suppliers;
        private BigDecimal grandTotal;
        private Integer totalOrders;
        private LocalDate startDate;
        private LocalDate endDate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SupplierSummary {
        private Long supplierId;
        private String supplierName;
        private Integer orderCount;
        private BigDecimal totalAmount;
        private Double percentage;
    }

    // CP-08-003: Reporte de Inventario Actual
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CurrentInventoryReport {
        private List<MaterialInventoryItem> materials;
        private BigDecimal totalInventoryValue;
        private Integer totalMaterials;
        private Integer criticalMaterialsCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MaterialInventoryItem {
        private Long materialId;
        private String sku;
        private String name;
        private BigDecimal currentStock;
        private BigDecimal minStock;
        private BigDecimal maxStock;
        private BigDecimal unitCost;
        private BigDecimal totalValue;
        private Boolean isCritical;
        private String status;
    }

    // CP-08-004: Reporte de Materiales Críticos
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CriticalMaterialsReport {
        private List<CriticalMaterialItem> materials;
        private Integer totalCritical;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CriticalMaterialItem {
        private Long materialId;
        private String sku;
        private String name;
        private BigDecimal currentStock;
        private BigDecimal minStock;
        private BigDecimal reorderPoint;
        private BigDecimal daysOfInventory;
        private BigDecimal suggestedQuantity;
        private String priority; // ALTA, MEDIA, BAJA
        private BigDecimal unitCost;
        private BigDecimal totalValue;
    }

    // CP-08-005: Reporte de Asientos Contables
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AccountingEntriesReport {
        private List<AccountingEntryResponse> entries;
        private BigDecimal totalDebits;
        private BigDecimal totalCredits;
        private BigDecimal balance;
        private Map<String, DocumentTypeSummary> byDocumentType;
        private LocalDate startDate;
        private LocalDate endDate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DocumentTypeSummary {
        private String documentType;
        private Integer count;
        private BigDecimal totalDebits;
        private BigDecimal totalCredits;
    }

    // CP-08-006: Dashboard Ejecutivo
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExecutiveDashboard {
        private DashboardMetrics metrics;
        private List<RecentOrder> recentOrders;
        private List<CriticalMaterialItem> topCriticalMaterials;
        private Map<String, BigDecimal> monthlyTrend;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DashboardMetrics {
        private BigDecimal totalPurchasedPeriod;
        private Integer totalOrders;
        private Integer activeSuppliers;
        private Integer criticalMaterials;
        private BigDecimal averageOrderValue;
        private Integer pendingOrders;
        private Integer receivedOrders;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecentOrder {
        private Long id;
        private String code;
        private String supplierName;
        private BigDecimal total;
        private String status;
        private LocalDate orderDate;
    }

    // CP-08-007: Reporte Promedio de Costos por Producto
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProductAverageCostReport {
        private List<ProductAverageCostItem> productCosts;
        private List<ProductWithoutRecipeItem> productsWithoutRecipe;
        private List<MaterialWithoutCostItem> materialsWithoutCost;
        private Integer totalProducts;
        private Integer productsWithRecipe;
        private Integer productsWithoutRecipeCount;
        private Integer materialsWithoutCostCount;
        private BigDecimal averageCostAcrossProducts;
        /** Promedio aritmético del precio de venta (solo filas con precio mayor que cero). */
        private BigDecimal averageSalePriceAcrossProducts;
        /** Promedio aritmético del % costo/precio de cada fila (solo filas con precio y porcentaje calculado). */
        private BigDecimal averageCostVsSalePercentage;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProductAverageCostItem {
        private Long productId;
        private String productCode;
        private String productName;
        private Integer recipeCount;
        private BigDecimal averageRecipeCost;
        private BigDecimal salePrice;
        private BigDecimal costVsSalePercentage;
        private BigDecimal minRecipeCost;
        private BigDecimal maxRecipeCost;
        private Integer recipeItemsCount;
        private Integer recipeItemsWithoutCost;
        private BigDecimal leatherConsumption;
        private BigDecimal leatherCost;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProductWithoutRecipeItem {
        private Long productId;
        private String productCode;
        private String productName;
        private String status;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MaterialWithoutCostItem {
        private Long materialId;
        private String sku;
        private String name;
        private BigDecimal unitCost;
        private BigDecimal legacyCost;
        private String status;
    }
}

