package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.SalesSourceDetailResponse;
import com.fossiles.fossilescorebackend.application.dto.response.SalesSourceDetailResponse.SaleRow;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.service.CustomerAccountService.VendorOrderBreakdown;
import com.fossiles.fossilescorebackend.application.service.SalesDashboardSupport.BreakdownAccumulator;
import com.fossiles.fossilescorebackend.application.service.SalesDashboardSupport.DateRange;
import com.fossiles.fossilescorebackend.application.service.SalesDashboardSupport.ProductAccumulator;
import com.fossiles.fossilescorebackend.application.service.SalesDashboardSupport.SaleFact;
import com.fossiles.fossilescorebackend.application.util.FinishedProductClassifier;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.CustomerEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductionOrderItemEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.CustomerRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductionOrderItemRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.ProductInventorySizesJson;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import static com.fossiles.fossilescorebackend.application.service.SalesDashboardSupport.*;

/**
 * Dashboard de ventas del vendedor LF (órdenes OPV/OPC). El total de cada orden es una estimación
 * (ítems + empaque + envío) calculada en lote con {@link CustomerAccountService#estimateVendorOrderBreakdowns}.
 */
@Service
@RequiredArgsConstructor
public class VendorSalesDashboardService {

    private static final int ID_CHUNK_SIZE = 1000;

    private final SalesSourceLoader sourceLoader;
    private final ProductionOrderItemRepository productionOrderItemRepository;
    private final CustomerRepository customerRepository;
    private final CustomerAccountService customerAccountService;
    private final SalesDashboardCache cache;

    public SalesSourceDetailResponse getDashboard(
            LocalDate startDate, LocalDate endDate, boolean refresh) throws BusinessException {
        DateRange range = resolveRange(startDate, endDate);
        String key = SalesDashboardCache.key(CHANNEL_VENDOR, range.from(), range.to(), null);
        return cache.get(key, refresh, () -> build(range));
    }

