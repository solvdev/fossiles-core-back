package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.projection.KioskSaleHeaderRow;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSaleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineSaleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Carga de ventas válidas por fuente en una sola consulta por canal. Aplica los mismos filtros de venta
 * válida que usaba el dashboard anterior; el cálculo de dinero y desgloses queda en cada servicio.
 */
@Component
@RequiredArgsConstructor
public class SalesSourceLoader {

    private final KioskSaleRepository kioskSaleRepository;
    private final OnlineSaleRepository onlineSaleRepository;
    private final ProductionOrderRepository productionOrderRepository;
    private final ProductRepository productRepository;
    private final CustomerAccountService customerAccountService;

    /** Cabeceras de ventas de kiosko válidas (no canceladas, no anuladas, no de prueba) de todos los kioskos. */
    List<KioskSaleHeaderRow> loadKioskHeaders(LocalDate from, LocalDate to) {
        return kioskSaleRepository.findHeaderRowsBySaleDateBetween(from, to).stream()
                .filter(row -> !"CANCELLED".equalsIgnoreCase(safeText(row.status())))
                .filter(row -> KioskPosService.countsForProductionMetrics(toStatusEntity(row)))
                .collect(Collectors.toList());
    }

    List<OnlineSaleEntity> loadOnlineSales(LocalDate from, LocalDate to) {
        return onlineSaleRepository.findBySaleDateBetweenOrderBySaleDateDesc(from, to).stream()
                .filter(sale -> !isCancelledOnlineSale(sale))
                .collect(Collectors.toList());
    }

    List<ProductionOrderEntity> loadVendorOrders(LocalDate from, LocalDate to) {
        return productionOrderRepository.findVendorSalesDashboardOrders(from, to).stream()
                .filter(customerAccountService::isLfVendorOrder)
                .filter(order -> order.getVendorShipmentVoidedAt() == null)
                .filter(order -> {
                    LocalDate orderDate = resolveVendorOrderDate(order);
                    return orderDate != null && !orderDate.isBefore(from) && !orderDate.isAfter(to);
                })
                .collect(Collectors.toList());
    }

    Map<Long, ProductEntity> loadProductsById(Collection<Long> productIds) {
        Set<Long> ids = productIds.stream().filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, ProductEntity> products = new HashMap<>();
        for (ProductEntity product : productRepository.findAllById(ids)) {
            products.put(product.getId(), product);
        }
        return products;
    }

    static LocalDate resolveVendorOrderDate(ProductionOrderEntity order) {
        if (order.getStartDate() != null) {
            return order.getStartDate();
        }
        return order.getCreatedAt() != null ? order.getCreatedAt().toLocalDate() : null;
    }

    static boolean isCancelledOnlineSale(OnlineSaleEntity sale) {
        String status = safeText(sale.getStatus()).toUpperCase(Locale.ROOT);
        return "CANCELADO".equals(status) || "CANCELADA".equals(status) || "ANULADA".equals(status);
    }

    private static KioskSaleEntity toStatusEntity(KioskSaleHeaderRow row) {
        return KioskSaleEntity.builder()
                .id(row.id())
                .status(row.status())
                .testSale(row.testSale())
                .build();
    }

    private static String safeText(String value) {
        return value != null ? value.trim() : "";
    }
}