    SalesSourceDetailResponse build(DateRange range) {
        List<ProductionOrderEntity> orders = sourceLoader.loadVendorOrders(loadFrom(range), range.to());

        List<ProductionOrderItemEntity> items = new ArrayList<>();
        List<Long> orderIds = orders.stream().map(ProductionOrderEntity::getId).toList();
        for (List<Long> chunk : partition(orderIds, ID_CHUNK_SIZE)) {
            items.addAll(productionOrderItemRepository.findByProductionOrderIdIn(chunk));
        }
        Map<Long, ProductEntity> productsById = sourceLoader.loadProductsById(
                items.stream().map(ProductionOrderItemEntity::getProductId).toList());
        Map<Long, VendorOrderBreakdown> breakdownByOrder =
                customerAccountService.estimateVendorOrderBreakdowns(orders, items, productsById);
        Map<Long, List<ProductionOrderItemEntity>> itemsByOrder = items.stream()
                .filter(i -> i.getProductionOrderId() != null)
                .collect(Collectors.groupingBy(ProductionOrderItemEntity::getProductionOrderId));
        Map<Long, String> customerNames = loadCustomerNames(orders, range);

        List<SaleFact> facts = new ArrayList<>();
        ProductAccumulator topProducts = new ProductAccumulator();
        BreakdownAccumulator byCustomer = new BreakdownAccumulator();
        BreakdownAccumulator byOrderType = new BreakdownAccumulator();
        BreakdownAccumulator byStatus = new BreakdownAccumulator();
        List<SaleRow> rows = new ArrayList<>();

        for (ProductionOrderEntity order : orders) {
            LocalDate orderDate = SalesSourceLoader.resolveVendorOrderDate(order);
            VendorOrderBreakdown breakdown = breakdownByOrder.get(order.getId());
            BigDecimal total = breakdown != null ? breakdown.total() : BigDecimal.ZERO;
            if (!range.contains(orderDate)) {
                facts.add(SaleFact.moneyOnly(orderDate, total));
                continue;
            }
            BigDecimal productAmount = BigDecimal.ZERO;
            BigDecimal units = BigDecimal.ZERO;
            boolean hasAnyItem = false;
            List<String> finishedNames = new ArrayList<>();
            for (ProductionOrderItemEntity item : itemsByOrder.getOrDefault(order.getId(), List.of())) {
                hasAnyItem = true;
                if (FinishedProductClassifier.isPackaging(item.getProductId(), productsById)) {
                    continue;
                }
                BigDecimal subtotal = breakdown != null
                        ? nz(breakdown.subtotalByItemId().get(item.getId()))
                        : BigDecimal.ZERO;
                BigDecimal quantity = BigDecimal.valueOf(resolveItemQuantity(item));
                ProductEntity product = productsById.get(item.getProductId());
                String name = product != null && product.getName() != null && !product.getName().isBlank()
                        ? product.getName()
                        : (item.getBrandName() != null && !item.getBrandName().isBlank() ? item.getBrandName() : "Producto");
                productAmount = productAmount.add(subtotal);
                units = units.add(quantity);
                topProducts.add(item.getProductId(), product != null ? product.getCode() : null,
                        name, quantity, subtotal);
                String baseName = FinishedProductClassifier.baseProductName(name);
                if (!finishedNames.contains(baseName)) {
                    finishedNames.add(baseName);
                }
            }
            BigDecimal shipping = breakdown != null ? money(breakdown.shippingCost()) : BigDecimal.ZERO;
            BigDecimal packaging = total.subtract(productAmount).subtract(shipping);
            facts.add(new SaleFact(orderDate, total, productAmount, packaging, shipping, units));

            String customer = resolveCustomerLabel(order, customerNames);
            String customerKey = order.getCustomerId() != null ? "C" + order.getCustomerId() : customer;
            byCustomer.add(customerKey, customer, total);
            String kind = customerAccountService.resolveReceivableOrderKind(order);
            byOrderType.add(kind, "OPC".equals(kind) ? "OPC marcas y cinchos" : "OPV Fossiles", total);
            byStatus.add(order.getStatus(), total);
            rows.add(SaleRow.builder()
                    .id(order.getId())
                    .saleDate(orderDate)
                    .reference(order.getCode())
                    .productLabel(productLabel(finishedNames, hasAnyItem, "Orden " + order.getCode()))
                    .quantity(units)
                    .totalAmount(money(total))
                    .status(order.getStatus())
                    .party(customer)
                    .build());
        }

        SalesSourceDetailResponse.SourceKpis kpis = kpis(range, facts, today());
        DateRange previous = previousPeriod(range);
        Map<String, List<SalesSourceDetailResponse.BreakdownRow>> breakdowns = new LinkedHashMap<>();
        breakdowns.put("byCustomer", byCustomer.rows(kpis.getTotalAmount()));
        breakdowns.put("byOrderType", byOrderType.rows(kpis.getTotalAmount()));
        breakdowns.put("byStatus", byStatus.rows(kpis.getTotalAmount()));

        return SalesSourceDetailResponse.builder()
                .channel(CHANNEL_VENDOR)
                .label("Vendedor LF")
                .startDate(range.from())
                .endDate(range.to())
                .previousStartDate(previous.from())
                .previousEndDate(previous.to())
                .kpis(kpis)
                .dailySeries(dailySeries(range, facts))
                .monthlyTrend(monthlyTrend(range.to(), facts))
                .topProducts(topProducts.top(TOP_PRODUCTS_LIMIT))
                .recentSales(rows.stream()
                        .sorted(Comparator.comparing(SaleRow::getSaleDate, Comparator.nullsLast(Comparator.reverseOrder()))
                                .thenComparing(SaleRow::getId, Comparator.reverseOrder()))
                        .limit(RECENT_SALES_LIMIT)
                        .toList())
                .breakdowns(breakdowns)
                .kioskOptions(null)
                .build();
    }

    private Map<Long, String> loadCustomerNames(List<ProductionOrderEntity> orders, DateRange range) {
        Set<Long> customerIds = orders.stream()
                .filter(o -> range.contains(SalesSourceLoader.resolveVendorOrderDate(o)))
                .map(ProductionOrderEntity::getCustomerId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (customerIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        for (CustomerEntity customer : customerRepository.findAllById(customerIds)) {
            names.put(customer.getId(), customer.getName());
        }
        return names;
    }

    private static String resolveCustomerLabel(ProductionOrderEntity order, Map<Long, String> customerNames) {
        String registered = order.getCustomerId() != null ? customerNames.get(order.getCustomerId()) : null;
        if (registered != null && !registered.isBlank()) {
            return registered.trim();
        }
        return labelOrDefault(order.getCustomerName());
    }

    private static int resolveItemQuantity(ProductionOrderItemEntity item) {
        Map<String, BigDecimal> sizes = ProductInventorySizesJson.parse(item.getSizesData());
        if (!sizes.isEmpty()) {
            return sizes.values().stream()
                    .filter(Objects::nonNull)
                    .mapToInt(BigDecimal::intValue)
                    .sum();
        }
        return item.getQuantity() != null ? item.getQuantity() : 0;
    }
}
